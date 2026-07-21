package af.shizuku.manager.home

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import af.shizuku.manager.R
import af.shizuku.manager.databinding.FragmentServerMetricsBinding
import rikka.shizuku.Shizuku
import af.shizuku.server.IAICorePlus
import timber.log.Timber

class ServerMetricsFragment : Fragment() {

    private var _binding: FragmentServerMetricsBinding? = null
    private val binding get() = _binding!!
    private val handler = Handler(Looper.getMainLooper())
    private var aiCore: IAICorePlus? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentServerMetricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initAiCore()
    }

    private fun initAiCore() {
        try {
            if (Shizuku.pingBinder()) {
                val binder = Shizuku.getBinder()
                val shizukuService = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
                aiCore = shizukuService.aiCorePlus
            }
        } catch (e: Exception) {
            Timber.w(e)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(updateRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(updateRunnable)
    }

    private fun updateStats() {
        val ai = aiCore ?: return
        try {
            val stats = ai.serverStats
            val uptimeMs = stats.getLong("uptime_ms")
            binding.textUptime.text = formatUptime(uptimeMs)
            val clientCount = stats.getInt("client_count")
            binding.textClientCount.text = "$clientCount applications connected"
            val total = stats.getLong("mem_total")
            val free = stats.getLong("mem_free")
            val maxRaw = stats.getLong("mem_max")
            val used = (total - free).coerceAtLeast(0L)
            val max = if (maxRaw == Long.MAX_VALUE || maxRaw <= 0) total else maxRaw
            val progress = if (max > 0) ((used.toFloat() / max.toFloat()) * 100).toInt().coerceIn(0, 100) else 0
            binding.progressMemory.progress = progress
            val maxStr = if (maxRaw == Long.MAX_VALUE) "Uncapped" else formatSize(max)
            binding.textMemoryDetails.text = "${formatSize(used)} / $maxStr (Max Allowed)"
        } catch (e: Exception) {
            Timber.w(e)
        }
    }

    private fun formatUptime(ms: Long): String {
        val seconds = (ms / 1000) % 60
        val minutes = (ms / (1000 * 60)) % 60
        val hours = (ms / (1000 * 60 * 60)) % 24
        val days = (ms / (1000 * 60 * 60 * 24))
        return if (days > 0) String.format("%dd %02d:%02d:%02d", days, hours, minutes, seconds)
        else String.format("%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatSize(bytes: Long): String {
        val kb = bytes / 1024
        val mb = kb / 1024
        return if (mb > 0) "$mb MB" else "$kb KB"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
