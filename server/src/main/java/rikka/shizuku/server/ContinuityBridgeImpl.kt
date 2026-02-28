package rikka.shizuku.server

import android.os.Bundle
import android.os.IBinder
import android.os.RemoteException
import moe.shizuku.server.IContinuityBridge

class ContinuityBridgeImpl : IContinuityBridge.Stub() {
    override fun syncData(targetDeviceId: String?, key: String?, data: Bundle?): Boolean {
        // Placeholder for cross-device state sync
        return false
    }

    override fun registerContinuityListener(listener: IBinder?) {
        // Placeholder for continuity event registration
    }

    override fun listEligibleDevices(): List<String> {
        return emptyList()
    }

    override fun requestHandoff(targetDeviceId: String?, taskState: Bundle?): Boolean {
        return false
    }
}
