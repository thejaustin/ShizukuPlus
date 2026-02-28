package rikka.shizuku.server

import moe.shizuku.server.INetworkGovernorPlus

class NetworkGovernorPlusImpl : INetworkGovernorPlus.Stub() {
    override fun setPrivateDns(mode: String?, hostname: String?): Boolean {
        // Placeholder: Modify Settings.Global or invoke INetworkManagementService
        return false
    }

    override fun restrictAppNetwork(packageName: String?, restricted: Boolean): Boolean {
        // Placeholder: Route through INetworkPolicyManager (iptables bridge)
        return false
    }

    override fun isAppNetworkRestricted(packageName: String?): Boolean {
        return false
    }
}
