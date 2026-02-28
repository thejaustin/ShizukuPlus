package rikka.shizuku.server

import android.os.Bundle
import android.os.RemoteException
import moe.shizuku.server.IVirtualMachineManager

class VirtualMachineManagerImpl : IVirtualMachineManager.Stub() {
    override fun create(name: String?, config: Bundle?): Boolean {
        // Placeholder for AVF VM creation logic
        return false
    }

    override fun start(name: String?): Boolean {
        return false
    }

    override fun stop(name: String?): Boolean {
        return false
    }

    override fun delete(name: String?): Boolean {
        return false
    }

    override fun getStatus(name: String?): String {
        return "Unknown"
    }

    override fun list(): List<String> {
        return emptyList()
    }
}
