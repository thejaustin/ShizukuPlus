package rikka.shizuku.server.util

import android.os.IBinder
import java.util.function.BooleanSupplier

/**
 * Per-call check for the sub-binders behind the "Unrestricted access" developer options.
 * The getter already refuses to hand them out while the option is off, but an app that got one
 * earlier keeps the binder; checking each transaction makes turning the option off take effect.
 */
object RiskyBinderGate {
    fun enforce(code: Int, allowed: BooleanSupplier, name: String) {
        if (code in IBinder.FIRST_CALL_TRANSACTION..IBinder.LAST_CALL_TRANSACTION && !allowed.asBoolean) {
            throw SecurityException("$name is turned off in Shizuku+ Developer options")
        }
    }
}
