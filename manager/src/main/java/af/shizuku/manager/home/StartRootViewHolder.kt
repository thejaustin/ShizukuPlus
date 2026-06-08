package af.shizuku.manager.home

import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import androidx.core.view.isVisible
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartRootBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.ktx.toHtml
import af.shizuku.manager.starter.StarterActivity
import rikka.html.text.HtmlCompat
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

import af.shizuku.manager.utils.MotionUtils.applySpringTouch

class StartRootViewHolder(
    private val binding: HomeStartRootBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<Boolean>(containerBinding.root) {

    companion object {
        val CREATOR = Creator<Boolean> { inflater: LayoutInflater, parent: ViewGroup? ->
            val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
            val inner = HomeStartRootBinding.inflate(inflater, outer.cardContent, true)
            StartRootViewHolder(inner, outer)
        }
    }

    private inline val start get() = binding.button1
    private inline val restart get() = binding.button2

    private var lottieAvailable: Boolean? = null

    init {
        containerBinding.root.applySpringTouch()
        containerBinding.root.setOnLongClickListener { HomeEditMode.enter(); true }
        val listener = View.OnClickListener { v: View -> onStartClicked(v) }
        start.setOnClickListener(listener)
        restart.setOnClickListener(listener)
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
        containerBinding.dragHandle.apply {
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartRootViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }

    }

    private fun onStartClicked(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        val isRooted = af.shizuku.manager.utils.EnvironmentUtils.isRooted()
        val isSystem = af.shizuku.manager.ShizukuSettings.isSamsungSystemUidEscalationEnabled() && !isRooted
        val intent = Intent(activity, StarterActivity::class.java).apply {
            if (isSystem) {
                putExtra(StarterActivity.EXTRA_IS_SYSTEM, true)
            } else {
                putExtra(StarterActivity.EXTRA_IS_ROOT, true)
            }
        }
        activity.startWithSceneTransition(intent, binding.icon, "icon_root")
    }

    override fun onBind() {
        HomeEditMode.applyOverlay(containerBinding)
        start.isEnabled = true
        restart.isEnabled = true
        val isRunning = data == true
        start.isVisible = !isRunning
        restart.isVisible = isRunning

        // Expressive Lottie Integration — probe asset once and cache result.
        if (af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
            val lottieView = itemView.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottie_start)
            if (lottieView != null) {
                val available = lottieAvailable ?: runCatching {
                    context.assets.open("lottie/button_start.json").close(); true
                }.getOrDefault(false).also { lottieAvailable = it }
                lottieView.isVisible = available
                if (available) {
                    lottieView.setAnimation("lottie/button_start.json")
                    lottieView.playAnimation()
                    start.icon = null
                }
            }
        }

        val sb = StringBuilder()
            .append(
                context.getString(
                    R.string.home_root_description,
                    "<b><a href=\"${Helps.SUI.get()}\">Sui</a></b>",
                    "Sui"
                )
            )

        binding.text1.text = sb.toHtml(HtmlCompat.FROM_HTML_OPTION_TRIM_WHITESPACE)
    }
}
