package moe.shizuku.manager.management

import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.activity.viewModels
import androidx.recyclerview.widget.RecyclerView
import moe.shizuku.manager.R
import moe.shizuku.manager.app.AppBarActivity
import moe.shizuku.manager.databinding.AppsActivityBinding
import moe.shizuku.manager.utils.ShizukuStateMachine
import rikka.lifecycle.Status
import java.util.Objects

class HiddenAppsActivity : AppBarActivity(), AppViewHolder.Callbacks {

    private val viewModel: AppsViewModel by viewModels()
    private val adapter = AppsAdapter()
    private lateinit var recyclerView: RecyclerView

    private val stateListener: (ShizukuStateMachine.State) -> Unit = {
        if (ShizukuStateMachine.isDead() && !isFinishing) finish()
    }

    override fun getLayoutId() = R.layout.apps_appbar_activity

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!ShizukuStateMachine.isRunning()) {
            finish()
            return
        }

        val binding = AppsActivityBinding.inflate(layoutInflater, rootView, true)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.setTitle(R.string.app_management_action_hidden_apps)

        // Hide search and filter for simplicity in hidden list
        findViewById<View>(R.id.search_layout).visibility = View.GONE
        findViewById<View>(R.id.filter_chip_group).parent.let { 
            if (it is View) it.visibility = View.GONE 
        }

        viewModel.getHiddenPackagesResource().observe(this) {
            when (it.status) {
                Status.SUCCESS -> {
                    adapter.updateData(it.data!!)
                }
                Status.ERROR -> {
                    Toast.makeText(this, Objects.toString(it.error, "unknown"), Toast.LENGTH_SHORT).show()
                }
                Status.LOADING -> {}
            }
        }
        viewModel.load()

        recyclerView = binding.list
        recyclerView.adapter = adapter

        ShizukuStateMachine.addListener(stateListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.app_management_undo).apply {
            setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        } else if (item.itemId == 1) { // Unhide all
            val hidden = viewModel.hiddenPackages.toSet()
            hidden.forEach { viewModel.unhidePackage(it) }
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onHideApp(packageName: String) {
        // In this activity, "hiding" an app actually UNHIDES it (toggling back to main list)
        viewModel.unhidePackage(packageName)
        viewModel.load() // Refresh hidden list
    }

    override fun onDestroy() {
        ShizukuStateMachine.removeListener(stateListener)
        super.onDestroy()
    }
}
