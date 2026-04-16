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
        val oldState = state.getAndUpdate(transform)
        val newState = transform(oldState)
        if(oldState != newState) {
            listeners.forEach { it(newState) }
            Timber.tag("ShizukuStateMachine").d(newState.toString())

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
        
        val state = if (isAlive) State.RUNNING else State.STOPPED
        set(state)
        return state
    }

    fun isRunning(): Boolean {
        return get() == State.RUNNING
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