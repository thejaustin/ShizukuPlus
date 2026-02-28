package rikka.shizuku.server

import moe.shizuku.server.IOverlayManagerPlus

class OverlayManagerPlusImpl : IOverlayManagerPlus.Stub() {
    override fun setOverlayEnabled(packageName: String?, enabled: Boolean): Boolean {
        // Placeholder: Native call to IOverlayManager via ServiceManager
        return false
    }

    override fun setHighestPriority(packageName: String?): Boolean {
        return false
    }

    override fun getAllOverlays(): List<String> {
        return emptyList()
    }
}
