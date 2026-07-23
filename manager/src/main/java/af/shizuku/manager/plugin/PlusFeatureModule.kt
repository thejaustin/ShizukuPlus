package af.shizuku.manager.plugin

import android.content.Context
import android.content.Intent
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/**
 * A self-describing Plus feature that can be discovered and launched via [PlusFeatureRegistry].
 *
 * Existing hardcoded settings entries are NOT migrated to this interface - doing so without
 * device testing across all of them is too risky. This is new infrastructure for future features
 * to plug into; the Scripting & Snippets feature (#11, see
 * af.shizuku.manager.scripting.ScriptingFeatureModule) registers itself here as a working proof
 * of concept while remaining fully functional through its existing direct preference wiring.
 */
interface PlusFeatureModule {
    /** Stable, unique identifier - used as the registry key. */
    val id: String

    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val summaryRes: Int

    @get:DrawableRes
    val iconRes: Int

    /** Builds the Intent that launches this feature's UI. */
    fun launchIntent(context: Context): Intent
}
