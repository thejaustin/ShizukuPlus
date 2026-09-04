package af.shizuku.manager.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import coil3.load
import coil3.request.crossfade
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.databinding.ActivityRootCompatibilityBinding
import af.shizuku.manager.databinding.AppListItemBinding
import af.shizuku.manager.databinding.ListSectionHeaderBinding
import timber.log.Timber
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.database.RootCompatHelper
import af.shizuku.manager.shell.ShellTutorialActivity
import rikka.shizuku.Shizuku
import af.shizuku.manager.database.RootSupportLevel
class RootCompatibilityActivity : AppBarActivity() {

    companion object {
        private const val TAG = "RootCompatibilityAct"
    }

    private var resolvedSuPath: String? = null
    // Cached once per activity instance — avoids repeated Shizuku IPC in onBindViewHolder.
    private var isRoot: Boolean = false
    private var isAdbMode: Boolean = false
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CategorizedSuggestedAppsAdapter
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityRootCompatibilityBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Cache privilege mode once — avoids repeated Shizuku IPC in onBindViewHolder.
        isRoot = try { Shizuku.pingBinder() && Shizuku.getUid() == 0 } catch (_: Exception) { false }
        isAdbMode = try { Shizuku.pingBinder() && Shizuku.getUid() == 2000 } catch (_: Exception) { false }

        resolvedSuPath = resolveSuPath()

        // Android 16+ ADB mode: /data/local/tmp is SELinux-blocked from the shell process, so the
        // tmp deploy is always skipped. Show a banner directing the user to the export flow instead.
        if (isAdbMode && Build.VERSION.SDK_INT >= 36) {
            binding.api36AdbWarningCard.visibility = View.VISIBLE
            binding.btnApi36Export.setContent {
                af.shizuku.core.ui.compose.Button(
                    onClick = {
                        startActivity(Intent(this@RootCompatibilityActivity, ShellTutorialActivity::class.java))
                    }
                ) {
                    androidx.compose.material3.Text(getString(R.string.su_bridge_export_files))
                }
            }
        }

        // Show the setup card whenever a path is available OR the tmp deploy might provide one.
        // ADB mode users can still auto-configure GLOBAL_SETTINGS_APPS (AdAway, AFWall+, etc.)
        // via `settings put global`, so the card is useful for them too.
        val exportPath = resolvedSuPath
        if (exportPath != null) {
            binding.globalSuPath.text = exportPath
        }
        binding.globalSetupCard.isVisible = exportPath != null || Shizuku.pingBinder()

        binding.btnCopyGlobal.setContent {
            af.shizuku.core.ui.compose.Button(
                // Read the field at click time so a later /data/local/tmp deploy is reflected.
                onClick = { copyToClipboard(resolvedSuPath ?: return@Button) }
            ) {
                androidx.compose.material3.Text(getString(R.string.su_bridge_copy_path))
            }
        }

        // Deploy the bridge to /data/local/tmp and prefer that path: it's exec-permitted (shared
        // storage is usually noexec, so apps can't exec the su path there) and holds the
        // read-only dex app_process requires on Android 14+. Works independently of whether the
        // user has set an export directory — falls back to the storage path if deploy fails.
        lifecycleScope.launch {
            val tmpPath = RootCompatHelper.deployBridgeToTmp(this@RootCompatibilityActivity)
            if (tmpPath != null && !isFinishing) {
                resolvedSuPath = tmpPath
                binding.globalSuPath.text = tmpPath
                binding.globalSetupCard.isVisible = true
            }
        }

