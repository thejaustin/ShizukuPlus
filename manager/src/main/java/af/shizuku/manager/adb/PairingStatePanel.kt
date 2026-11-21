package af.shizuku.manager.adb

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuSettings
import af.shizuku.manager.databinding.PairingStatePanelBinding
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MorphShapeDrawable
import af.shizuku.manager.utils.springTo
import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.core.view.isVisible
import com.google.android.material.color.MaterialColors

/** How a pairing state reads: decides the icon tile's colour and the status word next to it. */
enum class PairingTone(
    @StringRes val word: Int,
) {
    NEEDS_YOU(R.string.pairing_v2_word_needs_you),
    WORKING(R.string.pairing_v2_word_working),
    PROBLEM(R.string.pairing_v2_word_problem),
    DONE(R.string.pairing_v2_word_done),
}

class PairingAction(
    @StringRes val label: Int,
    val run: () -> Unit,
)

/**
 * One designed pairing state. [key] names the situation: a new key cross-fades the panel in, and
 * entering a DONE or PROBLEM state plays its haptic once; the same key again only refreshes text.
 */
data class PairingStateSpec(
    val key: String,
    @DrawableRes val icon: Int,
    val tone: PairingTone,
    val title: CharSequence,
    val text: CharSequence?,
    val primary: PairingAction? = null,
    val secondary: PairingAction? = null,
    val busy: Boolean = tone == PairingTone.WORKING,
)

/**
 * Renders [PairingStateSpec]s into pairing_state_panel. [inline] drops the card (inside a dialog,
 * which is already a surface) and shows both actions tonal, since the dialog's own OK is its one
 * filled action.
 */
