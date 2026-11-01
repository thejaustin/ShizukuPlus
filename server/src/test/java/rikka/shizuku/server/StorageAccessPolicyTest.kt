package rikka.shizuku.server

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import rikka.shizuku.server.util.StorageAccessPolicy

class StorageAccessPolicyTest {
    private fun read(path: String) = StorageAccessPolicy.isAllowed(path, write = false, unrestricted = false)

    private fun write(path: String) = StorageAccessPolicy.isAllowed(path, write = true, unrestricted = false)

    @Test
    fun privateAppDataIsBlocked() {
        assertFalse(read("/data/data"))
        assertFalse(read("/data/data/com.example/shared_prefs/x.xml"))
        assertFalse(read("/data/user/0/com.example"))
        assertFalse(read("/data/user/10"))
        assertFalse(write("/data/adb/shizuku/config"))
    }

    @Test
    fun externalAppDirsAreBlocked() {
        assertFalse(read("/storage/emulated/0/Android/data"))
        assertFalse(read("/storage/emulated/0/Android/data/com.example/files"))
        assertFalse(write("/storage/emulated/10/Android/obb/com.example/main.obb"))
        assertFalse(read("/sdcard/Android/data/com.example"))
        assertFalse(read("/storage/self/primary/Android/obb"))
        // Shared storage is case-insensitive.
        assertFalse(read("/storage/emulated/0/android/DATA/com.example"))
    }

    @Test
    fun sharedStorageStaysOpen() {
        assertTrue(read("/storage/emulated/0/Download/a.zip"))
        assertTrue(write("/storage/emulated/0/Documents/backup"))
        assertTrue(read("/storage/emulated/0/Android/media/com.example"))
        assertTrue(read("/storage/emulated/0/Android"))
        // A folder that only starts with the same letters is not Android/data.
        assertTrue(read("/storage/emulated/0/Android/database-dumps"))
    }

    @Test
    fun apksAreReadOnly() {
        assertTrue(read("/data/app/~~abc/com.example-1/base.apk"))
        assertFalse(write("/data/app/~~abc/com.example-1/base.apk"))
        assertFalse(write("/data/app"))
    }

    @Test
    fun prefixLookalikesAreNotMatched() {
        // "/data/database" is not under "/data/data"; the allow-list in InputValidationUtils rejects it anyway.
        assertTrue(read("/data/database"))
        assertTrue(write("/data/application"))
    }

    @Test
    fun developerOptionRestoresEverything() {
        val paths = listOf("/data/data/com.example", "/storage/emulated/0/Android/data/x", "/data/app/x/base.apk")
        paths.forEach { assertTrue(StorageAccessPolicy.isAllowed(it, write = true, unrestricted = true)) }
    }
}
