package af.shizuku.manager.utils

import android.content.ClipData
import android.os.PersistableBundle

// ClipDescription.EXTRA_IS_SENSITIVE (API 33); older versions ignore the extra.
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"

/** Plain-text clip that Android keeps out of the clipboard preview and keyboard suggestions. */
fun sensitivePlainText(
    label: CharSequence,
    text: CharSequence,
): ClipData =
    ClipData.newPlainText(label, text).apply {
        description.extras = PersistableBundle().apply { putBoolean(EXTRA_IS_SENSITIVE, true) }
    }
