package moe.shizuku.manager.settings

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
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

        val recyclerView = findViewById<RecyclerView>(R.id.suggested_apps_list)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = SuggestedAppsAdapter(AppContextManager.getRootLegacyPackages())
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private inner class SuggestedAppsAdapter(val packages: List<String>) : RecyclerView.Adapter<AppViewHolder>() {
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.app_list_item, parent, false)
            return AppViewHolder(view)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            val pkg = packages[position]
            val pm = packageManager
            val metadata = AppContextManager.getMetadata(pkg)
            
            holder.packageName.text = pkg
            holder.description.text = metadata?.description ?: ""
            holder.description.visibility = View.VISIBLE
            
            // Hide Shizuku-specific widgets for this list
            holder.itemView.findViewById<View>(R.id.switch_widget).visibility = View.GONE
            holder.itemView.findViewById<View>(R.id.checkbox).visibility = View.GONE

            try {
                val info = pm.getApplicationInfo(pkg, 0)
                holder.appName.text = info.loadLabel(pm)
                holder.icon.setImageDrawable(info.loadIcon(pm))
                holder.itemView.alpha = 1.0f
            } catch (e: PackageManager.NameNotFoundException) {
                holder.appName.text = pkg.split(".").last().capitalize()
                holder.icon.setImageResource(R.drawable.ic_system_icon)
                holder.itemView.alpha = 0.5f
            }
        }

        override fun getItemCount() = packages.size
    }

    private class AppViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val icon: ImageView = view.findViewById(android.R.id.icon)
        val appName: TextView = view.findViewById(android.R.id.title)
        val packageName: TextView = view.findViewById(android.R.id.summary)
        val description: TextView = view.findViewById(R.id.app_context)
    }
}
