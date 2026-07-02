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
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.Settings
import android.view.LayoutInflater
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
import kotlinx.coroutines.launch
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.databinding.ActivityRootCompatibilityBinding
import af.shizuku.manager.databinding.AppListItemBinding
import af.shizuku.manager.databinding.ListSectionHeaderBinding
import timber.log.Timber
import af.shizuku.manager.database.AppContextManager
import af.shizuku.manager.database.RootCompatHelper
import rikka.shizuku.Shizuku
import af.shizuku.manager.database.RootSupportLevel
import io.sentry.Sentry

class RootCompatibilityActivity : AppBarActivity() {

    companion object {
        private const val TAG = "RootCompatibilityAct"
    }

    private var resolvedSuPath: String? = null
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

        resolvedSuPath = resolveSuPath()

        resolvedSuPath?.let { path ->
            val isRoot = try { Shizuku.pingBinder() && Shizuku.getUid() == 0 } catch (e: Exception) { false }

            // Only show the automated setup card if we are rooted.
            // For ADB users, this card is irrelevant and confusing.
            binding.globalSetupCard.isVisible = isRoot

            binding.globalSuPath.text = path
            binding.btnCopyGlobal.setContent {
                af.shizuku.core.ui.compose.Button(
                    onClick = { copyToClipboard(path) }
                ) {
                    androidx.compose.material3.Text(getString(R.string.su_bridge_copy_path))
                }
            }

            if (isRoot) {
                binding.btnSetupAll.setContent {
                    af.shizuku.core.ui.compose.Button(
                        onClick = {
                            lifecycleScope.launch {
                                val count = RootCompatHelper.autoSetupAll(this@RootCompatibilityActivity, path)
                                if (count > 0) {
                                    Toast.makeText(this@RootCompatibilityActivity, getString(R.string.su_bridge_magic_setup_all_summary, count), Toast.LENGTH_LONG).show()
                                } else {
                                    Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_magic_setup_all_no_apps, Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                    ) {
                        androidx.compose.material3.Text(getString(R.string.su_bridge_setup_all))
                    }
                }
            }
        } ?: run {
            binding.globalSetupCard.isVisible = false
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

        val scrollView = binding.suggestedAppsList.parent?.parent as? View ?: rootView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        recyclerView = binding.suggestedAppsList
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = CategorizedSuggestedAppsAdapter(buildItems())
        recyclerView.adapter = adapter

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
        adapter.updateItems(buildItems())
    }


    private fun resolveSuPath(): String? {
        return af.shizuku.manager.utils.EnvironmentUtils.resolveExportedPath("su")
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

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class CategorizedSuggestedAppsAdapter(items: List<ListItem>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = items.toMutableList()

        fun updateItems(newItems: List<ListItem>) {
            items.clear()
            items.addAll(newItems)
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

            // M3E Expressive Animation: Scale and Fade Entrance for items
            holder.itemView.alpha = 0f
            holder.itemView.scaleX = 0.96f
            holder.itemView.scaleY = 0.96f
            holder.itemView.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(500)
                .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
                .start()

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

                val navHint = metadata?.suPathSettingNav ?: this@RootCompatibilityActivity.getString(R.string.su_bridge_default_nav_hint)
                holder.binding.suPathNav.text = navHint
                holder.binding.suPathNav.visibility = View.VISIBLE

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
                } catch (e: Exception) {
                    Timber.tag(TAG).w(e, "Failed to check if package $pkg is installed")
                    if (e !is PackageManager.NameNotFoundException) {
                        Sentry.captureException(e)
                    }
                }

                holder.binding.suMagicSetup.isVisible = isInstalled
                if (isInstalled) {
                    val isRoot = try { Shizuku.pingBinder() && Shizuku.getUid() == 0 } catch (e: Exception) { false }
                    holder.binding.suMagicSetup.alpha = if (isRoot) 1.0f else 0.5f

                    holder.binding.suMagicSetup.setContent {
                        af.shizuku.core.ui.compose.Button(
                            enabled = isRoot,
                            onClick = {
                                val path = resolvedSuPath
                                if (path == null) {
                                    Toast.makeText(this@RootCompatibilityActivity, R.string.su_bridge_no_export, Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                lifecycleScope.launch {
                                    val success = RootCompatHelper.autoSetup(this@RootCompatibilityActivity, pkg, path)
                                    if (success) {
                                        Toast.makeText(this@RootCompatibilityActivity, this@RootCompatibilityActivity.getString(R.string.su_bridge_magic_setup_success, holder.binding.title.text), Toast.LENGTH_LONG).show()
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
                    holder.itemView.alpha = 1.0f

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
                                timber.log.Timber.e(e, "start application details settings failed")
                                Sentry.captureException(e)
                            }
                        }
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    holder.binding.title.text = pkg.split(".").last().replaceFirstChar { it.uppercase() }
                    holder.binding.icon.load(R.drawable.ic_system_icon) {
                        crossfade(true)
                    }
                    holder.itemView.alpha = 0.5f

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
                                Timber.e("start view intent failed", e2)
                                Sentry.captureException(e2)
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
