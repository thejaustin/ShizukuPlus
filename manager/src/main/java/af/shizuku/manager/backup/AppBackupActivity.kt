package af.shizuku.manager.backup

import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.snackbar.Snackbar
import af.shizuku.core.ui.AppBarActivity
import af.shizuku.manager.R
import af.shizuku.manager.databinding.ActivityAppBackupBinding
import kotlinx.coroutines.launch

class AppBackupActivity : AppBarActivity() {

    private val viewModel: BackupViewModel by viewModels()
    private lateinit var binding: ActivityAppBackupBinding
    private lateinit var adapter: BackupAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityAppBackupBinding.inflate(layoutInflater, rootView, false)
        setContentView(binding.root)

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            title = getString(R.string.home_backup_title)
        }

        adapter = BackupAdapter()
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter

        adapter.onBackupClick = { entry ->
            val outputDir = getExternalFilesDir(null) ?: filesDir
            viewModel.backupAppData(entry, outputDir)
        }
        adapter.onFreezeClick = { entry ->
            viewModel.toggleFreeze(entry)
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    when (state) {
                        is BackupViewModel.UiState.Loading -> showLoading()
                        is BackupViewModel.UiState.Loaded -> {
                            showContent()
                            adapter.submitList(state.apps)
                        }
                        is BackupViewModel.UiState.Error -> showError(state.msg)
                        is BackupViewModel.UiState.ServiceNotRunning -> showError(
                            getString(R.string.home_status_service_not_running, getString(R.string.app_name))
                        )
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.busyPackages.collect { busy ->
                    adapter.setBusy(busy)
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is BackupViewModel.BackupEvent.BackupComplete ->
                            Snackbar.make(
                                rootView,
                                getString(R.string.backup_app_complete, event.pkg, event.path),
                                Snackbar.LENGTH_LONG
                            ).show()
                        is BackupViewModel.BackupEvent.FreezeChanged -> {
                            val msg = if (event.nowFrozen) R.string.backup_freeze_success else R.string.backup_unfreeze_success
                            Snackbar.make(rootView, msg, Snackbar.LENGTH_SHORT).show()
                        }
                        is BackupViewModel.BackupEvent.Failure ->
                            Snackbar.make(rootView, event.msg, Snackbar.LENGTH_LONG).show()
                    }
                }
            }
        }

        viewModel.loadApps()
    }

    private fun showLoading() {
        binding.progressBar.visibility = View.VISIBLE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.GONE
    }

    private fun showContent() {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.VISIBLE
        binding.errorText.visibility = View.GONE
    }

    private fun showError(msg: String) {
        binding.progressBar.visibility = View.GONE
        binding.recyclerView.visibility = View.GONE
        binding.errorText.visibility = View.VISIBLE
        binding.errorText.text = msg
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
