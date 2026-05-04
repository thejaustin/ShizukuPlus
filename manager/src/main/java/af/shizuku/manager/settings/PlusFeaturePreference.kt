package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.widget.TextView
import androidx.preference.PreferenceViewHolder
import androidx.preference.SwitchPreferenceCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import af.shizuku.manager.R

class PlusFeaturePreference(context: Context, attrs: AttributeSet) : SwitchPreferenceCompat(context, attrs) {

    private val infoTitle: Int
    private val infoDetail: Int
    private var integrationPackage: String? = null
    private var integrationAppName: String? = null

    init {
        val a = context.obtainStyledAttributes(attrs, R.styleable.PlusFeaturePreference)
        infoTitle = a.getResourceId(R.styleable.PlusFeaturePreference_infoTitle, 0)
        infoDetail = a.getResourceId(R.styleable.PlusFeaturePreference_infoDetail, 0)
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

        if (titleView != null && infoDetail != 0) {
            val iconRes = if (integrationPackage != null)
                R.drawable.ic_outline_open_in_new_24
            else
                R.drawable.ic_help_outline_24

            val drawable = context.getDrawable(iconRes)
            val iconSize = (18 * context.resources.displayMetrics.density).toInt()
            drawable?.setBounds(0, 0, iconSize, iconSize)
            titleView.setCompoundDrawablesRelative(null, null, drawable, null)
            titleView.compoundDrawablePadding =
                (4 * context.resources.displayMetrics.density).toInt()
        } else {
            titleView?.setCompoundDrawablesRelative(null, null, null, null)
        }

        holder.itemView.setOnLongClickListener {
            if (integrationPackage != null) launchIntegration() else showHelp()
            true
        }
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

    private fun showHelp() {
        if (infoDetail != 0) {
            MaterialAlertDialogBuilder(context)
                .setTitle(if (infoTitle != 0) infoTitle else R.string.settings_plus_learn_more)
                .setMessage(infoDetail)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }
}
