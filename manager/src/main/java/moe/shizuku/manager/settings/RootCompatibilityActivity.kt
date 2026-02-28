package moe.shizuku.manager.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.utils.AppContextManager

class RootCompatibilityActivity : AppBarActivity() {

    override fun getLayoutId() = R.layout.activity_root_compatibility

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val scrollView = findViewById<View>(R.id.suggested_apps_list)?.parent?.parent as? View ?: rootView
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        val recyclerView = findViewById<RecyclerView>(R.id.suggested_apps_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        
        val items = mutableListOf<Any>()
        val categories = AppContextManager.getRootLegacyPackages()
        categories.forEach { (category, packages) ->
            items.add(category) // Header
            items.addAll(packages) // App package names
        }
        
        recyclerView.adapter = CategorizedSuggestedAppsAdapter(items)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class CategorizedSuggestedAppsAdapter(val items: List<Any>) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        
        private val TYPE_HEADER = 0
        private val TYPE_APP = 1

        override fun getItemViewType(position: Int): Int {
            return if (items[position] is String && items[position].toString().contains(".")) TYPE_APP else if (items[position] is String) TYPE_HEADER else TYPE_APP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_HEADER) {
                HeaderViewHolder(inflater.inflate(R.layout.preference_category_material, parent, false))
            } else {
                AppViewHolder(inflater.inflate(R.layout.app_list_item, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            if (holder is HeaderViewHolder) {
                holder.title.text = item as String
            } else if (holder is AppViewHolder) {
                val pkg = item as String
                val pm = packageManager
                val metadata = AppContextManager.getMetadata(pkg)
                
                holder.packageName.text = pkg
                holder.description.text = metadata?.description ?: ""
                holder.description.visibility = View.VISIBLE
                
                holder.itemView.findViewById<View>(R.id.switch_widget).visibility = View.GONE
                holder.itemView.findViewById<View>(R.id.checkbox).visibility = View.GONE

                try {
                    val info = pm.getApplicationInfo(pkg, 0)
                    holder.appName.text = info.loadLabel(pm)
                    holder.icon.setImageDrawable(info.loadIcon(pm))
                    holder.itemView.alpha = 1.0f
                } catch (e: PackageManager.NameNotFoundException) {
                    holder.appName.text = pkg.split(".").last().replaceFirstChar { it.uppercase() }
                    holder.icon.setImageResource(R.drawable.ic_system_icon)
                    holder.itemView.alpha = 0.5f
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
    }
}
