package af.shizuku.manager.automation

import android.content.Context
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

sealed class AutomationEvent
data class ShizukuStateEvent(val isRunning: Boolean) : AutomationEvent()
data class NetworkEvent(val isWifiConnected: Boolean, val ssid: String?) : AutomationEvent()
data class ForegroundAppEvent(val packageName: String) : AutomationEvent()

interface AutomationRule {
    val name: String
    fun evaluate(event: AutomationEvent, context: Context): Boolean
    fun execute(context: Context)
}

object AutomationEngine {
    private val rules = CopyOnWriteArrayList<AutomationRule>()

    fun registerRule(rule: AutomationRule) {
        if (!rules.contains(rule)) {
            rules.add(rule)
        }
    }

    fun unregisterRule(rule: AutomationRule) {
        rules.remove(rule)
    }

    fun dispatchEvent(event: AutomationEvent, context: Context) {
        Timber.tag("AutomationEngine").d("Dispatching event: $event")
        for (rule in rules) {
            try {
                if (rule.evaluate(event, context)) {
                    Timber.tag("AutomationEngine").i("Executing rule: ${rule.name}")
                    rule.execute(context)
                }
            } catch (e: Exception) {
                Timber.tag("AutomationEngine").e(e, "Error evaluating/executing rule: ${rule.name}")
            }
        }
    }
}
