package moe.shizuku.manager.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.view.View
import android.widget.RemoteViews
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.ShizukuStateMachine

class ShizukuWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Refresh all widgets on server state changes or other events
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, ShizukuWidgetProvider::class.java))
        onUpdate(context, manager, ids)
    }

    companion object {
        fun updateAppWidget(context: Context, appWidgetManager: AppWidgetManager, appWidgetId: Int) {
            val state = ShizukuStateMachine.get()
            val isRunning = state == ShizukuStateMachine.State.RUNNING
            
            val views = RemoteViews(context.packageName, R.layout.widget_shizuku)
            
            // Set Text
            views.setTextViewText(R.id.widget_status, if (isRunning) "Running" else "Stopped")
            
            // Set Colors and Icons based on state
            if (isRunning) {
                views.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.shape_droplet_background)
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_server_ok_24dp)
                views.setViewVisibility(R.id.widget_button, View.GONE)
            } else {
                views.setInt(R.id.widget_icon, "setBackgroundResource", R.drawable.shape_circle_icon_background)
                views.setImageViewResource(R.id.widget_icon, R.drawable.ic_server_error_24dp)
                views.setViewVisibility(R.id.widget_button, View.VISIBLE)
            }

            // Main Click: Open App
            val mainIntent = Intent(context, MainActivity::class.java)
            val mainPendingIntent = PendingIntent.getActivity(context, 0, mainIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_root, mainPendingIntent)

            // Button Click: Start Service
            val startIntent = Intent(context, StarterActivity::class.java)
            val startPendingIntent = PendingIntent.getActivity(context, 1, startIntent, PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_button, startPendingIntent)

            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
}