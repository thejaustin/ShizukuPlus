package af.shizuku.manager.utils

import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import android.content.Context
import android.widget.Toast
import timber.log.Timber

/**
 * A toast that only shows over the app's own screens, unless the user allowed messages over other
 * apps. A toast posted from the background lands on whatever app is open, where it reads as that
 * app's message.
 */
object AppToast {
    /** Shows [text], or returns false when it was held back (the caller may notify instead). */
    fun show(
        context: Context,
        text: CharSequence,
        long: Boolean = false,
    ): Boolean {
        if (!ShizukuApplication.isOnScreen() && !ShizukuSettings.isToastsOverOtherAppsEnabled()) {
            Timber.tag("AppToast").i("not shown over another app: %s", text)
            return false
        }
        Toast.makeText(context, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        return true
    }
}
