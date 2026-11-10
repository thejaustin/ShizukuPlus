package af.shizuku.manager.home.changelog

import af.shizuku.manager.BuildConfig
import af.shizuku.manager.R
import af.shizuku.manager.home.ChangelogDialogFragment.ReleaseItem
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.HapticUtils
import android.view.View
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch

/**
 * Drives the "Timeline" changelog style: parses releases into categorised sections, shows them on
 * a rail, lets the user filter by change kind and sort, and pages in older releases on demand.
 * The design is inspired by Morphe Manager's timeline changelog (no code reused).
 */
class TimelineChangelogController(
    private val fragment: Fragment,
    private val root: View,
    initial: List<ReleaseItem>,
) {
    private val ctx = root.context

    // VERSION_NAME carries the app name ("Shizuku+ 13.7.0.r2786"); release tags are bare versions.
    private val installedTag = versionToken(BuildConfig.VERSION_NAME)
    private val releases = initial.map { it.toTimeline() }.toMutableList()
    private val selectedKinds = mutableSetOf<ChangeKind>()
    private var sort = ChangelogSort.NEWEST

    private var nextPage = 2 // page 1 is the initial set's source; older pages follow
    private var olderState = OlderState.IDLE
    private var exhausted = initial.size < MIN_FOR_MORE

    private enum class OlderState { IDLE, LOADING, FAILED }

    private val adapter =
        ChangelogTimelineAdapter(
            onLink = { url -> runCatching { CustomTabsHelper.launchUrlOrCopy(ctx, url) } },
            onRetryOlder = { loadOlder() },
        )
    private val list = root.findViewById<RecyclerView>(R.id.timeline_list)
    private val empty = root.findViewById<TextView>(R.id.empty_text)
    private val filterGroup = root.findViewById<ChipGroup>(R.id.filter_chips)
    private val sortChip = root.findViewById<Chip>(R.id.sort_chip)

    val selectedTag: String get() = releases.firstOrNull()?.tag ?: ""

    fun setup() {
        val lm = LinearLayoutManager(ctx)
        list.layoutManager = lm
        list.adapter = adapter
        list.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(
                    rv: RecyclerView,
                    dx: Int,
                    dy: Int,
                ) {
                    if (dy > 0 && lm.findLastVisibleItemPosition() >= adapter.itemCount - 3) loadOlder()
                }
            },
        )
        buildFilters()
        sortChip.setOnClickListener { showSortMenu() }
        updateSortLabel()
        render()
    }

    private fun ReleaseItem.toTimeline(): TimelineRelease =
        TimelineRelease(
            tag = tag,
            date = date,
            sections = ChangelogParser.parse(body),
            isNew = isNew,
            isInstalled = tag.removePrefix("v") == installedTag,
            isLatest = false,
            isPrerelease = isPrerelease,
        )

    private fun buildFilters() {
        filterGroup.removeAllViews()
        val present = ChangeKind.values().filter { k -> releases.any { it.count(k) > 0 } }
        if (present.size < 2) {
            (filterGroup.parent as? View)?.visibility = View.GONE
            return
        }
        (filterGroup.parent as? View)?.visibility = View.VISIBLE
        val all =
            Chip(ctx).apply {
                setText(R.string.changelog_filter_all)
                isCheckable = true
                isChecked = selectedKinds.isEmpty()
                setOnClickListener {
                    HapticUtils.segmentTick(this)
                    selectedKinds.clear()
                    buildFilters()
                    render()
                }
            }
        filterGroup.addView(all)
        present.forEach { kind ->
            filterGroup.addView(
                Chip(ctx).apply {
                    setText(kind.label)
                    isCheckable = true
                    isChecked = kind in selectedKinds
                    if (kind.icon != 0) {
                        chipIcon =
                            androidx.core.content.ContextCompat
                                .getDrawable(ctx, kind.icon)
                        isChipIconVisible = true
                    }
                    setOnClickListener {
                        HapticUtils.segmentTick(this)
                        if (!selectedKinds.remove(kind)) selectedKinds.add(kind)
                        all.isChecked = selectedKinds.isEmpty()
                        render()
                    }
                },
            )
        }
    }

    private fun showSortMenu() {
        HapticUtils.tap(sortChip)
        PopupMenu(ctx, sortChip)
            .apply {
                ChangelogSort.values().forEachIndexed { i, s ->
                    menu.add(0, i, i, s.label).isChecked = s == sort
                }
                menu.setGroupCheckable(0, true, true)
                setOnMenuItemClickListener {
                    sort = ChangelogSort.values()[it.itemId]
                    HapticUtils.segmentTick(sortChip)
                    updateSortLabel()
                    render()
                    true
                }
            }.show()
    }

    private fun updateSortLabel() = sortChip.setText(sort.label)

    private fun render() {
        val latestTag = releases.firstOrNull()?.tag
        val filtered =
            sort
                .apply(releases.map { it.copy(isLatest = it.tag == latestTag) })
                .mapNotNull { r ->
                    if (r.sections.isEmpty() && !r.isInstalled) {
                        // Nothing user-facing left (e.g. a README-only pre-release).
                        null
                    } else if (selectedKinds.isEmpty()) {
                        r
                    } else {
                        r.copy(sections = r.sections.filter { it.kind in selectedKinds }).takeIf { it.sections.isNotEmpty() }
                    }
                }

        val rows = mutableListOf<TimelineRow>()
        summaryRow()?.let { rows.add(it) }
        val showFooter = olderState != OlderState.IDLE
        filtered.forEachIndexed { i, r ->
            rows.add(TimelineRow.Release(r, isFirst = i == 0, isLast = i == filtered.lastIndex && !showFooter))
        }
        when (olderState) {
            OlderState.LOADING -> rows.add(TimelineRow.Loading)
            OlderState.FAILED -> rows.add(TimelineRow.Failed)
            OlderState.IDLE -> Unit
        }
        empty.visibility = if (filtered.isEmpty() && olderState == OlderState.IDLE) View.VISIBLE else View.GONE
        list.visibility = if (empty.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        adapter.submit(rows, autoExpand = filtered.firstOrNull { it.isLatest }?.tag ?: filtered.firstOrNull()?.tag)
    }

    private fun summaryRow(): TimelineRow.Summary? {
        // README-only releases are hidden from the list, so don't headline them either.
        val meaningful = releases.filter { it.sections.isNotEmpty() || it.isInstalled }
        val newOnes = meaningful.filter { it.isNew }
        val latest = meaningful.firstOrNull() ?: releases.firstOrNull() ?: return null
        val pool = newOnes.ifEmpty { listOf(latest) }
        val features = pool.sumOf { it.count(ChangeKind.FEATURE) }
        val fixes = pool.sumOf { it.count(ChangeKind.FIX) }
        val ui = pool.sumOf { it.count(ChangeKind.UI) }
        val tally =
            listOf(
                features to ChangeKind.FEATURE,
                fixes to ChangeKind.FIX,
                ui to ChangeKind.UI,
            ).filter { it.first > 0 }
                .joinToString(" · ") { "${it.first} ${ctx.getString(it.second.label)}" }
                .ifBlank {
                    val total = pool.sumOf { it.changeCount }
                    if (total == 0) "" else ctx.resources.getQuantityString(R.plurals.changelog_change_count, total, total)
                }
        // A local/dev build can be ahead of the newest release; that is not an upgrade path.
        val installedAhead = revision(installedTag) >= revision(latest.tag) && revision(latest.tag) > 0
        val isUpToDate = installedAhead || latest.isInstalled || installedTag.equals(latest.tag.removePrefix("v"), ignoreCase = true)
        val headline =
            when {
                newOnes.isNotEmpty() -> ctx.getString(R.string.changelog_summary_new_releases, newOnes.size)
                isUpToDate -> ctx.getString(R.string.changelog_summary_up_to_date)
                else -> ctx.getString(R.string.update_available_title)
            }
        val from = if (!latest.isInstalled && !installedAhead) "v$installedTag" else null
        return TimelineRow.Summary(from, latest.tag, headline, tally)
    }

    private fun loadOlder() {
        if (exhausted || olderState != OlderState.IDLE) return
        olderState = OlderState.LOADING
        render()
        fragment.lifecycleScope.launch {
            val page = UpdateChecker.fetchReleasesPage(nextPage)
            if (!fragment.isAdded) return@launch
            if (page == null) {
                olderState = OlderState.FAILED
            } else {
                val known = releases.map { it.tag }.toSet()
                val fresh = page.filter { it.tagName !in known }
                releases.addAll(
                    fresh.map {
                        ReleaseItem(it.tagName, it.publishedAt, it.body, isPrerelease = it.isPrerelease).toTimeline()
                    },
                )
                nextPage++
                if (page.size < PAGE_SIZE || fresh.isEmpty()) exhausted = true
                olderState = OlderState.IDLE
                buildFilters()
            }
            render()
        }
    }

    companion object {
        private val VERSION_TOKEN = Regex("""\d+(?:\.\d+)+(?:\.r\d+)?""")
        private val REVISION = Regex("""r(\d+)""")

        /** The bare version inside a display name, e.g. "Shizuku+ 13.7.0.r2786" -> "13.7.0.r2786". */
        internal fun versionToken(name: String): String = VERSION_TOKEN.find(name)?.value ?: name.removePrefix("v")

        /** The CI revision (the number after ".r"), or 0 when absent. */
        internal fun revision(tag: String): Int =
            REVISION
                .find(tag)
                ?.groupValues
                ?.get(1)
                ?.toIntOrNull() ?: 0

        private const val PAGE_SIZE = 15
        private const val MIN_FOR_MORE = 10
    }
}
