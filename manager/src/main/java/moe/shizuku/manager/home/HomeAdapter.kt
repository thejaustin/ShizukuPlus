package moe.shizuku.manager.home

import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.management.AppsViewModel
import moe.shizuku.manager.utils.EnvironmentUtils
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool

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

        private val DEFAULT_ORDER = listOf(
            ID_TERMINAL, ID_START_ROOT, ID_START_WADB, ID_START_ADB, ID_AUTOMATION, ID_LEARN_MORE
        )
    }

    private val cardOrder: MutableList<Long> = run {
        val saved = ShizukuSettings.getCardOrder()
        if (saved.isNullOrEmpty()) {
            DEFAULT_ORDER.toMutableList()
        } else {
            val parsed = saved.split(",").mapNotNull { it.trim().toLongOrNull() }
            val merged = parsed.toMutableList()
            DEFAULT_ORDER.forEach { if (it !in merged) merged.add(it) }
            merged
        }
    }

    init {
        setHasStableIds(true)
        HomeEditMode.onChanged = { updateData() }
        HomeEditMode.removeCardCallback = { cardId ->
            ShizukuSettings.addHiddenHomeCard(cardId.toString())
            HomeEditMode.exit()
            updateData()
        }
    }

    override fun onCreateCreatorPool(): IndexCreatorPool = IndexCreatorPool()

    fun updateData() {
        scope.launch {
            val status = homeModel.serviceStatus.value?.data ?: return@launch
            val grantedCount = appsModel.grantedCount.value?.data ?: 0
            val adbPermission = status.permission
            val running = status.isRunning
            val isPrimaryUser = UserHandleCompat.myUserId() == 0
            val rootRestart = running && status.uid == 0
            val hidden = ShizukuSettings.getHiddenHomeCards()

            withContext(Dispatchers.Main) {
                clear()

                // Fixed cards
                addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
                if (adbPermission) {
                    addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
                }
                if (running && !adbPermission) {
                    addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
                }

                // Draggable cards
                cardOrder.forEach { id ->
                    if (id.toString() in hidden) return@forEach
                    when (id) {
                        ID_TERMINAL -> if (adbPermission && ShizukuSettings.showTerminalHome()) 
                            addItem(TerminalViewHolder.CREATOR, status, id)
                        ID_START_ROOT -> if (isPrimaryUser && EnvironmentUtils.isRooted()) 
                            addItem(StartRootViewHolder.CREATOR, rootRestart, id)
                        ID_START_WADB -> if (isPrimaryUser && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0))
                            addItem(StartWirelessAdbViewHolder.creator(scope), null, id)
                        ID_START_ADB -> if (isPrimaryUser && ShizukuSettings.showStartAdbHome())
                            addItem(StartAdbViewHolder.CREATOR, null, id)
                        ID_AUTOMATION -> if (ShizukuSettings.showAutomationHome())
                            addItem(AutomationViewHolder.CREATOR, null, id)
                        ID_LEARN_MORE -> if (ShizukuSettings.showLearnMoreHome())
                            addItem(LearnMoreViewHolder.CREATOR, null, id)
                    }
                }

                notifyDataSetChanged()
            }
        }
    }

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

    fun persistCardOrder() {
        ShizukuSettings.setCardOrder(cardOrder.joinToString(","))
    }

    fun isDraggable(position: Int): Boolean = getItemId(position) in DEFAULT_ORDER
    private fun Long.str() = this.toString()
}
