package af.shizuku.manager.scripting

import android.content.Context
import android.content.Intent
import af.shizuku.manager.R
import af.shizuku.manager.plugin.PlusFeatureModule

/** Proof-of-concept [PlusFeatureModule] registration for the Scripting & Snippets feature (#11). */
object ScriptingFeatureModule : PlusFeatureModule {
    override val id: String = "scripting"
    override val titleRes: Int = R.string.scripting_title
    override val summaryRes: Int = R.string.scripting_summary
    override val iconRes: Int = R.drawable.ic_code_24

    override fun launchIntent(context: Context): Intent =
        Intent(context, ScriptingActivity::class.java)
}
