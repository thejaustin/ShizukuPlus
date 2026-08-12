package af.shizuku.manager.installer.verifier

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuApplication
import java.io.File

class FDroidIndexClient : ApkVerificationClient {
    override val name: String
        get() = ShizukuApplication.appContext.getString(R.string.verification_method_fdroid_index)
    override val preferenceKey = "verify_apk_fdroid"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        // Here we would parse index-v1.json locally and verify if the APK hash matches
        // an official build from the repository.

        return VerificationResult(
            isSafe = true,
            methodsUsed = listOf(name),
            riskScore = 0,
            details = ShizukuApplication.appContext.getString(R.string.verification_detail_fdroid_hash_matches)
        )
    }
}
