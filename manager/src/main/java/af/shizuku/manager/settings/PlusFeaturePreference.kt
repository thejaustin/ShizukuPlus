package af.shizuku.manager.settings

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import af.shizuku.manager.R

class PlusFeaturePreference(context: Context, attrs: AttributeSet) : SwitchPreferenceCompat(context, attrs) {

    private val infoTitle: Int
    private val infoDetail: Int
    private val badgeType: Int
    private val severityBadge: Int
    private var integrationPackage: String? = null
    private var integrationAppName: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlusFeaturePreference)
        infoTitle = a.getResourceId(R.styleable.PlusFeaturePreference_infoTitle, 0)
        infoDetail = a.getResourceId(R.styleable.PlusFeaturePreference_infoDetail, 0)
        badgeType = a.getInt(R.styleable.PlusFeaturePreference_badgeType, 0)
        severityBadge = a.getInt(R.styleable.PlusFeaturePreference_severityBadge, 0)
        a.recycle()
    }

    fun setIntegration(packageName: String, appName: String) {
        this.integrationPackage = packageName
        this.integrationAppName = appName
        notifyChanged()
    }

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        val titleView = holder.findViewById(android.R.id.title) as? TextView
        val summaryView = holder.findViewById(android.R.id.summary) as? TextView

        titleView?.apply {
            isSingleLine = false
            if (badgeType != 0 || severityBadge != 0) applyBadges(this)
        }

        summaryView?.apply {
            isSingleLine = false
        }

        holder.itemView.requestLayout()

        holder.itemView.setOnLongClickListener {
            if (integrationPackage != null) launchIntegration() else showHelp()
            true
        }
    }

    private fun badgeStyleFor(type: Int): Triple<String, Int, Int>? = when (type) {
        1 -> Triple(
            "PLUS",
            resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE8DEF8.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF21005D.toInt())
        )
        2 -> Triple(
            "ROOT",
            resolveColor(com.google.android.material.R.attr.colorErrorContainer, 0xFFFFDAD6.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnErrorContainer, 0xFF410002.toInt())
        )
        3 -> Triple(
            "EXP",
            resolveColor(com.google.android.material.R.attr.colorTertiaryContainer, 0xFFFFD8E4.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnTertiaryContainer, 0xFF31111D.toInt())
        )
        else -> null
    }

    private fun severityBadgeStyleFor(type: Int): Triple<String, Int, Int>? = when (type) {
        // No M3 "warning" role exists, so RISKY is a fixed amber rather than theme-resolved.
        1 -> Triple("RISKY", 0xFFFFE0B2.toInt(), 0xFF7A4A00.toInt())
        // Solid colorError (not the softer colorErrorContainer ROOT uses) so DANGEROUS reads as
        // a step up in severity even when both badges appear on the same item.
        2 -> Triple(
            "DANGEROUS",
            resolveColor(com.google.android.material.R.attr.colorError, 0xFFB3261E.toInt()),
            resolveColor(com.google.android.material.R.attr.colorOnError, 0xFFFFFFFF.toInt())
        )
        else -> null
    }

    private fun applyBadges(titleView: TextView) {
        val badges = listOfNotNull(badgeStyleFor(badgeType), severityBadgeStyleFor(severityBadge))
        if (badges.isEmpty()) return
        val spannable = SpannableStringBuilder(titleView.text)
        for ((badgeLabel, bgColor, fgColor) in badges) {
            spannable.append("  ")
            val start = spannable.length
            spannable.append(" $badgeLabel ")
            val end = spannable.length
            spannable.setSpan(BackgroundColorSpan(bgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(ForegroundColorSpan(fgColor), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(RelativeSizeSpan(0.65f), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            spannable.setSpan(StyleSpan(Typeface.BOLD), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        titleView.text = spannable
    }

    private fun launchIntegration() {
        val pkg = integrationPackage ?: return
        val intent = context.packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(
                context, R.string.app_management_no_launcher, android.widget.Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun resolveColor(attr: Int, fallback: Int): Int {
        val typedValue = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, typedValue, true)
        return if (resolved) typedValue.data else fallback
    }

    private fun showHelp() {
        if (infoDetail != 0) {
            val dialog = com.google.android.material.bottomsheet.BottomSheetDialog(context)

            // Outer container
            val container = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt(),
                    (24 * context.resources.displayMetrics.density).toInt()
                )
                // Use theme surface background
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurface, 0xFFFFFFFF.toInt()))
            }

            // Drag handle indicator
            val dragHandle = android.view.View(context).apply {
                val params = android.widget.LinearLayout.LayoutParams(
                    (36 * context.resources.displayMetrics.density).toInt(),
                    (4 * context.resources.displayMetrics.density).toInt()
                ).apply {
                    gravity = android.view.Gravity.CENTER_HORIZONTAL
                    bottomMargin = (16 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
                setBackgroundColor(resolveColor(com.google.android.material.R.attr.colorOutlineVariant, 0xFFCCCCCC.toInt()))
            }
            container.addView(dragHandle)

            // Title
            val titleTextView = TextView(context).apply {
                text = context.getString(if (infoTitle != 0) infoTitle else R.string.settings_plus_learn_more)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
                setPadding(0, 0, 0, (12 * context.resources.displayMetrics.density).toInt())
            }
            container.addView(titleTextView)

            // Detail Card (container for content)
            val cardView = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF5F5F5.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (20 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val detailTextView = TextView(context).apply {
                text = context.getString(infoDetail)
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF333333.toInt()))
                setLineSpacing(0f, 1.25f)
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt()
                )
            }
            cardView.addView(detailTextView)
            container.addView(cardView)

            // Interactive Switch Card
            val switchCard = com.google.android.material.card.MaterialCardView(context).apply {
                radius = (16 * context.resources.displayMetrics.density)
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(resolveColor(com.google.android.material.R.attr.colorPrimaryContainer, 0xFFE0F2F1.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = (24 * context.resources.displayMetrics.density).toInt()
                }
                layoutParams = params
            }

            val switchLayout = android.widget.LinearLayout(context).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt(),
                    (16 * context.resources.displayMetrics.density).toInt(),
                    (12 * context.resources.displayMetrics.density).toInt()
                )
            }

            val switchText = TextView(context).apply {
                text = "Enable feature"
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(resolveColor(com.google.android.material.R.attr.colorOnPrimaryContainer, 0xFF004D40.toInt()))
                val params = android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                layoutParams = params
            }
            switchLayout.addView(switchText)

            val mSwitch = com.google.android.material.materialswitch.MaterialSwitch(context).apply {
                isChecked = this@PlusFeaturePreference.isChecked
                setOnCheckedChangeListener { _, isCheckedVal ->
                    this@PlusFeaturePreference.isChecked = isCheckedVal
                    this@PlusFeaturePreference.callChangeListener(isCheckedVal)
                }
            }
            switchLayout.addView(mSwitch)
            switchCard.addView(switchLayout)
            container.addView(switchCard)

            // Dismiss Button
            val closeButton = com.google.android.material.button.MaterialButton(context).apply {
                text = "Close"
                cornerRadius = (24 * context.resources.displayMetrics.density).toInt()
                val params = android.widget.LinearLayout.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    (48 * context.resources.displayMetrics.density).toInt()
                )
                layoutParams = params
                setOnClickListener { dialog.dismiss() }
            }
            container.addView(closeButton)

            dialog.setContentView(container)
            dialog.show()
        }
    }
}
