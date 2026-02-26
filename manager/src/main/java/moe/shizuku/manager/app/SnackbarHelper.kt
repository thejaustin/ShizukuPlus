package moe.shizuku.manager.app

import android.content.Context
import android.view.View
import com.google.android.material.snackbar.Snackbar
import moe.shizuku.manager.app.ThemeHelper
import moe.shizuku.manager.R

object SnackbarHelper {

    private var snackbar: Snackbar? = null

    fun show(
        context: Context,
        view: View,
        msg: String,
        duration: Int = Snackbar.LENGTH_SHORT,
        actionText: String? = null,
        action: (() -> Unit)? = null,
        onDismiss: ((event: Int) -> Unit)? = null
    ) {
        dismiss() // Dismiss any existing snackbar
        val newSnackbar = Snackbar.make(view, msg, duration).setDuration(duration)
        if (action != null) {
            newSnackbar.setAction(actionText ?: context.getString(android.R.string.ok)) { action() }
        }
        if (onDismiss != null) {
            newSnackbar.addCallback(object : Snackbar.Callback() {
                override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                    onDismiss(event)
                }
            })
        }
        ThemeHelper.applySnackbarTheme(context, newSnackbar)
        newSnackbar.show()
        snackbar = newSnackbar
    }

    fun dismiss() {
        snackbar?.dismiss()
        snackbar = null
    }

}