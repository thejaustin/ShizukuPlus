package moe.shizuku.manager.settings

import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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

        val suPathCard = findViewById<MaterialCardView>(R.id.su_path_card)
        val suPathText = findViewById<TextView>(R.id.su_path_text)
        val copyPathButton = findViewById<MaterialButton>(R.id.copy_path_button)
        val magicSetupAllButton = findViewById<MaterialButton>(R.id.magic_setup_all_button)

        if (resolvedSuPath != null) {
            suPathCard.isVisible = true
            suPathText.text = resolvedSuPath
            copyPathButton.setOnClickListener { copyToClipboard(resolvedSuPath!!) }
            
            magicSetupAllButton.setOnClickListener {
                lifecycleScope.launch {
                    val supported = listOf("org.adaway", "dev.ukanth.ufirewall")
                    var count = 0
                    supported.forEach { pkg ->
                        try {
                            packageManager.getPackageInfo(pkg, 0)
                            if (RootCompatHelper.autoSetup(this@RootCompatibilityActivity, pkg, resolvedSuPath!!)) {
                                count++
                            }
                        } catch (ignored: Exception) {}
                    }
                    if (count > 0) {
                        Toast.makeText(this@RootCompatibilityActivity, getString(R.string.root_hub_magic_setup_all_summary, count), Toast.LENGTH_LONG).show()
                    } else {
                        Toast.makeText(this@RootCompatibilityActivity, R.string.root_hub_magic_setup_all_no_apps, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        } else {
            suPathCard.isVisible = false
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
        AppContextManager.getRootLegacyPackages().forEach { (category, packages) ->
            items.add(category)
            items.addAll(packages)
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
                .setDuration(450)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()

            if (holder is HeaderViewHolder) {
                holder.title.text = item as String
            } else if (holder is AppViewHolder) {
                val pkg = item as String
                val pm = packageManager
                val metadata = AppContextManager.getMetadata(pkg)

                holder.packageName.text = pkg
                holder.description.text = metadata?.description ?: ""
                holder.description.visibility = View.VISIBLE
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
                val isMagicSupported = pkg in listOf("org.adaway", "dev.ukanth.ufirewall")
                holder.suMagicSetup.isVisible = isMagicSupported
                if (isMagicSupported) {
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

                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    holder.appName.text = info.loadLabel(pm)
                    holder.icon.setImageDrawable(info.loadIcon(pm))
                    holder.itemView.alpha = 1.0f
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
                    holder.itemView.setOnClickListener {
                        try {
                            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            })
                        } catch (e: Exception) {
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
