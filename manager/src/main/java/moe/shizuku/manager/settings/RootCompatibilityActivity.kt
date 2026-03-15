package moe.shizuku.manager.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
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
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors
import moe.shizuku.manager.utils.RootSupportLevel
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import moe.shizuku.manager.R
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.utils.AppContextManager

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import moe.shizuku.manager.utils.RootCompatHelper

class RootCompatibilityActivity : AppBarActivity() {

    override fun getLayoutId() = R.layout.activity_root_compatibility

    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: CategorizedSuggestedAppsAdapter
    private var resolvedSuPath: String? = null

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            refreshList()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        resolvedSuPath = resolveSuPath()

        val globalSetupCard = findViewById<MaterialCardView>(R.id.global_setup_card)
        val globalSuPath = findViewById<TextView>(R.id.global_su_path)
        val btnCopyGlobal = findViewById<MaterialButton>(R.id.btn_copy_global)
        val btnSetupAll = findViewById<MaterialButton>(R.id.btn_setup_all)

        if (resolvedSuPath != null) {
            globalSetupCard.isVisible = true
            globalSuPath.text = resolvedSuPath
            btnCopyGlobal.setOnClickListener { copyToClipboard(resolvedSuPath!!) }
            
            btnSetupAll.setOnClickListener {
                lifecycleScope.launch {
                    val count = RootCompatHelper.autoSetupAll(this@RootCompatibilityActivity, resolvedSuPath!!)
                    if (count > 0) {
                        Toast.makeText(this@RootCompatibilityActivity, getString(R.string.root_hub_magic_setup_all_summary, count), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@RootCompatibilityActivity, R.string.root_hub_magic_setup_all_no_apps, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            globalSetupCard.isVisible = false
        }

        // Device Identity Card
        val realDeviceText = findViewById<TextView>(R.id.device_identity_real)
        val spoofedDeviceText = findViewById<TextView>(R.id.device_identity_spoofed)
        
        val realModel = android.os.Build.MODEL
        val realManufacturer = android.os.Build.MANUFACTURER
        realDeviceText.text = getString(R.string.root_hub_device_identity_real, "$realManufacturer $realModel")
        
        if (ShizukuSettings.isSpoofDeviceEnabled()) {
            val target = ShizukuSettings.getSpoofTarget()
            val targetFriendly = when (target) {
                "pixel_9_pro_xl" -> "Pixel 9 Pro XL"
                "pixel_8_pro" -> "Pixel 8 Pro"
                "s24_ultra" -> "Galaxy S24 Ultra"
                "s23_ultra" -> "Galaxy S23 Ultra"
                "oneplus_12" -> "OnePlus 12"
                "nothing_phone_2" -> "Nothing Phone (2)"
                else -> target
            }
            spoofedDeviceText.text = getString(R.string.root_hub_device_identity_spoofed, targetFriendly)
            spoofedDeviceText.setTextColor(MaterialColors.getColor(this, R.attr.colorPrimary, Color.BLUE))
        } else {
            spoofedDeviceText.text = getString(R.string.root_hub_device_identity_spoofed, getString(R.string.root_hub_device_identity_none))
            spoofedDeviceText.setTextColor(MaterialColors.getColor(this, R.attr.colorOnSurfaceVariant, Color.GRAY))
        }

        val scrollView = findViewById<View>(R.id.suggested_apps_list)?.parent?.parent as? View ?: rootView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        recyclerView = findViewById(R.id.suggested_apps_list)
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

    private fun buildItems(): List<Any> {
        val items = mutableListOf<Any>()
        
        // 1. Official categories from AppContextManager
        AppContextManager.getRootLegacyPackages().forEach { (category, packages) ->
            items.add(category)
            items.addAll(packages)
        }
        
        // 2. Scan for other potential root apps installed on the device
        val detected = mutableListOf<String>()
        val pm = packageManager
        val installed = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val knownPkgs = AppContextManager.getRootLegacyPackages().values.flatten().toSet()
        
        for (pkgInfo in installed) {
            val pkg = pkgInfo.packageName
            if (pkg == packageName || knownPkgs.contains(pkg)) continue
            
            val usesRoot = pkgInfo.requestedPermissions?.any { 
                it.contains("ROOT", true) || it.contains("SUPERUSER", true)
            } == true
            
            if (usesRoot) {
                detected.add(pkg)
            }
        }
        
        if (detected.isNotEmpty()) {
            items.add("Other Detected Root Apps")
            items.addAll(detected)
        }
        
        return items
    }

    private fun refreshList() {
        adapter.updateItems(buildItems())
    }

    private fun resolveSuPath(): String? {
        val uriStr = ShizukuSettings.getExportDirUri() ?: return null
        return try {
            val uri = Uri.parse(uriStr)
            val docId = DocumentsContract.getTreeDocumentId(uri)
            
            // Check for common volume patterns
            when {
                docId.startsWith("primary:") -> {
                    val relative = docId.removePrefix("primary:")
                    if (relative.isEmpty()) "/storage/emulated/0/su"
                    else "/storage/emulated/0/$relative/su"
                }
                docId.contains(":") -> {
                    // Try to resolve secondary SD cards if possible (best-effort)
                    val parts = docId.split(":")
                    val volumeId = parts[0]
                    val relative = parts[1]
                    "/storage/$volumeId/$relative/su"
                }
                // Handle direct directory names if primary prefix is missing
                docId.startsWith("Download") || docId.startsWith("Documents") || docId.startsWith("Movies") -> {
                    "/storage/emulated/0/$docId/su"
                }
                else -> {
                    // Fallback to internal app storage if it looks like a relative path
                    if (!docId.startsWith("/")) "/storage/emulated/0/$docId/su"
                    else null
                }
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("su path", text))
        Toast.makeText(this, R.string.root_hub_path_copied, Toast.LENGTH_SHORT).show()
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

    private inner class CategorizedSuggestedAppsAdapter(items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        private val items = items.toMutableList()

        fun updateItems(newItems: List<Any>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }
        
        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String && items[position].toString().contains(".")) TYPE_APP else if (items[position] is String) TYPE_HEADER else TYPE_APP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.list_section_header, parent, false))
            } else {
                AppViewHolder(inflater.inflate(R.layout.app_list_item, parent, false))
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

            if (holder is HeaderViewHolder) {
                holder.title.text = item as String
            } else if (holder is AppViewHolder) {
                val pkg = item as String
                val pm = packageManager
                val metadata = AppContextManager.getMetadata(pkg)

                holder.packageName.text = pkg
                holder.description.text = metadata?.description ?: ""
                holder.description.visibility = if (holder.description.text.isNullOrEmpty()) View.GONE else View.VISIBLE
                
                // Root support badge: color and text vary by support level
                when (metadata?.rootSupportLevel) {
                    RootSupportLevel.ROOT_REQUIRED -> {
                        holder.requiresRoot.visibility = View.VISIBLE
                        holder.requiresRoot.setText(R.string.app_management_item_summary_requires_root)
                        holder.requiresRoot.setTextColor(
                            MaterialColors.getColor(holder.itemView, R.attr.colorError))
                    }
                    RootSupportLevel.PARTIAL -> {
                        holder.requiresRoot.visibility = View.VISIBLE
                        holder.requiresRoot.setText(R.string.app_management_item_summary_partial_root)
                        holder.requiresRoot.setTextColor(
                            MaterialColors.getColor(holder.itemView, R.attr.colorTertiary))
                    }
                    else -> holder.requiresRoot.visibility = View.GONE
                }
                // "Requires Plus" badge: shown when app has Plus enhancements that benefit it
                holder.requiresPlus.visibility = if (metadata != null && metadata.potentialEnhancements.isNotEmpty()) View.VISIBLE else View.GONE

                holder.itemView.findViewById<View>(R.id.switch_widget).visibility = View.GONE
                holder.itemView.findViewById<View>(R.id.checkbox).visibility = View.GONE

                val navHint = metadata?.suPathSettingNav ?: this@RootCompatibilityActivity.getString(R.string.root_hub_default_nav_hint)
                holder.suPathNav.text = navHint
                holder.suPathNav.visibility = View.VISIBLE
                
                holder.suCopyOpen.visibility = View.VISIBLE
                holder.suCopyOpen.setOnClickListener {
                    val path = resolvedSuPath
                    if (path != null) {
                        copyToClipboard(path)
                        launchOrStore(pkg)
                    } else {
                        Toast.makeText(this@RootCompatibilityActivity, R.string.root_hub_no_export, Toast.LENGTH_SHORT).show()
                    }
                }

                // Automation: Magic Setup for supported apps
                var isInstalled = false
                try {
                    pm.getPackageInfo(pkg, 0)
                    isInstalled = true
                } catch (ignored: Exception) {}

                holder.suMagicSetup.isVisible = isInstalled
                if (isInstalled) {
                    holder.suMagicSetup.setOnClickListener {
                        val path = resolvedSuPath
                        if (path == null) {
                            Toast.makeText(this@RootCompatibilityActivity, R.string.root_hub_no_export, Toast.LENGTH_SHORT).show()
                            return@setOnClickListener
                        }
                        lifecycleScope.launch {
                            val success = RootCompatHelper.autoSetup(this@RootCompatibilityActivity, pkg, path)
                            if (success) {
                                Toast.makeText(this@RootCompatibilityActivity, this@RootCompatibilityActivity.getString(R.string.root_hub_magic_setup_success, holder.appName.text), Toast.LENGTH_LONG).show()
                                launchOrStore(pkg)
                            } else {
                                Toast.makeText(this@RootCompatibilityActivity, R.string.root_hub_magic_setup_fail, Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                }
                
                // Load App Info
                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    holder.appName.text = info.loadLabel(pm)
                    holder.icon.setImageDrawable(info.loadIcon(pm))
                    holder.itemView.alpha = 1.0f
                    
                    if (metadata == null) {
                        holder.description.text = "Installed Root App"
                        holder.description.visibility = View.VISIBLE
                    }

                    holder.itemView.setOnClickListener {
                        val intent = pm.getLaunchIntentForPackage(pkg)
                        if (intent != null) {
                            startActivity(intent)
                        } else {
                            try {
                                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                } catch (e: PackageManager.NameNotFoundException) {
                    holder.appName.text = pkg.split(".").last().replaceFirstChar { it.uppercase() }
                    holder.icon.setImageResource(R.drawable.ic_system_icon)
                    holder.itemView.alpha = 0.5f
                    
                    if (metadata == null) {
                        holder.description.text = "Suggested Root App"
                        holder.description.visibility = View.VISIBLE
                    }

                    holder.itemView.setOnClickListener {
                        val url = when (pkg) {
                            "org.adaway" -> "https://f-droid.org/packages/org.adaway/"
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
                            try {
                                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
                            } catch (e2: Exception) {
                                e2.printStackTrace()
                            }
                        }
                    }
                }
            }
        }

        override fun getItemCount() = items.size
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val title: TextView = view.findViewById(android.R.id.title)
    }

    private class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(android.R.id.icon)
        val appName: TextView = view.findViewById(android.R.id.title)
        val packageName: TextView = view.findViewById(android.R.id.summary)
        val description: TextView = view.findViewById(R.id.app_context)
        val requiresRoot: TextView = view.findViewById(R.id.requires_root)
        val requiresPlus: TextView = view.findViewById(R.id.requires_plus)
        val suPathNav: TextView = view.findViewById(R.id.su_path_nav)
        val suCopyOpen: MaterialButton = view.findViewById(R.id.su_copy_open)
        val suMagicSetup: MaterialButton = view.findViewById(R.id.su_magic_setup)
    }
}
