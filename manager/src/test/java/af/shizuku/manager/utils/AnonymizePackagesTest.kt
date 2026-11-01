package af.shizuku.manager.utils

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AnonymizePackagesTest {
    @Test
    fun otherAppsAreNumberedEverywhere() {
        val text = "[19:34] ru.zdevs.zarchiver: Connected\nnewProcess: in.hridayan.ashell -> id\nru.zdevs.zarchiver again"
        assertEquals(
            "[19:34] app 1: Connected\nnewProcess: app 2 -> id\napp 1 again",
            anonymizePackages(text, listOf("ru.zdevs.zarchiver", "in.hridayan.ashell")),
        )
    }

    @Test
    fun onlyWholeNamesMatch() {
        val text = "com.example and com.example.plugin and xcom.example, end com.example."
        assertEquals(
            "app 1 and app 2 and xcom.example, end app 1.",
            anonymizePackages(text, listOf("com.example", "com.example.plugin")),
        )
    }

    @Test
    fun unrelatedTextIsUntouched() {
        assertEquals("android.widget.PopupWindow", anonymizePackages("android.widget.PopupWindow", listOf("com.example")))
    }
}
