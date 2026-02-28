package rikka.shizuku.server

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import moe.shizuku.server.IStorageProxy
import java.io.File

class StorageProxyImpl : IStorageProxy.Stub() {
    override fun openFile(path: String?, mode: Int): ParcelFileDescriptor? {
        if (path == null) return null
        return try {
            val file = File(path)
            ParcelFileDescriptor.open(file, mode)
        } catch (e: Exception) {
            null
        }
    }

    override fun exists(path: String?): Boolean {
        if (path == null) return false
        return File(path).exists()
    }

    override fun listFiles(path: String?): List<String> {
        if (path == null) return emptyList()
        return File(path).list()?.toList() ?: emptyList()
    }

    override fun getFileInfo(path: String?): Bundle {
        val bundle = Bundle()
        if (path != null) {
            val file = File(path)
            if (file.exists()) {
                bundle.putBoolean("exists", true)
                bundle.putLong("size", file.length())
                bundle.putLong("lastModified", file.lastModified())
                bundle.putBoolean("isDirectory", file.isDirectory)
            } else {
                bundle.putBoolean("exists", false)
            }
        }
        return bundle
    }
}
