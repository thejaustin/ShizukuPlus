package af.shizuku.manager.home

import android.content.Intent
import android.text.method.LinkMovementMethod
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import af.shizuku.manager.Helps
import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeStartRootBinding
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

    private var alertDialog: AlertDialog? = null

    init {
        containerBinding.root.applySpringTouch()
        val listener = View.OnClickListener { v: View -> onStartClicked(v) }
        start.setOnClickListener(listener)
        restart.setOnClickListener(listener)
        binding.text1.movementMethod = LinkMovementMethod.getInstance()
        containerBinding.dragHandle.apply {
            visibility = View.VISIBLE
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) HomeEditMode.startDragCallback?.invoke(this@StartRootViewHolder)
                false
            }
            setOnLongClickListener { HomeEditMode.enter(); true }
        }
        containerBinding.removeBtn.setOnClickListener {
            HomeEditMode.removeCardCallback?.invoke(HomeAdapter.ID_START_ROOT)
        }
    }

    private fun onStartClicked(v: View) {
        val context = v.context
        val activity = context as? android.app.Activity ?: return
        val intent = Intent(context, StarterActivity::class.java).apply {
            putExtra(StarterActivity.EXTRA_IS_ROOT, true)
        }
        val options = android.app.ActivityOptions.makeSceneTransitionAnimation(
            activity,
            android.util.Pair.create(binding.icon, "icon_root")
        )
        activity.startActivity(intent, options.toBundle())
    }

    override fun onBind() {
        containerBinding.removeBtn.isVisible = HomeEditMode.isActive
        containerBinding.dragHandle.isVisible = HomeEditMode.isActive
        start.isEnabled = true
        restart.isEnabled = true
        val isRunning = data == true
        if (isRunning) {
            start.visibility = View.GONE
            restart.visibility = View.VISIBLE
        } else {
            start.visibility = View.VISIBLE
            restart.visibility = View.GONE
        }

        // Expressive Lottie Integration
        if (af.shizuku.manager.ShizukuSettings.isExpressiveAnimationsEnabled()) {
            val lottieView = itemView.findViewById<com.airbnb.lottie.LottieAnimationView>(R.id.lottie_start)
            if (lottieView != null) {
                try {
                    context.assets.open("lottie/button_start.json").close()
                    lottieView.visibility = View.VISIBLE
                    lottieView.setAnimation("lottie/button_start.json")
                    lottieView.playAnimation()
                    start.icon = null // Hide static icon
                } catch (e: Exception) {
                    lottieView.visibility = View.GONE
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

    override fun onRecycle() {
        super.onRecycle()
        alertDialog = null
    }
}
