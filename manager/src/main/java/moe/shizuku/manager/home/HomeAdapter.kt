package moe.shizuku.manager.home

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool
import rikka.shizuku.Shizuku

class HomeAdapter(
    private val homeModel: HomeViewModel,
    private val appsModel: AppsViewModel,
    private val scope: CoroutineScope
) : IdBasedRecyclerViewAdapter(ArrayList()) {

    companion object {
        const val ID_STATUS = 0L
        const val ID_APPS = 1L
        const val ID_TERMINAL = 2L
        const val ID_START_ROOT = 3L
        const val ID_START_WADB = 4L
        const val ID_START_ADB = 5L
        const val ID_LEARN_MORE = 6L
        const val ID_ADB_PERMISSION_LIMITED = 7L
        const val ID_AUTOMATION = 8L
        const val ID_DOCTOR = 9L

        // Default order for draggable cards
        private val DEFAULT_ORDER = listOf(
            ID_TERMINAL, ID_START_ROOT, ID_START_WADB, ID_START_ADB, ID_AUTOMATION, ID_LEARN_MORE
        )
    }

    // In-memory ordered list of draggable card IDs, loaded/saved from prefs
    private val cardOrder: MutableList<Long> = run {
        val saved = ShizukuSettings.getCardOrder()
        if (saved.isNullOrEmpty()) {
            DEFAULT_ORDER.toMutableList()
        } else {
            val parsed = saved.split(",").mapNotNull { it.trim().toLongOrNull() }
            // Include any new defaults not yet in saved order
            val merged = parsed.toMutableList()
            DEFAULT_ORDER.forEach { if (it !in merged) merged.add(it) }
            merged
        }
    }

    init {
        updateData()
        setHasStableIds(true)
        HomeEditMode.onChanged = { notifyDataSetChanged() }
        HomeEditMode.removeCardCallback = { cardId ->
            ShizukuSettings.addHiddenHomeCard(cardId.toString())
            HomeEditMode.exit()
            updateData()
        }
    }

    override fun onCreateCreatorPool(): IndexCreatorPool {
        return IndexCreatorPool()
    }

    fun updateData() {
        val status = homeModel.serviceStatus.value?.data ?: return
        val grantedCount = appsModel.grantedCount.value?.data ?: 0
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0
        val rootRestart = running && status.uid == 0
        val hidden = ShizukuSettings.getHiddenHomeCards()

        // Build eligible draggable cards keyed by ID
        val eligible = mutableMapOf<Long, () -> Unit>()

        if (adbPermission && ShizukuSettings.showTerminalHome() && ID_TERMINAL.str() !in hidden) {
            eligible[ID_TERMINAL] = { addItem(TerminalViewHolder.CREATOR, status, ID_TERMINAL) }
        }
        if (isPrimaryUser) {
            if (EnvironmentUtils.isRooted() && ID_START_ROOT.str() !in hidden) {
                eligible[ID_START_ROOT] = { addItem(StartRootViewHolder.CREATOR, rootRestart, ID_START_ROOT) }
            }
            if ((Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
                        EnvironmentUtils.isTelevision() ||
                        EnvironmentUtils.getAdbTcpPort() > 0) && ID_START_WADB.str() !in hidden) {
                eligible[ID_START_WADB] = { addItem(StartWirelessAdbViewHolder.creator(scope), null, ID_START_WADB) }
            }
            if (ShizukuSettings.showStartAdbHome() && ID_START_ADB.str() !in hidden) {
                eligible[ID_START_ADB] = { addItem(StartAdbViewHolder.CREATOR, null, ID_START_ADB) }
            }
        }
        if (ShizukuSettings.showAutomationHome() && ID_AUTOMATION.str() !in hidden) {
            eligible[ID_AUTOMATION] = { addItem(AutomationViewHolder.CREATOR, null, ID_AUTOMATION) }
        }
        if (ShizukuSettings.showLearnMoreHome() && ID_LEARN_MORE.str() !in hidden) {
            eligible[ID_LEARN_MORE] = { addItem(LearnMoreViewHolder.CREATOR, null, ID_LEARN_MORE) }
        }

        clear()

        // Fixed cards first
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
        }
        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
        }

        // Draggable cards in persisted order
        cardOrder
            .filter { it in eligible }
            .forEach { eligible[it]?.invoke() }

        notifyDataSetChanged()
    }

    /** Called by ItemTouchHelper during drag to swap positions. */
    fun moveItem(fromPos: Int, toPos: Int) {
        val fromId = getItemId(fromPos)
        val toId = getItemId(toPos)
        val fromIdx = cardOrder.indexOf(fromId)
        val toIdx = cardOrder.indexOf(toId)
        if (fromIdx >= 0 && toIdx >= 0) {
            cardOrder.removeAt(fromIdx)
            cardOrder.add(toIdx, fromId)
        }
        notifyItemMoved(fromPos, toPos)
    }

    /** Persist the current card order to SharedPreferences. Called after drag ends. */
    fun persistCardOrder() {
        ShizukuSettings.setCardOrder(cardOrder.joinToString(","))
    }

    /** Returns true if the card at this position is draggable. */
    fun isDraggable(position: Int): Boolean = getItemId(position) in DEFAULT_ORDER

    private fun Long.str() = this.toString()
}
