package af.shizuku.manager.home

import af.shizuku.common.util.UserHandleCompat
import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.management.AppsViewModel
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.EnvironmentUtils
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.MotionUtils.cancelAllSprings
import af.shizuku.manager.utils.MotionUtils.isSpringing
import android.os.Build
import androidx.core.view.isVisible
import com.airbnb.mvrx.withState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.IdBasedRecyclerViewAdapter
import rikka.recyclerview.IndexCreatorPool

class HomeAdapter(
    private val homeModel: HomeViewModel,
    private val appsModel: AppsViewModel,
    private val scope: CoroutineScope,
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
        const val ID_START_VIA_STOCK = 10L
        const val ID_BACKUP = 11L
        const val ID_DEVICE_CONTROL = 12L
        const val ID_PERMISSION_MANAGER = 13L
        const val ID_ALL_HIDDEN = 14L

        private val DEFAULT_ORDER =
            listOf(
                ID_TERMINAL,
                ID_START_ROOT,
                ID_START_WADB,
                ID_START_ADB,
                ID_AUTOMATION,
                ID_BACKUP,
                ID_DEVICE_CONTROL,
                ID_PERMISSION_MANAGER,
                ID_LEARN_MORE,
                ID_COMPANION,
            )
    }

    private val cardOrder: MutableList<Long> =
        run {
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
    private val companionCreator = ShizukuCompanionViewHolder.creator(scope, homeModel)
    private val startStockCreator = StartStockShizukuViewHolder.creator(scope)

    var isDragging = false
    private var isUpdating = false
    private var lastUpdateDataTime = 0L
    private var pendingUpdate = false
    private val animatedIds = HashSet<Long>()

    // Cached inputs from the last successful updateData() render, so moveItem() can rebuild the
    // adapter's real backing list synchronously mid-drag instead of only reordering cardOrder.
    private data class RebuildArgs(
        val status: ServiceStatus,
        val grantedCount: Int?,
        val isEditMode: Boolean,
        val companionInstalled: Boolean,
        val compatHubInstalled: Boolean,
        val isOriginalShizukuRunning: Boolean,
        val hidden: Set<String>,
    )

    private var lastRebuildArgs: RebuildArgs? = null

    /** Run by the card that replaces the others once all of them are hidden. */
    var onRestoreAllCards: (() -> Unit)? = null

    init {
        setHasStableIds(true)
        HomeEditMode.onChanged = { updateData() }
        HomeEditMode.removeCardCallback = { cardId ->
            if (cardId == ID_BACKUP) {
                // Backup uses its own opt-in flag rather than the generic hidden set, so that
                // it starts hidden by default without polluting the hidden set for all users.
                ShizukuSettings.setBackupCardVisible(!ShizukuSettings.isBackupCardVisible())
            } else {
                val hiddenSet = ShizukuSettings.getHiddenHomeCards().toMutableSet()
                if (cardId.toString() in hiddenSet) {
                    hiddenSet.remove(cardId.toString())
                } else {
                    hiddenSet.add(cardId.toString())
                }
                ShizukuSettings.setHiddenHomeCards(hiddenSet)
            }
            updateData()
        }
    }

    /**
     * Restores every hidden card at once. Wired to the "Restore cards" button on the
     * all-cards-hidden empty state, whose copy promises "Go to Settings to restore them" / a
     * bulk restore - it previously only called HomeEditMode.enter(), which doesn't unhide
     * anything on its own and left the user to un-hide each card individually via its "+"
     * toggle, contradicting what the button said it would do.
     */
    fun restoreAllCards() {
        ShizukuSettings.setHiddenHomeCards(emptySet())
        ShizukuSettings.setBackupCardVisible(true)
        ShizukuSettings.setCardOrder(null)
        cardOrder.clear()
        cardOrder.addAll(DEFAULT_ORDER)
        updateData()
    }

    override fun onCreateCreatorPool(): IndexCreatorPool = IndexCreatorPool()

    fun updateData() {
        val now = System.currentTimeMillis()
        // On start the state changes in a rapid burst (Loading -> Success, plus state-listener and
        // onResume reloads). Dropping throttled/in-flight requests loses the final "running" render,
        // leaving a stale "not running" card until the user re-enters the screen. Instead of
        // dropping, coalesce into a single trailing update so the latest state is always rendered.
        if (isUpdating || now - lastUpdateDataTime < 100) {
            if (!pendingUpdate) {
                pendingUpdate = true
                scope.launch {
                    kotlinx.coroutines.delay(120)
                    pendingUpdate = false
                    updateData()
                }
            }
            return
        }
        lastUpdateDataTime = now
        isUpdating = true
        scope.launch {
            // Read isEditMode from HomeEditMode.isActive (the live singleton) rather than
            // homeModel.isEditMode (the Mavericks state). The Mavericks state is updated via
            // a 150ms-delayed onChanged callback, so it lags behind the real edit-mode
            // toggle — this lag caused all cards to pop into view after a drag gesture that
            // started within that 150ms window (fixes #475).
            val isEditMode = HomeEditMode.isActive
            val (status, grantedCount) =
                withState(homeModel) {
                    Pair(it.serviceStatus.invoke(), it.grantedAppCount)
                }
            val companionInstalled = withState(homeModel) { it.companionInstalled }
            val compatHubInstalled = withState(homeModel) { it.compatHubInstalled }
            val isOriginalShizukuRunning = withState(homeModel) { it.isOriginalShizukuRunning }

            if (status == null) {
                isUpdating = false
                return@launch
            }

            val hidden = ShizukuSettings.getHiddenHomeCards()

            withContext(Dispatchers.Main) {
                if (isDragging) {
                    isUpdating = false
                    return@withContext
                }

                val oldIds = LongArray(itemCount) { getItemId(it) }
                rebuildItems(
                    status,
                    grantedCount,
                    isEditMode,
                    companionInstalled,
                    compatHubInstalled,
                    isOriginalShizukuRunning,
                    hidden,
                )

                dispatchDiff(oldIds)

                isUpdating = false
            }
        }
    }

    /**
     * Notifies the change from [oldIds] to the rebuilt list as moves/inserts/removes instead of
     * notifyDataSetChanged(), which snapped every card into place (most visibly right after a
     * drag, while the dropped card was still settling). Every remaining card is still rebound.
     */
    private fun dispatchDiff(oldIds: LongArray) {
        val newIds = LongArray(itemCount) { getItemId(it) }
        androidx.recyclerview.widget.DiffUtil
            .calculateDiff(
                object : androidx.recyclerview.widget.DiffUtil.Callback() {
                    override fun getOldListSize() = oldIds.size

                    override fun getNewListSize() = newIds.size

                    override fun areItemsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = oldIds[oldItemPosition] == newIds[newItemPosition]

                    // Card contents come from live state, not the list, so always rebind.
                    override fun areContentsTheSame(
                        oldItemPosition: Int,
                        newItemPosition: Int,
                    ) = false
                },
            ).dispatchUpdatesTo(this)
    }

    /**
     * Clears and repopulates the adapter's backing item list in cardOrder sequence. Must run on
     * the main thread. Returns the number of fixed (non-draggable) cards added. Does not call
     * notifyDataSetChanged()/notifyItemMoved() — callers are responsible for the right notify.
     */
    private fun rebuildItems(
        status: ServiceStatus,
        grantedCount: Int?,
        isEditMode: Boolean,
        companionInstalled: Boolean,
        compatHubInstalled: Boolean,
        isOriginalShizukuRunning: Boolean,
        hidden: Set<String>,
    ): Int {
        lastRebuildArgs = RebuildArgs(status, grantedCount, isEditMode, companionInstalled, compatHubInstalled, isOriginalShizukuRunning, hidden)
        val adbPermission = status.permission
        val running = status.isRunning
        val isPrimaryUser = UserHandleCompat.myUserId() == 0
        val rootRestart = running && status.uid == 0

        clear()

        // Fixed cards
        var fixedCardCount = 0
        addItem(ServerStatusViewHolder.CREATOR, status, ID_STATUS)
        fixedCardCount++
        if (isOriginalShizukuRunning) {
            addItem(startStockCreator, null, ID_START_VIA_STOCK)
            fixedCardCount++
        }
        if (adbPermission) {
            addItem(ManageAppsViewHolder.CREATOR, status to grantedCount, ID_APPS)
            fixedCardCount++
        }
        if (running && !adbPermission) {
            addItem(AdbPermissionLimitedViewHolder.CREATOR, status, ID_ADB_PERMISSION_LIMITED)
            fixedCardCount++
        }

        // Draggable cards
        val beforeDraggable = itemCount
        cardOrder.forEach { id ->
            val isHidden = id.toString() in hidden
            if (isHidden && !isEditMode) return@forEach
            when (id) {
                ID_TERMINAL -> addItem(TerminalViewHolder.CREATOR, status, id)
                ID_START_ROOT ->
                    if (isEditMode || (isPrimaryUser && (EnvironmentUtils.isRooted() || ShizukuSettings.isSamsungSystemUidEscalationEnabled()))) {
                        addItem(StartRootViewHolder.CREATOR, rootRestart, id)
                    }
                ID_START_WADB ->
                    if (isEditMode || (isPrimaryUser && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R || EnvironmentUtils.getAdbTcpPort() > 0))) {
                        addItem(startWadbCreator, status, id)
                    }
                ID_START_ADB ->
                    if (isEditMode || isPrimaryUser) {
                        addItem(StartAdbViewHolder.CREATOR, status, id)
                    }
                ID_AUTOMATION -> addItem(AutomationViewHolder.CREATOR, null, id)
                ID_BACKUP ->
                    if (isEditMode || ShizukuSettings.isBackupCardVisible()) {
                        addItem(AppBackupViewHolder.CREATOR, status, id)
                    }
                ID_DEVICE_CONTROL ->
                    if (ShizukuSettings.isDeviceControlHomeEnabled()) {
                        addItem(DeviceControlViewHolder.CREATOR, status, id)
                    }
                ID_PERMISSION_MANAGER -> addItem(PermissionManagerViewHolder.CREATOR, status, id)
                ID_LEARN_MORE -> addItem(LearnMoreViewHolder.CREATOR, null, id)
                ID_COMPANION -> {
                    // Show the compat hub card unconditionally (visibility is managed by the
                    // standard hidden-set like every other card). Previously the card was gated
                    // on isCompanionModeEnabled() || needsAction, which made it disappear from
                    // the home screen as soon as the hub was successfully installed — users had
                    // no persistent status indicator and couldn't tell the hub was active.
                    addItem(companionCreator, Pair(companionInstalled, compatHubInstalled), id)
                }
            }
        }
        val restore = onRestoreAllCards
        if (!isEditMode && itemCount == beforeDraggable && restore != null) {
            addItem(AllCardsHiddenViewHolder.CREATOR, restore, ID_ALL_HIDDEN)
        }

        return fixedCardCount
    }

    override fun onBindViewHolder(
        holder: BaseViewHolder<*>,
        position: Int,
    ) {
        val id = getItemId(position)
        val hidden = ShizukuSettings.getHiddenHomeCards()
        holder.itemView.tag =
            when (id) {
                ID_BACKUP -> !ShizukuSettings.isBackupCardVisible()
                else -> id.toString() in hidden
            }

        val removeBtn = holder.itemView.findViewById<android.widget.ImageButton>(R.id.remove_btn)
        if (removeBtn != null) {
            val isHidden =
                when (id) {
                    ID_BACKUP -> !ShizukuSettings.isBackupCardVisible()
                    else -> id.toString() in hidden
                }
            val iconRes = if (isHidden) R.drawable.ic_visibility_off_24 else R.drawable.ic_visibility_24
            removeBtn.setImageResource(iconRes)
            removeBtn.contentDescription =
                removeBtn.context.getString(if (isHidden) R.string.accessibility_card_show else R.string.accessibility_card_hide)
            removeBtn.setOnClickListener {
                if (isHidden) {
                    HapticUtils.toggleOn(it)
                } else {
                    HapticUtils.toggleOff(it)
                }
                HomeEditMode.removeCardCallback?.invoke(id)
            }
        }

        super.onBindViewHolder(holder, position)

        // Card borders setting; the status card has its own outline.
        val card = holder.itemView as? com.google.android.material.card.MaterialCardView
        if (card != null) {
            if (ShizukuSettings.isCornerRoundingAppliedToHome()) {
                val tv = android.util.TypedValue()
                if (card.context.theme.resolveAttribute(com.google.android.material.R.attr.shapeAppearanceCornerExtraLarge, tv, true) && tv.resourceId != 0) {
                    runCatching {
                        card.shapeAppearanceModel =
                            com.google.android.material.shape.ShapeAppearanceModel
                                .builder(card.context, tv.resourceId, 0)
                                .build()
                    }
                }
            }
            if (id != ID_STATUS) {
                af.shizuku.manager.utils.CardBorder
                    .apply(card)
            }
        }

        // Fixed cards (status, apps bar) never participate in edit mode — ensure handles stay hidden.
        if (!isDraggable(position)) {
            holder.itemView.findViewById<android.view.View>(R.id.remove_btn)?.isVisible = false
            holder.itemView.findViewById<android.view.View>(R.id.drag_handle)?.isVisible = false
        }

        // M3E entrance animation — only on first appearance per card id, not every recycle.
        if (!animatedIds.add(id)) {
            // During drag, RecyclerView owns translationX/Y for the move animation; resetting them
            // here would kill the in-flight slide and make displaced cards snap/vanish. A card
            // still settling after a drop is mid-spring: resetting it made it jump for a frame.
            if (!isDragging && !holder.itemView.isSpringing()) {
                holder.itemView.alpha = 1f
                holder.itemView.translationY = 0f
                holder.itemView.scaleX = 1f
                holder.itemView.scaleY = 1f
                holder.itemView.translationZ = 0f
            }
            return
        }
        val view = holder.itemView
        view.animate().cancel()
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationZ = 0f

        val animationsEnabled =
            af.shizuku.manager.ShizukuSettings
                .isExpressiveAnimationsEnabled() &&
                (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O || android.animation.ValueAnimator.areAnimatorsEnabled())

        if (animationsEnabled) {
            val density = view.resources.displayMetrics.density
            view.alpha = 0f
            view.translationY = 24f * density
            view.scaleX = 0.96f
            view.scaleY = 0.96f
            view
                .animate()
                ?.alpha(1f)
                ?.translationY(0f)
                ?.scaleX(1f)
                ?.scaleY(1f)
                ?.setDuration(
                    af.shizuku.manager.ShizukuSettings
                        .scaledAnimationDuration(380),
                )?.setStartDelay(
                    af.shizuku.manager.ShizukuSettings
                        // Capped: a card first shown by scrolling shouldn't wait for every one above it.
                        .scaledAnimationDuration(minOf(position, 5) * 45L),
                )?.setInterpolator(af.shizuku.manager.utils.MotionUtils.EMPHASIZED_DECELERATE)
                ?.start()
        } else {
            view.alpha = 1f
            view.translationY = 0f
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }

    override fun onViewRecycled(holder: BaseViewHolder<*>) {
        super.onViewRecycled(holder)
        holder.itemView.animate().cancel()
        holder.itemView.cancelAllSprings()
        holder.itemView.scaleX = 1f
        holder.itemView.scaleY = 1f
        holder.itemView.translationZ = 0f
        holder.itemView.translationY = 0f
        holder.itemView.alpha = 1f
    }

    fun moveItem(
        fromPos: Int,
        toPos: Int,
    ) {
        val fromId = getItemId(fromPos)
        val toId = getItemId(toPos)
        val fromIdx = cardOrder.indexOf(fromId)
        val toIdx = cardOrder.indexOf(toId)
        if (fromIdx < 0 || toIdx < 0) return
        cardOrder.removeAt(fromIdx)
        cardOrder.add(toIdx, fromId)
        // Rebuild the backing list so the adapter's item order matches cardOrder, then notify
        // with notifyItemMoved so RecyclerView's item animator slides siblings natively.
        // rebuildItems() calls only clear()/addItem() (no notify), so it's safe mid-drag.
        // The updateData() isDragging guard (L169) still blocks full notifyDataSetChanged
        // rebuilds during drag; notifyItemMoved is the lightweight per-step notify instead.
        val args = lastRebuildArgs ?: return
        rebuildItems(args.status, args.grantedCount, args.isEditMode, args.companionInstalled, args.compatHubInstalled, args.isOriginalShizukuRunning, args.hidden)
        notifyItemMoved(fromPos, toPos)
    }

    fun persistCardOrder() {
        ShizukuSettings.setCardOrder(cardOrder.joinToString(","))
    }

    fun isDraggable(position: Int): Boolean {
        if (position < 0 || position >= itemCount) return false
        return getItemId(position) in DEFAULT_ORDER
    }
}