        // "Setup All" works for both root (all automatable apps) and ADB mode (GLOBAL_SETTINGS_APPS only).
        binding.btnSetupAll.setContent {
            af.shizuku.core.ui.compose.Button(
                onClick = {
                    val path = resolvedSuPath ?: run {
                        Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_no_export, Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    lifecycleScope.launch {
                        val count = RootCompatHelper.autoSetupAll(this@RootCompatibilityActivity, path)
                        if (!isFinishing && !isDestroyed) {
                            if (count > 0) {
                                Toast.makeText(this@RootCompatibilityActivity, getString(R.string.su_bridge_magic_setup_all_summary, count), Toast.LENGTH_LONG).show()
                            } else {
                                Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_magic_setup_all_no_apps, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
            ) {
                androidx.compose.material3.Text(getString(R.string.su_bridge_setup_all))
            }
        }

        // Device Identity Card
        val realModel = android.os.Build.MODEL
        val realManufacturer = android.os.Build.MANUFACTURER
        binding.deviceIdentityReal.text = getString(R.string.su_bridge_device_identity_real, "$realManufacturer $realModel")

        if (ShizukuSettings.isSpoofDeviceEnabled()) {
            var target = ShizukuSettings.getSpoofTarget()

            if (target == "auto") {
                val model = android.os.Build.MODEL
                val manuf = android.os.Build.MANUFACTURER
                target = "$manuf $model"
            }

            val targetFriendly = when (target) {
                "pixel_9_pro_xl" -> "Pixel 9 Pro XL"
                "pixel_8_pro" -> "Pixel 8 Pro"
                "s24_ultra" -> "Galaxy S24 Ultra"
                "s23_ultra" -> "Galaxy S23 Ultra"
                "s22_ultra" -> "Galaxy S22 Ultra"
                "oneplus_12" -> "OnePlus 12"
                "nothing_phone_2" -> "Nothing Phone (2)"
                else -> target
            }
            binding.deviceIdentitySpoofed.text = getString(R.string.su_bridge_device_identity_spoofed, targetFriendly)
            binding.deviceIdentitySpoofed.setTextColor(MaterialColors.getColor(this, R.attr.colorPrimary, Color.BLUE))
        } else {
            binding.deviceIdentitySpoofed.text = getString(R.string.su_bridge_device_identity_spoofed, getString(R.string.su_bridge_device_identity_none))
            binding.deviceIdentitySpoofed.setTextColor(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant, Color.GRAY))
        }

        recyclerView = binding.suggestedAppsList
        recyclerView.clipToPadding = false
        ViewCompat.setOnApplyWindowInsetsListener(recyclerView) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(bars.left, view.paddingTop, bars.right, bars.bottom)
            insets
        }
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CategorizedSuggestedAppsAdapter(emptyList())
        recyclerView.adapter = adapter
        refreshList()

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }


    override fun onDestroy() {
        unregisterReceiver(packageReceiver)
        super.onDestroy()
    }

    sealed class ListItem {
        data class Header(val title: String) : ListItem()
        data class App(val packageName: String) : ListItem()
    }

    private fun buildItems(): List<ListItem> {
        val items = mutableListOf<ListItem>()

        AppContextManager.getRootLegacyPackages().forEach { (category, packages) ->
            items.add(ListItem.Header(category))
            packages.forEach { items.add(ListItem.App(it)) }
        }

        val pm = packageManager
        val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val knownPkgs = AppContextManager.getRootLegacyPackages().values.flatten().toSet()

        val detected = installed
            .filter { pkg ->
                pkg.packageName != packageName &&
                !knownPkgs.contains(pkg.packageName) &&
                pkg.requestedPermissions?.any {
                    it.contains("ROOT", true) || it.contains("SUPERUSER", true)
                } == true
            }
            .map { it.packageName }

        if (detected.isNotEmpty()) {
            items.add(ListItem.Header(getString(R.string.su_bridge_other_detected_apps)))
            detected.forEach { items.add(ListItem.App(it)) }
        }

        return items
    }

    private fun refreshList() {
        // getInstalledPackages(GET_PERMISSIONS) enumerates every app's permission set; on devices
        // with many apps that's a multi-hundred-ms main-thread stall (ANR risk). Build off-thread.
        lifecycleScope.launch {
            val items = withContext(Dispatchers.IO) { buildItems() }
            if (isFinishing || isDestroyed) return@launch
            adapter.updateItems(items)
        }
    }


    private fun resolveSuPath(): String? {
        return af.shizuku.manager.utils.EnvironmentUtils.resolveExportedPath("su")
    }

    private fun isPackageInstalled(pkg: String): Boolean =
        try {
            packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("su path", text))
        Toast.makeText(this, R.string.su_bridge_path_copied, Toast.LENGTH_SHORT).show()
    }

    private fun launchOrStore(pkg: String) {
        val pm = packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_root_compatibility, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                finish()
                return true
            }
            R.id.action_self_test -> {
                runSelfTest()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }

    /** Runs the SU Bridge self-test off the main thread and shows the result in a dialog. */
    private fun runSelfTest() {
        Toast.makeText(this, R.string.su_bridge_self_test_running, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val result = RootCompatHelper.selfTest(this@RootCompatibilityActivity)
            if (isFinishing) return@launch
            val heading = if (result.ok) getString(R.string.su_bridge_self_test_ok)
                          else getString(R.string.su_bridge_self_test_fail)
            MaterialAlertDialogBuilder(this@RootCompatibilityActivity)
                .setTitle(getString(R.string.su_bridge_self_test_title))
                .setMessage("$heading\n\n${result.report}")
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.su_bridge_self_test_copy) { _, _ ->
                    val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("SU Bridge self-test", result.report))
                    Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_self_test_copied, Toast.LENGTH_SHORT).show()
                }
                .show()
        }
    }

    private inner class CategorizedSuggestedAppsAdapter(items: List<ListItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = items.toMutableList()
        private var lastAnimatedPosition = -1

        fun updateItems(newItems: List<ListItem>) {
            items.clear()
            items.addAll(newItems)
            lastAnimatedPosition = -1
            notifyDataSetChanged()
        }

        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        override fun getItemViewType(position: Int): Int =
            if (items[position] is ListItem.Header) TYPE_HEADER else TYPE_APP

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(ListSectionHeaderBinding.inflate(inflater, parent, false))
            } else {
                AppViewHolder(AppListItemBinding.inflate(inflater, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]

            // Resting alpha: suggested apps that aren't installed are dimmed as a "not installed"
            // cue. Compute it up front so the entrance animation lands on it — animating to a fixed
            // 1f would clobber the dim (uninstalled apps rendered fully opaque).
            val restAlpha = if (item is ListItem.App && !isPackageInstalled(item.packageName)) 0.5f else 1f

            // M3E Expressive Animation: Scale and Fade Entrance. Only animate the first time each
            // position scrolls into view — replaying on every bind makes items flash while scrolling.
            holder.itemView.animate().cancel()
            if (position > lastAnimatedPosition) {
                lastAnimatedPosition = position
                holder.itemView.alpha = 0f
                holder.itemView.scaleX = 0.96f
                holder.itemView.scaleY = 0.96f
                holder.itemView.animate()
                    .alpha(restAlpha)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(ShizukuSettings.scaledAnimationDuration(500))
                    .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                    .start()
            } else {
                holder.itemView.alpha = restAlpha
                holder.itemView.scaleX = 1f
                holder.itemView.scaleY = 1f
            }

            if (holder is HeaderViewHolder && item is ListItem.Header) {
                holder.binding.title.text = item.title
            } else if (holder is AppViewHolder && item is ListItem.App) {
                val pkg = item.packageName
                val pm = packageManager
                val metadata = AppContextManager.getMetadata(pkg)

                holder.binding.summary.text = pkg
                holder.binding.appContext.text = metadata?.description ?: ""
                holder.binding.appContext.visibility = if (holder.binding.appContext.text.isNullOrEmpty()) View.GONE else View.VISIBLE

                // Root support badge: color and text vary by support level
                when (metadata?.rootSupportLevel) {
                    RootSupportLevel.ROOT_REQUIRED -> {
                        holder.binding.requiresRoot.visibility = View.VISIBLE
                        holder.binding.requiresRoot.setText(R.string.app_management_item_summary_requires_root)
                        holder.binding.requiresRoot.setTextColor(
                            MaterialColors.getColor(holder.itemView, R.attr.colorError))
                    }
                    RootSupportLevel.PARTIAL -> {
                        holder.binding.requiresRoot.visibility = View.VISIBLE
                        holder.binding.requiresRoot.setText(R.string.app_management_item_summary_partial_root)
                        holder.binding.requiresRoot.setTextColor(
                            MaterialColors.getColor(holder.itemView, R.attr.colorTertiary))
                    }
                    else -> holder.binding.requiresRoot.visibility = View.GONE
                }
                // "Requires Plus" badge: shown when app has Plus enhancements that benefit it
                holder.binding.requiresPlus.visibility = if (metadata != null && metadata.potentialEnhancements.isNotEmpty()) View.VISIBLE else View.GONE

                // "Shizuku-aware" badge: shown for apps that support Shizuku natively
                holder.binding.shizukuAware.visibility = if (metadata?.supportsShizukuNatively == true) View.VISIBLE else View.GONE

                holder.binding.switchWidget.visibility = View.GONE
                holder.binding.checkbox.visibility = View.GONE

                val isShizukuNative = metadata?.supportsShizukuNatively == true
                val navHint = metadata?.suPathSettingNav ?: this@RootCompatibilityActivity.getString(R.string.su_bridge_default_nav_hint)
                holder.binding.suPathNav.text = navHint
                holder.binding.suPathNav.visibility = View.VISIBLE

                // SU Bridge actions are irrelevant for Shizuku-native apps — they handle their
                // own auth flow and have no SU path to copy or configure.
                holder.binding.suCopyOpen.isVisible = !isShizukuNative

                holder.binding.suCopyOpen.setContent {
                    af.shizuku.core.ui.compose.Button(
                        onClick = {
                            val path = resolvedSuPath
                            if (path != null) {
                                copyToClipboard(path)
                                launchOrStore(pkg)
                            } else {
                                Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_no_export, Toast.LENGTH_SHORT).show()
                            }
                        }
                    ) {
                        androidx.compose.material3.Text(this@RootCompatibilityActivity.getString(R.string.su_bridge_copy_open))
                    }
                }

                // Automation: Magic Setup for supported apps
                var isInstalled = false
                try {
                    pm.getPackageInfo(pkg, 0)
                    isInstalled = true
                } catch (_: PackageManager.NameNotFoundException) {
                    // expected for suggested-but-not-installed apps
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to check if package $pkg is installed")
                }

                // Magic Setup is only meaningful when we know how to configure this specific app.
                // canAutoSetup() is the single source of truth: GLOBAL_SETTINGS_APPS in any mode,
                // ROOT_PREFS_APPS only when running as root (UID 0).
                val canMagicSetup = RootCompatHelper.canAutoSetup(pkg, isRoot)
                holder.binding.suMagicSetup.isVisible = isInstalled && !isShizukuNative
                if (isInstalled) {
                    holder.binding.suMagicSetup.alpha = if (canMagicSetup) 1.0f else 0.5f

                    holder.binding.suMagicSetup.setContent {
                        af.shizuku.core.ui.compose.Button(
                            enabled = canMagicSetup,
                            onClick = {
                                val path = resolvedSuPath
                                if (path == null) {
                                    Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_no_export, Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                // Capture app name before the coroutine — holder may be recycled
                                // by the time the IPC completes, making title.text stale.
                                val appName = holder.binding.title.text?.toString() ?: pkg
                                lifecycleScope.launch {
                                    val success = RootCompatHelper.autoSetup(this@RootCompatibilityActivity, pkg, path)
                                    if (success) {
                                        Toast.makeText(this@RootCompatibilityActivity, this@RootCompatibilityActivity.getString(R.string.su_bridge_magic_setup_success, appName), Toast.LENGTH_LONG).show()
                                        launchOrStore(pkg)
                                    } else {
                                        Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_magic_setup_fail, Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        ) {
                            androidx.compose.material3.Text(this@RootCompatibilityActivity.getString(R.string.su_bridge_magic_setup))
                        }
                    }
                }

                // Load App Info
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    holder.binding.title.text = info.loadLabel(pm)
                    holder.binding.icon.load(info.loadIcon(pm)) {
                        crossfade(true)
                    }

                    if (metadata == null) {
                        holder.binding.appContext.text = getString(R.string.su_bridge_installed_root_app)
                        holder.binding.appContext.visibility = View.VISIBLE
                    }

                    holder.itemView.setOnClickListener {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            startActivity(intent)
                        } else {
                            try {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                            } catch (e: Exception) {
                                Timber.tag(TAG).w(e, "start application details settings failed")
                            }
                        }
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    holder.binding.title.text = pkg.split(".").last().replaceFirstChar { it.uppercase() }
                    holder.binding.icon.load(R.drawable.ic_system_icon) {
                        crossfade(true)
                    }

                    if (metadata == null) {
                        holder.binding.appContext.text = getString(R.string.su_bridge_suggested_root_app)
                        holder.binding.appContext.visibility = View.VISIBLE
                    }

                    holder.itemView.setOnClickListener {
                        val url = when (pkg) {
                            "dev.ukanth.ufirewall" -> "https://f-droid.org/packages/dev.ukanth.ufirewall/"
                            "com.machiav3lli.neo_backup" -> "https://f-droid.org/packages/com.machiav3lli.neo_backup/"
                            "samolego.canta" -> "https://f-droid.org/packages/samolego.canta/"
                            "com.aistra.hail" -> "https://f-droid.org/packages/com.aistra.hail/"
                            "thejaustin.afdroid" -> "https://github.com/thejaustin/afdroid/releases"
                            "thejaustin.hexodus" -> "https://github.com/thejaustin/Hexodus/releases"
                            else -> "https://play.google.com/store/apps/details?id=$pkg"
                        }
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (ex: Exception) {
                            Timber.tag(TAG).d(ex, "Primary URL intent failed for $pkg, falling back to Play Store")
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                            } catch (e2: Exception) {
                                Timber.tag(TAG).w(e2, "start view intent failed for $pkg")
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private class HeaderViewHolder(val binding: ListSectionHeaderBinding) : RecyclerView.ViewHolder(binding.root)

    private class AppViewHolder(val binding: AppListItemBinding) : RecyclerView.ViewHolder(binding.root)
}
