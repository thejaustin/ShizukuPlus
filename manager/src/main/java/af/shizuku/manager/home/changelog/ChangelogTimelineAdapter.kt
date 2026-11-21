package af.shizuku.manager.home.changelog

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.update.UpdateChecker
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.useTextAppearance
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.text.format.DateUtils
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

sealed class TimelineRow {
    data class Summary(
        val fromLabel: String?,
        val toLabel: String,
        val headline: String,
        val tally: String,
        /** Plain headlines of the newest features, shown as a short "what's new" list. */
        val highlights: List<String> = emptyList(),
        /** Features beyond [highlights], for the "+N more" line. */
        val moreHighlights: Int = 0,
    ) : TimelineRow()

    data class Release(
        val release: TimelineRelease,
        val isFirst: Boolean,
        val isLast: Boolean,
    ) : TimelineRow()

    object Loading : TimelineRow()

    object Failed : TimelineRow()
}

class ChangelogTimelineAdapter(
    private val onLink: (String) -> Unit,
    private val onRetryOlder: () -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    private var rows: List<TimelineRow> = emptyList()
    private val expandedTags = mutableSetOf<String>()
    private val fullSections = mutableSetOf<String>() // "tag|kind" sections opened past the preview
    private val openChanges = mutableSetOf<String>() // "tag|hash" changes whose detail is fully open

    // Newest first; the installed release's place in it, for the update path on the rail.
    private var releaseOrder: List<String> = emptyList()
    private var installedPos = -1

    fun submit(
        newRows: List<TimelineRow>,
        autoExpand: String?,
    ) {
        if (autoExpand != null && expandedTags.isEmpty()) expandedTags.add(autoExpand)
        rows = newRows
        releaseOrder = newRows.filterIsInstance<TimelineRow.Release>().map { it.release.tag }
        installedPos = newRows.filterIsInstance<TimelineRow.Release>().indexOfFirst { it.release.isInstalled }
        notifyDataSetChanged()
    }

    /** Opens (or closes) every release card at once; the newest-release card stays open when collapsing. */
    fun setAllExpanded(expand: Boolean) {
        expandedTags.clear()
        if (expand) expandedTags.addAll(releaseOrder) else releaseOrder.firstOrNull()?.let { expandedTags.add(it) }
        notifyDataSetChanged()
    }

    val allExpanded: Boolean get() = releaseOrder.isNotEmpty() && expandedTags.containsAll(releaseOrder)

    override fun getItemCount() = rows.size

    override fun getItemViewType(position: Int) =
        when (rows[position]) {
            is TimelineRow.Summary -> 0
            is TimelineRow.Release -> 1
            TimelineRow.Loading, TimelineRow.Failed -> 2
        }

    override fun onCreateViewHolder(
        parent: ViewGroup,
        viewType: Int,
    ): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            0 -> SummaryHolder(inflater.inflate(R.layout.item_changelog_summary, parent, false))
            1 -> ReleaseHolder(inflater.inflate(R.layout.item_changelog_release, parent, false))
            else -> FooterHolder(inflater.inflate(R.layout.item_changelog_footer, parent, false))
        }
    }

    override fun onBindViewHolder(
        holder: RecyclerView.ViewHolder,
        position: Int,
    ) {
        when (val row = rows[position]) {
            is TimelineRow.Summary -> (holder as SummaryHolder).bind(row)
            is TimelineRow.Release -> (holder as ReleaseHolder).bind(row)
            TimelineRow.Loading -> (holder as FooterHolder).bind(failed = false)
            TimelineRow.Failed -> (holder as FooterHolder).bind(failed = true)
        }
    }

    override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
        (holder as? FooterHolder)?.stop()
        (holder as? ReleaseHolder)?.stop()
    }

    private fun color(
        view: View,
        attr: Int,
    ) = MaterialColors.getColor(view, attr)

    private fun pill(
        view: View,
        bg: Int,
        fg: Int,
        radiusDp: Float = 100f,
    ): GradientDrawable {
        val d = view.resources.displayMetrics.density
        return GradientDrawable()
            .apply {
                cornerRadius = radiusDp * d
                setColor(bg)
            }.also { if (view is TextView) view.setTextColor(fg) }
    }

    // ---- Summary -----------------------------------------------------------------------------

    inner class SummaryHolder(
        v: View,
    ) : RecyclerView.ViewHolder(v) {
        private val from = v.findViewById<TextView>(R.id.from_pill)
        private val to = v.findViewById<TextView>(R.id.to_pill)
        private val arrow = v.findViewById<ImageView>(R.id.arrow)
        private val headline = v.findViewById<TextView>(R.id.headline)
        private val tally = v.findViewById<TextView>(R.id.tally)
        private val highlights = v.findViewById<TextView>(R.id.highlights)

        fun bind(row: TimelineRow.Summary) {
            val surface = color(itemView, com.google.android.material.R.attr.colorSurface)
            val onSurface = color(itemView, com.google.android.material.R.attr.colorOnSurface)
            val primary = color(itemView, androidx.appcompat.R.attr.colorPrimary)
            val onPrimary = color(itemView, com.google.android.material.R.attr.colorOnPrimary)
            from.isVisible(row.fromLabel != null)
            arrow.visibility = if (row.fromLabel != null) View.VISIBLE else View.GONE
            from.text = row.fromLabel
            from.background = pill(from, ColorUtils.setAlphaComponent(surface, 0xB0), onSurface)
            to.text = row.toLabel
            to.background = pill(to, primary, onPrimary)
            headline.text = row.headline
            tally.text = row.tally
            tally.visibility = if (row.tally.isBlank()) View.GONE else View.VISIBLE
            val lines = row.highlights.map { "•  $it" }.toMutableList()
            if (row.moreHighlights > 0) {
                lines.add(itemView.resources.getQuantityString(R.plurals.changelog_highlights_more, row.moreHighlights, row.moreHighlights))
            }
            highlights.text = lines.joinToString("\n")
            highlights.visibility = if (lines.isEmpty()) View.GONE else View.VISIBLE
        }

        private fun View.isVisible(v: Boolean) {
            visibility = if (v) View.VISIBLE else View.GONE
        }
    }

    // ---- Release -----------------------------------------------------------------------------

    inner class ReleaseHolder(
        v: View,
    ) : RecyclerView.ViewHolder(v) {
        private val rail = v.findViewById<TimelineRailView>(R.id.rail)
        private val header = v.findViewById<View>(R.id.header)
        private val version = v.findViewById<TextView>(R.id.version)
        private val badges = v.findViewById<LinearLayout>(R.id.badges)
        private val chevron = v.findViewById<ImageView>(R.id.chevron)
        private val summary = v.findViewById<TextView>(R.id.summary)
        private val sections = v.findViewById<LinearLayout>(R.id.sections)

        init {
            with(af.shizuku.manager.utils.MotionUtils) { header.applySpringTouch(scale = 0.98f) }
        }

        fun stop() = rail.setPulsing(false)

        fun bind(row: TimelineRow.Release) {
            val r = row.release
            val outline = color(itemView, com.google.android.material.R.attr.colorOutline)
            val outlineVariant = color(itemView, com.google.android.material.R.attr.colorOutlineVariant)
            val primary = color(itemView, androidx.appcompat.R.attr.colorPrimary)
            val active = r.isLatest || r.isInstalled || r.isNew
            val node = if (active) primary else outline
            rail.isFirst = row.isFirst
            rail.isLast = row.isLast
            rail.filled = active
            rail.setColors(outlineVariant, node, ColorUtils.setAlphaComponent(node, 0x33), primary)
            rail.setPulsing(r.isLatest && ShizukuSettings.isExpressiveAnimationsEnabled())
            // Behind on updates: light the rail from the installed release up to the latest.
            val pos = releaseOrder.indexOf(r.tag)
            val behind = installedPos > 0 && pos >= 0
            rail.highlightAbove = behind && pos in 1..installedPos
            rail.highlightBelow = behind && pos < installedPos

            version.text = r.tag
            bindBadges(r)
            summary.text = summaryLine(r)

            val expanded = r.tag in expandedTags
            with(af.shizuku.manager.utils.MotionUtils) { chevron.cancelAllSprings() }
            chevron.rotation = if (expanded) 180f else 0f
            rail.setOpen(expanded, animate = false)
            renderSections(r, expanded)

            header.setOnClickListener {
                HapticUtils.segmentTick(it)
                val open = if (expandedTags.remove(r.tag)) false else expandedTags.add(r.tag)
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    (itemView.parent as? ViewGroup)?.let {
                        TransitionManager.beginDelayedTransition(
                            it,
                            AutoTransition().setDuration(ShizukuSettings.scaledAnimationDuration(200)),
                        )
                    }
                    with(af.shizuku.manager.utils.MotionUtils) {
                        chevron.springTo(
                            androidx.dynamicanimation.animation.SpringAnimation.ROTATION,
                            if (open) 180f else 0f,
                            com.google.android.material.R.attr.motionSpringFastSpatial,
                        )
                    }
                } else {
                    chevron.rotation = if (open) 180f else 0f
                }
                rail.setOpen(open, animate = true)
                renderSections(r, open)
            }
        }

        private fun summaryLine(r: TimelineRelease): String {
            val ctx = itemView.context
            val parts = mutableListOf<String>()
            relativeDate(r.date)?.let { parts.add(it) }
            if (r.changeCount > 0) {
                parts.add(ctx.resources.getQuantityString(R.plurals.changelog_change_count, r.changeCount, r.changeCount))
            }
            return parts.joinToString(" · ")
        }

        private fun bindBadges(r: TimelineRelease) {
            badges.removeAllViews()
            val d = itemView.resources.displayMetrics.density

            fun add(
                label: Int,
                bgAttr: Int,
                fgAttr: Int,
            ) {
                val tv =
                    TextView(itemView.context).apply {
                        setText(label)
                        useTextAppearance(com.google.android.material.R.attr.textAppearanceLabelSmall)
                        setPadding((8 * d).toInt(), (2 * d).toInt(), (8 * d).toInt(), (2 * d).toInt())
                        background = pill(this, color(this, bgAttr), color(this, fgAttr))
                        layoutParams =
                            LinearLayout
                                .LayoutParams(
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT,
                                ).apply { marginEnd = (6 * d).toInt() }
                    }
                badges.addView(tv)
            }
            // Strongest first, at most two so a narrow sheet never wraps.
            val wanted = mutableListOf<Triple<Int, Int, Int>>()
            if (r.isLeftOff) wanted.add(Triple(R.string.changelog_badge_left_off, com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer))
            if (r.isInstalled) wanted.add(Triple(R.string.changelog_badge_installed, com.google.android.material.R.attr.colorTertiaryContainer, com.google.android.material.R.attr.colorOnTertiaryContainer))
            if (r.isPrerelease) wanted.add(Triple(R.string.changelog_badge_prerelease, com.google.android.material.R.attr.colorErrorContainer, com.google.android.material.R.attr.colorOnErrorContainer))
            if (r.isLatest) wanted.add(Triple(R.string.changelog_badge_latest, com.google.android.material.R.attr.colorPrimaryContainer, com.google.android.material.R.attr.colorOnPrimaryContainer))
            if (r.isNew && !r.isLatest) wanted.add(Triple(R.string.changelog_badge_new, com.google.android.material.R.attr.colorSecondaryContainer, com.google.android.material.R.attr.colorOnSecondaryContainer))
            wanted.take(2).forEach { add(it.first, it.second, it.third) }
        }

        private fun renderSections(
            r: TimelineRelease,
            expanded: Boolean,
        ) {
            sections.visibility = if (expanded) View.VISIBLE else View.GONE
            if (!expanded) return
            sections.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)
            if (r.sections.isEmpty()) {
                sections.addView(
                    TextView(itemView.context).apply {
                        setText(R.string.changelog_fallback_message)
                        setTextColor(color(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                    },
                )
                return
            }
            r.sections.forEach { sections.addView(buildSection(inflater, r, it)) }
        }

        private fun buildSection(
            inflater: LayoutInflater,
            r: TimelineRelease,
            s: ChangeSection,
        ): View {
            val card = inflater.inflate(R.layout.item_changelog_section, sections, false) as MaterialCardView
            val ctx = card.context
            val (bgAttr, fgAttr) =
                when (s.kind) {
                    ChangeKind.FEATURE -> com.google.android.material.R.attr.colorPrimaryContainer to com.google.android.material.R.attr.colorOnPrimaryContainer
                    ChangeKind.FIX -> com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
                    ChangeKind.UI -> com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
                    else -> com.google.android.material.R.attr.colorSurfaceContainerHighest to com.google.android.material.R.attr.colorOnSurfaceVariant
                }
            val bg = color(card, bgAttr)
            val fg = color(card, fgAttr)
            card.findViewById<MaterialCardView>(R.id.icon_chip).setCardBackgroundColor(bg)
            card.findViewById<ImageView>(R.id.icon).apply {
                setImageResource(s.kind.icon)
                imageTintList = ColorStateList.valueOf(fg)
            }
            card.findViewById<TextView>(R.id.title).text = s.title.ifBlank { ctx.getString(s.kind.label) }
            card.findViewById<TextView>(R.id.count).text = s.items.size.toString()

            val key = "${r.tag}|${s.kind}"
            val long = s.items.size > PREVIEW_LIMIT
            val showAll = !long || key in fullSections
            val shown = if (showAll) s.items else s.items.take(PREVIEW_SIZE)
            val itemsBox = card.findViewById<LinearLayout>(R.id.items)
            val density = ctx.resources.displayMetrics.density
            val chipColor = ColorUtils.setAlphaComponent(fg, 0x22)
            shown.forEach { text -> itemsBox.addView(buildChange(inflater, itemsBox, r, text, fg, chipColor, density)) }
            val expander = card.findViewById<TextView>(R.id.expander)
            if (long) {
                expander.visibility = View.VISIBLE
                expander.text =
                    if (showAll) {
                        ctx.getString(R.string.changelog_show_less)
                    } else {
                        ctx.getString(R.string.changelog_show_more, s.items.size - PREVIEW_SIZE)
                    }
                expander.setOnClickListener {
                    HapticUtils.segmentTick(it)
                    if (!fullSections.remove(key)) fullSections.add(key)
                    if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                        (itemView.parent as? ViewGroup)?.let {
                            TransitionManager.beginDelayedTransition(
                                it,
                                AutoTransition().setDuration(ShizukuSettings.scaledAnimationDuration(200)),
                            )
                        }
                    }
                    renderSections(r, true)
                }
            }
            return card
        }

        /**
         * One change as a scannable row: the headline always shows; the longer detail is two lines until
         * the row is tapped. A change with no bold lead is the headline itself, clamped to three lines.
         */
        private fun buildChange(
            inflater: LayoutInflater,
            parent: ViewGroup,
            r: TimelineRelease,
            text: String,
            fg: Int,
            chipColor: Int,
            density: Float,
        ): View {
            val row = inflater.inflate(R.layout.item_changelog_change, parent, false)
            row.findViewById<View>(R.id.dot).background =
                GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(fg)
                }
            val split = ChangeSplitter.split(text)
            val headline = row.findViewById<TextView>(R.id.text)
            val detail = row.findViewById<TextView>(R.id.detail)
            headline.text = InlineFormatter.format(split.headline, chipColor, density, onLink)
            headline.movementMethod = linksOnly
            val key = "${r.tag}|${text.hashCode()}"
            val hasDetail = split.detail != null
            if (hasDetail) {
                headline.setTypeface(headline.typeface, Typeface.BOLD)
                detail.visibility = View.VISIBLE
                detail.text = InlineFormatter.format(split.detail!!, chipColor, density, onLink)
                detail.movementMethod = linksOnly
            }
            // linksOnly lets taps that miss a link fall through to the row; the movement method
            // also marks the view clickable, which would swallow them again.
            headline.isClickable = false
            headline.isFocusable = false
            detail.isClickable = false
            detail.isFocusable = false
            val target = if (hasDetail) detail else headline
            val collapsedLines = if (hasDetail) DETAIL_LINES else HEADLINE_LINES

            val more = row.findViewById<TextView>(R.id.more)

            // "Read more" only shows when the text is actually cut off, so a short note has no
            // dead control; once open, "Show less" stays so the row can be closed again.
            fun updateMore(open: Boolean) {
                if (open) {
                    more.text = more.context.getString(R.string.changelog_show_less)
                    more.visibility = View.VISIBLE
                    return
                }
                target.post {
                    val layout = target.layout
                    val cut =
                        layout != null && layout.lineCount > 0 &&
                            (layout.lineCount > collapsedLines || layout.getEllipsisCount(layout.lineCount - 1) > 0)
                    more.text = more.context.getString(R.string.changelog_read_more)
                    more.visibility = if (cut) View.VISIBLE else View.GONE
                }
            }

            fun apply(open: Boolean) {
                target.maxLines = if (open) Int.MAX_VALUE else collapsedLines
                target.ellipsize = if (open) null else TextUtils.TruncateAt.END
                // setMaxLines alone keeps the old layout, so no ellipsis drew and the cut was invisible.
                target.text = target.text
                target.isClickable = false
                target.isFocusable = false
                updateMore(open)
            }
            apply(key in openChanges)
            val ripple = android.util.TypedValue()
            if (ctxOf(row).theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                row.setBackgroundResource(ripple.resourceId)
            }
            row.setOnClickListener {
                HapticUtils.segmentTick(it)
                val open = if (openChanges.remove(key)) false else openChanges.add(key)
                if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                    (itemView.parent as? ViewGroup)?.let {
                        TransitionManager.beginDelayedTransition(
                            it,
                            AutoTransition().setDuration(ShizukuSettings.scaledAnimationDuration(200)),
                        )
                    }
                }
                apply(open)
            }
            return row
        }
    }

    // ---- Footer (shimmer / retry) -----------------------------------------------------------

    inner class FooterHolder(
        v: View,
    ) : RecyclerView.ViewHolder(v) {
        private val shimmer = v.findViewById<View>(R.id.shimmer)
        private val retry = v.findViewById<MaterialButton>(R.id.retry)
        private var anim: ValueAnimator? = null

        fun stop() {
            anim?.cancel()
            anim = null
        }

        fun bind(failed: Boolean) {
            stop()
            shimmer.visibility = if (failed) View.GONE else View.VISIBLE
            retry.visibility = if (failed) View.VISIBLE else View.GONE
            retry.setOnClickListener {
                HapticUtils.tap(it)
                onRetryOlder()
            }
            if (failed) return
            val base = color(itemView, com.google.android.material.R.attr.colorSurfaceContainerHighest)
            listOf(R.id.bar1, R.id.bar2, R.id.bar3).forEach { id ->
                itemView.findViewById<View>(id).background =
                    GradientDrawable().apply {
                        cornerRadius = 12f * itemView.resources.displayMetrics.density
                        setColor(base)
                    }
            }
            if (ShizukuSettings.isExpressiveAnimationsEnabled()) {
                anim =
                    ValueAnimator.ofFloat(0.45f, 1f).apply {
                        duration = 900
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { shimmer.alpha = it.animatedValue as Float }
                        start()
                    }
            }
        }
    }

    companion object {
        // Headline rows are short, so a section can show more of them before "Show more".
        private const val PREVIEW_LIMIT = 9
        private const val PREVIEW_SIZE = 8

        private fun ctxOf(v: View) = v.context

        /** Opens links but returns false for any other touch, so the row behind the text gets the tap. */
        private val linksOnly =
            object : LinkMovementMethod() {
                override fun onTouchEvent(
                    widget: TextView,
                    buffer: android.text.Spannable,
                    event: android.view.MotionEvent,
                ): Boolean {
                    val layout = widget.layout ?: return false
                    val x = (event.x - widget.totalPaddingLeft + widget.scrollX).toInt()
                    val y = (event.y - widget.totalPaddingTop + widget.scrollY).toInt()
                    val off = layout.getOffsetForHorizontal(layout.getLineForVertical(y), x.toFloat())
                    if (buffer.getSpans(off, off, android.text.style.ClickableSpan::class.java).isEmpty()) return false
                    return super.onTouchEvent(widget, buffer, event)
                }
            }

        private const val DETAIL_LINES = 2
        private const val HEADLINE_LINES = 3
        private const val RELATIVE_DAYS = 6

        private val iso =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

        /** "2 days ago" for recent releases, a locale date otherwise; null when there is no date. */
        fun relativeDate(date: String): String? {
            if (date.isBlank()) return null
            val millis = synchronized(iso) { runCatching { iso.parse(date)?.time }.getOrNull() } ?: return null
            val age = System.currentTimeMillis() - millis
            return if (age in 0..(RELATIVE_DAYS * DateUtils.DAY_IN_MILLIS)) {
                DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
            } else {
                UpdateChecker.formatPublishedDate(date).takeIf { it.isNotBlank() } ?: date
            }
        }
    }
}
