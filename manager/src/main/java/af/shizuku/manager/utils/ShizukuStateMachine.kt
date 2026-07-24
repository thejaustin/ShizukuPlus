package af.shizuku.manager.utils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.BuildConfig
import rikka.shizuku.Shizuku
import io.sentry.Sentry
import io.sentry.Breadcrumb

object ShizukuStateMachine {

    enum class State { STARTING, RUNNING, STOPPING, STOPPED, CRASHED }

    private var state = AtomicReference<State>(State.STOPPED)
    private val listeners = CopyOnWriteArrayList<(State) -> Unit>()

    init {
        Shizuku.addBinderReceivedListenerSticky(
            Shizuku.OnBinderReceivedListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder received - service is now RUNNING").apply {
                    category = "shizuku.service"
                })
                set(State.RUNNING)
            }
        )
        Shizuku.addBinderDeadListener(
            Shizuku.OnBinderDeadListener {
                Sentry.addBreadcrumb(Breadcrumb("Binder dead - service connection lost").apply {
                    category = "shizuku.service"
                    level = io.sentry.SentryLevel.WARNING
                })
                setDead()
            }
        )
    }

    fun get(): State = state.get()

    private fun transition(transform: (State) -> State) {
        // Capture the value our own CAS produced: a separate state.get() after getAndUpdate can
        // observe a concurrent transition's later write, making oldState == newState and silently
        // skipping listener/broadcast side effects for a transition that did occur.
        var computed: State? = null
        val oldState = state.getAndUpdate { current -> transform(current).also { computed = it } }
        val newState = computed!!
        if(oldState != newState) {
            listeners.forEach { it(newState) }
            Timber.tag("ShizukuStateMachine").d(newState.toString())

            // Record which app build is starting this server instance. All deliberate start paths
            // (AdbStarter, ShizukuReceiverStarter, tile, StarterActivity) funnel through STARTING,
            // so this captures every fresh start centrally and lets isServerVersionSkewed() later
            // detect a running server left behind by a pre-update build.
            if (newState == State.STARTING) {
                try {
                    ShizukuSettings.setServerStartedBuild(BuildConfig.VERSION_CODE)
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to record server start build")
                }
            }

            if (newState == State.RUNNING || newState == State.STOPPED || newState == State.CRASHED) {
                try {
                    val context = ShizukuApplication.appContext
                    af.shizuku.manager.automation.AutomationEngine.dispatchEvent(
                        af.shizuku.manager.automation.ShizukuStateEvent(newState == State.RUNNING),
                        context
                    )
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to dispatch automation event")
                }
            }

            // Broadcast state change for widgets and other receivers
            try {
                val context = ShizukuApplication.appContext
                val intent = android.content.Intent("af.shizuku.manager.action.STATE_CHANGED").apply {
                    setPackage(context.packageName)
                }
                context.sendBroadcast(intent)
            } catch (e: UninitializedPropertyAccessException) {
                Timber.tag("ShizukuStateMachine").w("Skipping broadcast: appContext not initialized yet")
            }
        }
    }

    fun set(newState: State) = transition { newState }

    fun setDead() = transition {
        when (it) {
            State.RUNNING -> State.CRASHED
            State.STOPPING -> {
                try {
                    val context = ShizukuApplication.appContext
                    val permissionGranted = context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
                    val shouldDisableUsbDebugging = permissionGranted && ShizukuSettings.getAutoDisableUsbDebugging()
                    if (shouldDisableUsbDebugging) {
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                    }
                } catch (e: UninitializedPropertyAccessException) {
                    Timber.tag("ShizukuStateMachine").w("Skipping USB debugging disable: appContext not initialized yet")
                } catch (e: Exception) {
                    Timber.tag("ShizukuStateMachine").w(e, "Failed to disable USB debugging")
                }
                State.STOPPED
            }
            else -> it
        }
    }

    fun update(): State {
        val span = Sentry.getSpan()?.startChild("ipc.shizuku", "pingBinder")
        val isAlive = try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            false
        } finally {
            span?.finish()
        }

        val currentState = get()
        val state = when {
            isAlive -> State.RUNNING
            currentState == State.STARTING -> State.STARTING
            currentState == State.STOPPING -> State.STOPPING
            currentState == State.CRASHED -> State.CRASHED
            else -> State.STOPPED
        }
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
    }

    /**
     * True when the running privileged server was started by an app build older than the one now
     * installed — i.e. the app was updated but the server (a separate long-lived process) is still
     * running the old code. The binder wire protocol can differ across versions, so this can
     * silently break connections for third-party apps until the service is restarted. Returns false
     * when the starting build is unknown (0, e.g. server predates this tracking) or already current.
     */
    fun isServerVersionSkewed(): Boolean {
        if (!isRunning()) return false
        val startedBuild = ShizukuSettings.getServerStartedBuild()
        return startedBuild in 1 until BuildConfig.VERSION_CODE
    }

    fun isDead(): Boolean {
        return (get() == State.STOPPED || get() == State.CRASHED)
    }

    fun addListener(listener: (State) -> Unit) {
        listeners.add(listener)
        listener(state.get())
    }

    fun removeListener(listener: (State) -> Unit) {
        listeners.remove(listener)
    }

    fun asFlow(): Flow<State> = callbackFlow {
        val listener: (State) -> Unit = { trySend(it).isSuccess }
        addListener(listener)
        awaitClose { removeListener(listener) }
    }

}