class PairingStatePanel private constructor(
    val binding: PairingStatePanelBinding,
    private val inline: Boolean,
) {
    companion object {
        fun inflate(
            parent: ViewGroup,
            inline: Boolean = false,
        ): PairingStatePanel {
            val binding = PairingStatePanelBinding.inflate(LayoutInflater.from(parent.context), parent, true)
            return PairingStatePanel(binding, inline)
        }

        private const val FADE_MS = 180L
        private const val SHAKE_MS = 360L
    }

    private var lastKey: String? = null
    private var lastTone: PairingTone? = null

    init {
        if (inline) {
            binding.stateCard.setCardBackgroundColor(Color.TRANSPARENT)
            binding.stateCard.setContentPadding(0, 0, 0, 0)
            // The dialog's own OK is the filled action; both panel actions stay tonal there.
            binding.statePrimary.backgroundTintList =
                android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(binding.statePrimary, com.google.android.material.R.attr.colorSecondaryContainer),
                )
            binding.statePrimary.setTextColor(
                MaterialColors.getColor(binding.statePrimary, com.google.android.material.R.attr.colorOnSecondaryContainer),
            )
        }
        binding.root.isVisible = false
    }

    val isShowing: Boolean get() = binding.root.isVisible

    fun render(spec: PairingStateSpec?) {
        val root = binding.root
        if (spec == null) {
            lastKey = null
            lastTone = null
            root.isVisible = false
            return
        }
        val context = root.context
        val changed = spec.key != lastKey
        val toneChanged = spec.tone != lastTone

        binding.stateWord.setText(spec.tone.word)
        binding.stateTitle.text = spec.title
        binding.stateText.text = spec.text
        binding.stateText.isVisible = !spec.text.isNullOrEmpty()
        bindAction(binding.statePrimary, spec.primary)
        bindAction(binding.stateSecondary, spec.secondary)
        binding.stateActions.isVisible = spec.primary != null || spec.secondary != null
        // Two buttons side by side don't fit at large font sizes or on narrow windows; stack them.
        val stacked = context.resources.configuration.fontScale >= 1.3f || context.resources.configuration.screenWidthDp < 360
        binding.stateActions.orientation = if (stacked) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        (binding.statePrimary.layoutParams as LinearLayout.LayoutParams).apply {
            marginStart = if (stacked) 0 else (8 * context.resources.displayMetrics.density).toInt()
            width = if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        }
        (binding.stateSecondary.layoutParams as LinearLayout.LayoutParams).width =
            if (stacked) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT
        if (stacked) {
            // Primary first when stacked, so the main action is on top.
            if (binding.stateActions.indexOfChild(binding.statePrimary) != 0) {
                binding.stateActions.removeView(binding.statePrimary)
                binding.stateActions.addView(binding.statePrimary, 0)
            }
        } else if (binding.stateActions.indexOfChild(binding.statePrimary) != binding.stateActions.childCount - 1) {
            binding.stateActions.removeView(binding.statePrimary)
            binding.stateActions.addView(binding.statePrimary)
        }

        if (toneChanged || changed) bindIcon(spec)
        binding.stateProgress.isVisible = spec.busy
        binding.stateIcon.drawable?.alpha = if (spec.busy) 0 else 255

        val wasShowing = root.isVisible
        root.isVisible = true
        if (changed) {
            val previousTone = lastTone
            lastKey = spec.key
            lastTone = spec.tone
            crossFade(wasShowing)
            when (spec.tone) {
                PairingTone.DONE -> {
                    if (previousTone != PairingTone.DONE) {
                        HapticUtils.success(root)
                        (binding.stateIcon.background as? MorphShapeDrawable)?.let {
                            it.progress = 0f
                            it.springTo(1f)
                        }
                    }
                }
                PairingTone.PROBLEM -> {
                    HapticUtils.error(root)
                    shake(binding.stateIcon)
                }
                else -> Unit
            }
        }
    }

    private fun bindAction(
        button: com.google.android.material.button.MaterialButton,
        action: PairingAction?,
    ) {
        button.isVisible = action != null
        if (action == null) {
            button.setOnClickListener(null)
            return
        }
        button.setText(action.label)
        button.setOnClickListener {
            HapticUtils.tap(it)
            action.run()
        }
    }

    private fun bindIcon(spec: PairingStateSpec) {
        val context = binding.root.context
        val (pillAttr, onPillAttr) =
            when (spec.tone) {
                PairingTone.DONE -> androidx.appcompat.R.attr.colorPrimary to com.google.android.material.R.attr.colorOnPrimary
                PairingTone.PROBLEM -> com.google.android.material.R.attr.colorErrorContainer to com.google.android.material.R.attr.colorOnErrorContainer
                PairingTone.NEEDS_YOU ->
                    com.google.android.material.R.attr.colorTertiaryContainer to com.google.android.material.R.attr.colorOnTertiaryContainer
                PairingTone.WORKING ->
                    com.google.android.material.R.attr.colorSecondaryContainer to com.google.android.material.R.attr.colorOnSecondaryContainer
            }
        val pill = MaterialColors.getColor(binding.root, pillAttr)
        val onPill = MaterialColors.getColor(binding.root, onPillAttr)
        binding.stateIcon.setImageDrawable(
            androidx.core.content.ContextCompat
                .getDrawable(context, spec.icon)
                ?.mutate(),
        )
        IconStyleHelper.applyToStatusCardIcon(binding.stateIcon, pillColor = pill, tintColor = onPill)
        // Like Home's status icon: a plain circle until pairing is done, then the user's chosen
        // expressive shape (the morph plays in render()).
        (binding.stateIcon.background as? MorphShapeDrawable)?.progress = if (spec.tone == PairingTone.DONE) 1f else 0f
        binding.stateProgress.setIndicatorColor(onPill)
        val wordColor =
            MaterialColors.getColor(
                binding.root,
                if (spec.tone == PairingTone.PROBLEM) androidx.appcompat.R.attr.colorError else androidx.appcompat.R.attr.colorPrimary,
            )
        binding.stateWord.setTextColor(wordColor)
    }

    // Reduced motion (animations off) skips straight to the new state.
    private fun crossFade(wasShowing: Boolean) {
        val content = binding.stateContent
        val duration = ShizukuSettings.scaledAnimationDuration(FADE_MS)
        content.animate().cancel()
        if (duration <= 0L) {
            content.alpha = 1f
            return
        }
        content.alpha = if (wasShowing) 0.2f else 0f
        content
            .animate()
            .alpha(1f)
            .setDuration(duration)
            .start()
    }

    private fun shake(view: View) {
        val duration = ShizukuSettings.scaledAnimationDuration(SHAKE_MS)
        if (duration <= 0L) return
        val d = view.resources.displayMetrics.density
        android.animation.ObjectAnimator
            .ofFloat(view, View.TRANSLATION_X, 0f, -6 * d, 6 * d, -4 * d, 4 * d, -2 * d, 0f)
            .setDuration(duration)
            .start()
    }
}
