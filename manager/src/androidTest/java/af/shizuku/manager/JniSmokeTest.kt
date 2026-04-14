package af.shizuku.manager

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented JNI smoke tests.
 *
 * These run on a real Android device or emulator and verify that libadb.so loads
 * correctly and all native methods are registered. The most common failure mode is
 * a stale class path in JNI_OnLoad after a package rename: FindClass returns null,
 * RegisterNatives aborts the process with SIGABRT, and the app crashes on launch.
 *
 * If libadb_loads_without_crash passes, the JNI_OnLoad + RegisterNatives path is
 * confirmed healthy. If it SEGFAULTs or SIGABRTs, this test suite fails in CI and
 * blocks the release.
 */
@RunWith(AndroidJUnit4::class)
class JniSmokeTest {

    /**
     * Loads libadb.so, triggering JNI_OnLoad which calls RegisterNatives for
     * PairingContext. If the class path is wrong, the process aborts with SIGABRT
     * before this method returns — the test runner reports it as a process crash.
     */
    @Test
    fun libadb_loads_without_crash() {
        System.loadLibrary("adb")
    }

    /**
     * Verifies that nativeConstructor is actually registered (not just that the
     * library loaded). An UnsatisfiedLinkError here means RegisterNatives ran but
     * the method signature or class path didn't match.
     */
    @Test
    fun PairingContext_nativeConstructor_is_linked() {
        System.loadLibrary("adb")

        try {
            val outerClass = Class.forName("af.shizuku.manager.adb.AdbPairingClient")
            val pairingContextClass = outerClass.declaredClasses
                .firstOrNull { it.simpleName == "PairingContext" }
            assertNotNull(
                "PairingContext inner class not found — check AdbPairingClient structure",
                pairingContextClass
            )

            // Find nativeConstructor in PairingContext.Companion
            val companionClass = pairingContextClass!!.declaredClasses
                .firstOrNull { it.simpleName == "Companion" }
            assertNotNull("PairingContext.Companion not found", companionClass)

            val nativeMethod = companionClass!!.declaredMethods
                .firstOrNull { it.name == "nativeConstructor" }
            assertNotNull(
                "nativeConstructor not found in PairingContext.Companion — " +
                "JNI method table may be mismatched",
                nativeMethod
            )

            // Actually invoke the native method. If RegisterNatives mapped it to the
            // wrong JNI function, this will throw UnsatisfiedLinkError or crash.
            nativeMethod!!.isAccessible = true
            val companionField = pairingContextClass.getDeclaredField("Companion")
                .also { it.isAccessible = true }
            val companionInstance = companionField.get(null)

            // Returns a native pointer (>0) on success, 0 on alloc failure.
            // Either way, reaching this line means the JNI call worked.
            nativeMethod.invoke(companionInstance, true, "smoketest".toByteArray())

        } catch (e: UnsatisfiedLinkError) {
            fail("nativeConstructor is not linked — RegisterNatives may have failed: ${e.message}")
        }
    }
}
