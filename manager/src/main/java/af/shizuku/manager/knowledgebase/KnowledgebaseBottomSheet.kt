package af.shizuku.manager.knowledgebase

import af.shizuku.manager.R
import af.shizuku.manager.utils.CustomTabsHelper
import af.shizuku.manager.utils.HapticUtils
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.FragmentManager
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import io.noties.markwon.Markwon

class KnowledgebaseBottomSheet : BottomSheetDialogFragment() {
    private var topicId: String = KnowledgebaseRegistry.TOPIC_ROOT_COMPAT

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        topicId = arguments?.getString(ARG_TOPIC_ID) ?: KnowledgebaseRegistry.TOPIC_ROOT_COMPAT
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val topic =
            KnowledgebaseRegistry.getTopic(topicId)
                ?: KnowledgebaseRegistry.getTopic(KnowledgebaseRegistry.TOPIC_ROOT_COMPAT)!!

        val density = context.resources.displayMetrics.density
        val dp = { value: Int -> (value * density).toInt() }

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), 0, dp(20), dp(20))
            }

        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(dp(20), 0, dp(20), dp(20) + navBars.bottom)
            insets
        }

        // M3 drag handle: accessible (tap to expand/collapse, announced by TalkBack), unlike a
        // drawn bar.
        root.addView(
            com.google.android.material.bottomsheet.BottomSheetDragHandleView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        gravity = Gravity.CENTER_HORIZONTAL
                    }
            },
        )

        // Header container (Icon + Title + Subtitle)
        val headerLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            bottomMargin = dp(14)
                        }
            }

        val iconView =
            ImageView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(36), dp(36)).apply {
                        marginEnd = dp(14)
                    }
                setImageResource(topic.iconRes)
                imageTintList =
                    ColorStateList.valueOf(
                        resolveThemeColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF006C50.toInt()),
                    )
            }
        headerLayout.addView(iconView)

        val headerTextLayout =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

        val titleView =
            TextView(context).apply {
                text = topic.title
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
                setTypeface(null, Typeface.BOLD)
                setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt()))
            }
        headerTextLayout.addView(titleView)

        val subtitleView =
            TextView(context).apply {
                text = topic.subtitle
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF666666.toInt()))
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ).apply {
                            topMargin = dp(2)
                        }
            }
        headerTextLayout.addView(subtitleView)
        headerLayout.addView(headerTextLayout)
        root.addView(headerLayout)

        // Content Card containing scrollable markdown content
        val contentCard =
            MaterialCardView(context).apply {
                radius = 16f * density
                strokeWidth = 0
                cardElevation = 0f
                setCardBackgroundColor(
                    resolveThemeColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0xFFF4F4F4.toInt()),
                )
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            0,
                            1f,
                        ).apply {
                            bottomMargin = dp(16)
                        }
            }

        val scrollView =
            NestedScrollView(context).apply {
                layoutParams =
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
            }

        val contentTextView =
            TextView(context).apply {
                setPadding(dp(16), dp(16), dp(16), dp(16))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(resolveThemeColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0xFF333333.toInt()))
                setLineSpacing(dp(3).toFloat(), 1.2f)
            }

        // Render Markdown content using Markwon
        try {
            val markwon = Markwon.create(context)
            val markdown =
                context.assets
                    .open(topic.assetFileName)
                    .bufferedReader()
                    .use { it.readText() }
            markwon.setMarkdown(contentTextView, markdown)
        } catch (e: Exception) {
            contentTextView.text = topic.subtitle
        }

        scrollView.addView(contentTextView)
        contentCard.addView(scrollView)
        root.addView(contentCard)

        // Action Buttons Row (External Wiki Link + Close)
        val buttonRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                layoutParams =
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }

        val webButton =
            MaterialButton(context, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                text = context.getString(R.string.open_in_browser)
                layoutParams =
                    LinearLayout
                        .LayoutParams(
                            0,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            1f,
                        ).apply {
                            marginEnd = dp(8)
                        }
                setOnClickListener {
                    HapticUtils.tap(it)
                    CustomTabsHelper.launchUrlOrCopy(context, topic.webFallbackUrl)
                }
            }
        buttonRow.addView(webButton)

        val closeButton =
            MaterialButton(context, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
                text = context.getString(R.string.changelog_close)
                layoutParams =
                    LinearLayout.LayoutParams(
                        0,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        1f,
                    )
                setOnClickListener {
                    HapticUtils.tap(it)
                    dismiss()
                }
            }
        buttonRow.addView(closeButton)
        root.addView(buttonRow)

        return root
    }

    override fun onStart() {
        super.onStart()
        val d = dialog as? com.google.android.material.bottomsheet.BottomSheetDialog ?: return
        val bottomSheet = d.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet) ?: return
        val behavior =
            com.google.android.material.bottomsheet.BottomSheetBehavior
                .from(bottomSheet)
        val displayHeight = resources.displayMetrics.heightPixels
        behavior.peekHeight = (displayHeight * 0.70f).toInt()
        behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        behavior.skipCollapsed = true
        bottomSheet.layoutParams.height = (displayHeight * 0.85f).toInt()
        bottomSheet.requestLayout()
    }

    private fun resolveThemeColor(
        context: Context,
        attr: Int,
        fallback: Int,
    ): Int {
        val typedValue = TypedValue()
        return if (context.theme.resolveAttribute(attr, typedValue, true)) {
            typedValue.data
        } else {
            fallback
        }
    }

    companion object {
        const val TAG = "KnowledgebaseBottomSheet"
        private const val ARG_TOPIC_ID = "arg_topic_id"

        fun show(
            fragmentManager: FragmentManager,
            topicId: String,
        ) {
            if (fragmentManager.isStateSaved || fragmentManager.isDestroyed) return
            val existing = fragmentManager.findFragmentByTag(TAG)
            if (existing != null && existing.isAdded) return
            val sheet =
                KnowledgebaseBottomSheet().apply {
                    arguments =
                        Bundle().apply {
                            putString(ARG_TOPIC_ID, topicId)
                        }
                }
            sheet.show(fragmentManager, TAG)
        }
    }
}
