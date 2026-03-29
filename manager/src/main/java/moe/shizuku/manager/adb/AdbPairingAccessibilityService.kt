package moe.shizuku.manager.adb

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import android.provider.Settings
import android.util.Log
import android.content.ActivityNotFoundException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.R
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbPairingClient
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import java.net.ConnectException

class AdbPairingAccessibilityService : AccessibilityService() {

    var port: Int? = null
    var password: String? = null

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onServiceConnected() {
        super.onServiceConnected()
        
        val isSamsung = EnvironmentUtils.isSamsung()
        val isTv = EnvironmentUtils.isTelevision()
        
        if (!(isTv || isSamsung) || !EnvironmentUtils.isTlsSupported()) {
            Toast.makeText(this, getString(R.string.toast_accessibility_tv_only), Toast.LENGTH_SHORT).show()
            disableSelf()
            return
        }

        // On Samsung, we don't necessarily want to jump to MainActivity immediately
        // as the user might be manually navigating Developer Options.
        if (isTv) {
            val intent = Intent(this, MainActivity::class.java).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or 
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
                putExtra(HomeActivity.EXTRA_SHOW_PAIRING_DIALOG, true)
            }
            startActivity(intent)
        } else {
            Toast.makeText(this, "Shizuku+ Accessibility active: Monitoring for Pairing Code...", Toast.LENGTH_SHORT).show()
        }

        // Auto-disable after 60 seconds to prevent lingering background usage
        serviceScope.launch(Dispatchers.Main) {
            delay(60_000)
            if (port == null || password == null) {
                Toast.makeText(this@AdbPairingAccessibilityService, getString(R.string.toast_pairing_timeout), Toast.LENGTH_LONG).show()
                disableSelf()
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (port != null && password != null) return

        val source = event.source ?: return
        val text = source.text ?: ""
        
        // Debug Samsung-specific dialog titles
        if (EnvironmentUtils.isSamsung() && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            if (className.contains("AlertDialog") || className.contains("Dialog")) {
                Log.d("AdbAccessibility", "Samsung Dialog detected: $text")
            }
        }

        val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
        val passwordRegex = Regex("""\d{6}""")

        // Standard IP:Port check
        ipPortRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { port = it }
        
        // Samsung specific: sometimes the port is in a different view or has specific labels
        if (port == null && text.contains("Port", ignoreCase = true)) {
            val portMatch = Regex("""\d{5}""").find(text)
            portMatch?.value?.toIntOrNull()?.let { port = it }
        }

        passwordRegex.find(text)?.value?.let { password = it }

        // Recursive search for children if text is empty on parent (Samsung UI optimization)
        if (port == null || password == null) {
            findPortAndPasswordInNode(source)
        }

        val currentPort = port
        val currentPassword = password
        if (currentPort != null && currentPassword != null) {
            val portValue = currentPort
            val passwordValue = currentPassword

            var toastMsg = getString(R.string.notification_adb_pairing_failed_title)
            
            serviceScope.launch {
                val host = "127.0.0.1"

                val key = try {
                    AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                } catch (e: Throwable) {
                    toastMsg = getString(R.string.adb_error_key_store)
                    return@launch
                }

                AdbPairingClient(host, portValue, passwordValue, key).runCatching {
                    start()
                }.onFailure {
                    when (it) {
                        is ConnectException -> toastMsg = getString(R.string.cannot_connect_port)
                        is AdbInvalidPairingCodeException -> toastMsg = getString(R.string.paring_code_is_wrong)
                        is AdbKeyException -> toastMsg = getString(R.string.adb_error_key_store)
                    }
                }.onSuccess {
                    if (it) {
                        toastMsg = "${getString(R.string.notification_adb_pairing_succeed_title)}. ${getString(R.string.notification_adb_pairing_succeed_text)}"
                   
                        val intent = Intent(this@AdbPairingAccessibilityService, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        }
                        startActivity(intent)
                    }
                }
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@AdbPairingAccessibilityService, toastMsg, Toast.LENGTH_LONG).show()
                }
                disableSelf()
            }
        }
    }

    private fun findPortAndPasswordInNode(node: android.view.accessibility.AccessibilityNodeInfo?, depth: Int = 0) {
        if (node == null || depth > 10) return // Prevent excessive recursion causing ANRs on complex Samsung UIs
        if (port != null && password != null) return

        val text = node.text?.toString() ?: ""
        if (text.isNotEmpty()) {
            val ipPortRegex = Regex("""(?:\d{1,3}\.){3}\d{1,3}:(\d{2,5})""")
            val passwordRegex = Regex("""\d{6}""")

            if (port == null) {
                ipPortRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()?.let { port = it }
            }
            if (password == null) {
                passwordRegex.find(text)?.value?.let { password = it }
            }
        }

        for (i in 0 until node.childCount) {
            findPortAndPasswordInNode(node.getChild(i), depth + 1)
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        serviceScope.cancel()
        return super.onUnbind(intent)
    }

}