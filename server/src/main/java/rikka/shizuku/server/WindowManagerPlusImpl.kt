package rikka.shizuku.server

import android.graphics.Rect
import android.os.Bundle
import android.os.RemoteException
import moe.shizuku.server.IWindowManagerPlus

class WindowManagerPlusImpl : IWindowManagerPlus.Stub() {
    override fun forceResizable(packageName: String?, enabled: Boolean) {
        // Placeholder for forcing free-form mode
    }

    override fun pinToRegion(taskId: Int, region: Rect?) {
        // Placeholder for Desktop Mode pinning
    }

    override fun setAsBubble(taskId: Int, enabled: Boolean) {
        // Placeholder for universal bubble forcing
    }

    override fun configureBubbleBar(settings: Bundle?) {
        // Placeholder for Android 17 Bubble Bar config
    }

    override fun setAlwaysOnTop(taskId: Int, enabled: Boolean) {
        // Placeholder for privileged always-on-top
    }
}
