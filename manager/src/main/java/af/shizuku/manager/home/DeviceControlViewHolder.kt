package af.shizuku.manager.home

import af.shizuku.manager.R
import af.shizuku.manager.app.SnackbarHelper
import af.shizuku.manager.databinding.HomeDeviceControlItemBinding
import af.shizuku.manager.databinding.HomeItemContainerBinding
import af.shizuku.manager.devicecontrol.DeviceControlActivity
import af.shizuku.manager.ktx.startWithSceneTransition
import af.shizuku.manager.model.ServiceStatus
import af.shizuku.manager.utils.HapticUtils
import af.shizuku.manager.utils.IconStyleHelper
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import rikka.core.content.asActivity
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

class DeviceControlViewHolder(
    private val binding: HomeDeviceControlItemBinding,
    private val containerBinding: HomeItemContainerBinding,
) : BaseViewHolder<ServiceStatus>(containerBinding.root),
    View.OnClickListener {
    companion object {
        val CREATOR =
            Creator<ServiceStatus> { inflater: LayoutInflater, parent: ViewGroup? ->
                val outer = HomeItemContainerBinding.inflate(inflater, parent, false)
                val inner = HomeDeviceControlItemBinding.inflate(inflater, outer.cardContent, true)
                DeviceControlViewHolder(inner, outer)
            }
    }

    init {
        containerBinding.root.setOnClickListener(this)
        HomeEditMode.bindCardGestures(containerBinding, this)
    }

    private val originalIcon = binding.icon.drawable

    private inline val title get() = binding.text1
    private inline val summary get() = binding.text2
    private inline val iconView get() = binding.icon

    override fun onBind() {
        val context = itemView.context
        HomeEditMode.applyOverlay(containerBinding)
        IconStyleHelper.applyToCardIcon(iconView, originalIcon, "home_device_control")
        if (!data.isRunning) {
            title.setText(R.string.home_device_control_title)
            summary.text =
                context.getString(
                    R.string.home_status_service_not_running,
                    context.getString(R.string.app_name),
                )
        } else {
            title.setText(R.string.home_device_control_title)
            summary.setText(R.string.home_device_control_summary)
        }
    }

    override fun onClick(v: View) {
        val activity = v.context.asActivity<android.app.Activity>() ?: return
        if (!data.isRunning) {
            HapticUtils.tap(v)
            SnackbarHelper.show(
                activity,
                v,
                activity.getString(R.string.home_status_service_not_running, activity.getString(R.string.app_name)),
            )
            return
        }
        activity.startWithSceneTransition(
            Intent(activity, DeviceControlActivity::class.java),
        )
    }
}
