package af.shizuku.manager.admin

import af.shizuku.manager.R
import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DhizukuAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(
        context: Context,
        intent: Intent,
    ) {
        super.onEnabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_enabled, Toast.LENGTH_SHORT).show()
        // setBackupServiceEnabled is API 26+, and a missing method is an Error the catch below misses.
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
            val componentName = android.content.ComponentName(context, DhizukuAdminReceiver::class.java)
            dpm.setBackupServiceEnabled(componentName, true)
        } catch (_: Exception) {
            // Ignored, may not be device owner yet or method not available
        }
    }

    override fun onDisabled(
        context: Context,
        intent: Intent,
    ) {
        super.onDisabled(context, intent)
        Toast.makeText(context, R.string.dhizuku_device_owner_disabled, Toast.LENGTH_SHORT).show()
    }
}
