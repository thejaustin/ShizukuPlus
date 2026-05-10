package af.shizuku.manager.home

import android.os.Build
import com.airbnb.mvrx.withState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.UserHandleCompat
import rikka.recyclerview.BaseViewHolder
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
        const val ID_COMPANION = 9L

        private val DEFAULT_ORDER = listOf(
            ID_TERMINAL, ID_START_ROOT, ID_START_WADB, ID_START_ADB, ID_AUTOMATION, ID_LEARN_MORE, ID_COMPANION
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

    private val startWadbCreator = StartWirelessAdbViewHolder.creator(scope, homeModel)

    var isDragging = false
    private var isUpdating = false
    private var lastUpdateDataTime = 0L
    private val animatedIds = HashSet<Long>()
    
    /**
     * Callback to notify when the empty state should be shown/hidden.
     * @param isEmpty true if there are no visible cards (excluding fixed status card)
     */
    var onEmptyStateChanged: ((Boolean) -> Unit)? = null

    init {
        setHasStableIds(true)
        HomeEditMode.onChanged = { updateData() }
        HomeEditMode.removeCardCallback = { cardId ->
            ShizukuSettings.addHiddenHomeCard(cardId.toString())
            HomeEditMode.exit()
            updateData()
        }
    }

    /**
     * Restores a card by removing it from the hidden list.
     */
    fun restoreCard(cardId: Long) {
        val hidden = ShizukuSettings.getHiddenHomeCards().toMutableSet()
        if (hidden.remove(cardId.toString())) {
            ShizukuSettings.setHiddenHomeCards(hidden)
            updateData()
        }
    }

    override fun onCreateCreatorPool(): IndexCreatorPool = IndexCreatorPool()

    fun updateData() {
        if (isUpdating) return
        val now = System.currentTimeMillis()
        if (now - lastUpdateDataTime < 100) return
        lastUpdateDataTime = now
        isUpdating = true
        scope.launch {
            val (status, grantedCount, isEditMode) = withState(homeModel) {
                Triple(it.serviceStatus.invoke(), it.grantedAppCount, it.isEditMode)
            }
            val companionInstalled = withState(homeModel) { it.companionInstalled }

            if (status == null) {
                isUpdating = false
                return@launch
            }

            val adbPermission = status.permission
            val running = status.isRunning
            val isPrimaryUser = UserHandleCompat.myUserId() == 0
            val rootRestart = running && status.uid == 0
            val hidden = ShizukuSettings.getHiddenHomeCards()

            withContext(Dispatchers.Main) {
                if (isDragging) {
                    isUpdating = false
                    return@withContext
                }
                clear()

                // Fixed cards
                var fixedCardCount = 0
                addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS); fixedCardCount++
                if (adbPermission) {
                    addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS); fixedCardCount++
                }
                if (running && !adbPermission) {
                    addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED); fixedCardCount++
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
                            addItem(startWadbCreator, null, id)
                        ID_START_ADB -> if (isPrimaryUser && ShizukuSettings.showStartAdbHome())
                            addItem(StartAdbViewHolder.CREATOR, null, id)
                        ID_AUTOMATION -> if (ShizukuSettings.showAutomationHome())
                            addItem(AutomationViewHolder.CREATOR, null, id)
                        ID_LEARN_MORE -> if (ShizukuSettings.showLearnMoreHome())
                            addItem(LearnMoreViewHolder.CREATOR, null, id)
                        ID_COMPANION -> if (ShizukuSettings.isCompanionModeEnabled())
                            addItem(ShizukuCompanionViewHolder.CREATOR, companionInstalled, id)
                    }
                }

                notifyDataSetChanged()

                val hasVisibleCards = itemCount > fixedCardCount
                onEmptyStateChanged?.invoke(!hasVisibleCards)
                
                isUpdating = false
            }
        }
    }

    override fun onBindViewHolder(holder: BaseViewHolder<*>, position: Int) {
        super.onBindViewHolder(holder, position)

        // M3E entrance animation — only on first appearance per card id, not every recycle.
        val id = getItemId(position)
        if (!animatedIds.add(id)) return
        val view = holder.itemView
        view.alpha = 0f
        view.translationY = 24f
        view.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(400)
            .setStartDelay(position * 50L)
            .setInterpolator(android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f))
            .start()
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

    fun isDraggable(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return getItemId(position) in DEFAULT_ORDER
    }
    private fun Long.str() = this.toString()
}
