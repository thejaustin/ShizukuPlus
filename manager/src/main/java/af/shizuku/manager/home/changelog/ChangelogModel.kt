package af.shizuku.manager.home.changelog

import af.shizuku.manager.R
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes

/** Category a group of changes belongs to; drives the section card's icon, tone and the filter chips. */
enum class ChangeKind(
    @StringRes val label: Int,
    @DrawableRes val icon: Int,
) {
    FEATURE(R.string.changelog_kind_features, R.drawable.ic_bolt_24),
    FIX(R.string.changelog_kind_fixes, R.drawable.ic_autorenew),
    UI(R.string.changelog_kind_ui, R.drawable.ic_palette_24),
    DOCS(R.string.changelog_kind_docs, R.drawable.ic_article_24),
    OTHER(R.string.changelog_kind_other, R.drawable.ic_code_24),
}

data class ChangeSection(
    val kind: ChangeKind,
    val title: String,
    val items: List<String>,
)

data class TimelineRelease(
    val tag: String,
    val date: String,
    val sections: List<ChangeSection>,
    val isNew: Boolean,
    val isInstalled: Boolean,
    val isLatest: Boolean,
    val isPrerelease: Boolean,
    /** The first release the user had already seen: everything above it is what an update added. */
    val isLeftOff: Boolean = false,
) {
    val changeCount: Int get() = sections.sumOf { it.items.size }

    fun count(kind: ChangeKind): Int = sections.filter { it.kind == kind }.sumOf { it.items.size }
}

enum class ChangelogSort(
    @StringRes val label: Int,
) {
    NEWEST(R.string.changelog_sort_newest),
    OLDEST(R.string.changelog_sort_oldest),
    MOST_CHANGES(R.string.changelog_sort_most_changes),
    ;

    fun apply(releases: List<TimelineRelease>): List<TimelineRelease> =
        when (this) {
            NEWEST -> releases
            OLDEST -> releases.reversed()
            MOST_CHANGES -> releases.sortedByDescending { it.changeCount }
        }
}
