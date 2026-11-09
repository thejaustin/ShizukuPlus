package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.databinding.HomeManageAppsItemBinding
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.management.ApplicationManagementActivity
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.IconStyleHelper
import af.shizuku.manager.utils.MotionUtils.applySpringTouch
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class ManageAppsViewHolder(
    private val binding: HomeManageAppsItemBinding,
    root: View,
) : BaseViewHolder<Pair<ServiceStatus, Int?>>(root),
    View.OnClickListener {
    companion object {
        val CREATOR =
            Creator<Pair<ServiceStatus, Int?>> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeManageAppsItemBinding.inflate(inflater, outer.cardContent, true)
                ManageAppsViewHolder(inner, outer.root)
            }
    }

    init {
        root.setOnClickListener(this)
        root.applySpringTouch()
    }

    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2
    private inline val iconView get() = binding.icon
    private val originalIcon = binding.icon.drawable

    override fun onBind() {
        val context = itemView.context
        (itemView as? com.google.android.material.card.MaterialCardView)?.let {
            af.shizuku.manager.utils.CardBorder
                .apply(it)
        }
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_manage_apps")

        if (!data.first.isRunning) {
            itemView.isEnabled = false
            title.setText(R.string.home_app_management_title)
            summary.text =
                context.getString(
                    R.string.home_status_service_not_running,
                    context.getString(R.string.app_name),
                )
        } else {
            itemView.isEnabled = true
            val count = data.second
            title.text =
                if (count == null) {
                    context.getString(R.string.home_app_management_title)
                } else {
                    context.resources.getQuantityString(
                        R.plurals.home_app_management_authorized_apps_count,
                        count,
                        count,
                    )
                }
            summary.text = context.getString(R.string.home_app_management_view_authorized_apps)
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        activity.startWithSceneTransition(
            Intent(activity, ApplicationManagementActivity::class.java),
        )
    }
}
