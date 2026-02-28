package moe.shizuku.manager.settings

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.ActivityLogItemBinding
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.ActivityLogManager
import moe.shizuku.manager.utils.ActivityLogRecord
import rikka.recyclerview.BaseViewHolder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ActivityLogActivity : AppBarActivity() {

    private val adapter = LogAdapter()
    private var emptyView: TextView? = null

    override fun getLayoutId() = R.layout.apps_appbar_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val binding = AppsActivityBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.settings_activity_log)
        
        // Hide search/filter
        findViewById<View>(R.id.search_layout).visibility = View.GONE
        findViewById<View>(R.id.filter_chip_group).parent.let { 
            if (it is View) it.visibility = View.GONE 
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.list) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, systemBars.bottom)
            insets
        }

        binding.list.adapter = adapter
        val records = ActivityLogManager.getRecords()
        adapter.update(records)

        if (records.isEmpty()) {
            emptyView = TextView(this).apply {
                text = getString(R.string.settings_activity_log_empty)
                gravity = Gravity.CENTER
                androidx.core.widget.TextViewCompat.setTextAppearance(this, com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            }
            (binding.list.parent as? ViewGroup)?.addView(emptyView)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, android.R.string.cancel).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            setIcon(R.drawable.ic_close_24)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        } else if (item.itemId == 1) {
            ActivityLogManager.clear()
            adapter.update(emptyList())
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    private class LogAdapter : RecyclerView.Adapter<LogViewHolder>() {
        private var items = emptyList<ActivityLogRecord>()

        fun update(newItems: List<ActivityLogRecord>) {
            items = newItems
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = LogViewHolder.create(parent)
        override fun onBindViewHolder(holder: LogViewHolder, position: Int) = holder.bind(items[position])
    }

    private class LogViewHolder(private val binding: ActivityLogItemBinding) : RecyclerView.ViewHolder(binding.root) {
        companion object {
            private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
            fun create(parent: ViewGroup) = LogViewHolder(ActivityLogItemBinding.inflate(LayoutInflater.from(parent.context), parent, false))
        }

        fun bind(record: ActivityLogRecord) {
            binding.appName.text = record.appName
            binding.packageName.text = record.packageName
            binding.action.text = record.action
            binding.timestamp.text = dateFormat.format(Date(record.timestamp))
        }
    }
}
