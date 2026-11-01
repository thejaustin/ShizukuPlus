package af.shizuku.manager.starter

import af.shizuku.manager.ShizukuSettings
import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * The public "start the service" entry point (action af.shizuku.manager.action.START_SERVICE)
 * for other apps and automations. It opens [StarterActivity], which is not exported, with only
 * what Shizuku+ decides itself: root if the last start was root, otherwise ADB with port
 * discovery. Extras from the caller are ignored. StarterActivity used to be exported and trusted
 * them, so any app could aim the ADB client at a port it listened on (and have Shizuku+'s ADB
 * key sign for it), claim a user tap to skip the unanswered-dialog guard, or ask for root.
 */
class ExternalStartActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startActivity(
            Intent(this, StarterActivity::class.java)
                .putExtra(StarterActivity.EXTRA_IS_ROOT, ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ROOT),
        )
        finish()
    }
}
