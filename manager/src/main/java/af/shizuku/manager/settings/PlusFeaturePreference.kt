package af.shizuku.manager.settings

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
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
        
        // Find the title view
        val titleView = holder.findViewById(android.R.id.title) as? TextView ?: return
        
        // Reset visibility for recycled views
        titleView.parent?.let { parent ->
            if (parent is ViewGroup) {
                parent.findViewWithTag<View>("help_info_icon")?.visibility = View.GONE
                parent.findViewWithTag<View>("integration_setup_icon")?.visibility = View.GONE
            }
        }

        val parent = titleView.parent as? ViewGroup ?: return
        val iconSize = (18 * context.resources.displayMetrics.density).toInt()
        val margin = (8 * context.resources.displayMetrics.density).toInt()

        // Create or find container
        var container = parent.findViewWithTag<LinearLayout>("plus_title_container")
        if (container == null) {
            container = LinearLayout(context).apply {
                tag = "plus_title_container"
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = titleView.layoutParams
            }
            
            val index = parent.indexOfChild(titleView)
            parent.removeViewAt(index)
            
            titleView.layoutParams = LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
            )
            
            container.addView(titleView)
            parent.addView(container, index)
        }

        // Help Info Icon
        if (infoDetail != 0) {
            var infoIcon = container.findViewWithTag<ImageView>("help_info_icon")
            if (infoIcon == null) {
                infoIcon = ImageView(context).apply {
                    tag = "help_info_icon"
                    setImageResource(R.drawable.ic_help_outline_24dp)
                    val params = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginStart = margin
                    }
                    layoutParams = params
                }
                container.addView(infoIcon)
            }
            infoIcon.setOnClickListener { showHelp() }
            infoIcon.visibility = View.VISIBLE
        }

        // Integration Setup Icon
        if (integrationPackage != null) {
            var setupIcon = container.findViewWithTag<ImageView>("integration_setup_icon")
            if (setupIcon == null) {
                setupIcon = ImageView(context).apply {
                    tag = "integration_setup_icon"
                    setImageResource(R.drawable.ic_outline_open_in_new_24)
                    val params = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        marginStart = margin
                    }
                    layoutParams = params
                }
                container.addView(setupIcon)
            }
            setupIcon.setOnClickListener { launchIntegration() }
            setupIcon.visibility = View.VISIBLE
        }
        
        // Also support long-press on the whole preference for accessibility
        holder.itemView.setOnLongClickListener {
            if (integrationPackage != null) launchIntegration() else showHelp()
            true
        }
    }

    private fun launchIntegration() {
        val pkg = integrationPackage ?: return
        val pm = context.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            context.startActivity(intent)
        } else {
            android.widget.Toast.makeText(context, R.string.app_management_no_launcher, android.widget.Toast.LENGTH_SHORT).show()
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
