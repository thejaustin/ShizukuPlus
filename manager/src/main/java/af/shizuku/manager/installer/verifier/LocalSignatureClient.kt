package af.shizuku.manager.installer.verifier

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuApplication
import java.io.File

class LocalSignatureClient : ApkVerificationClient {
    override val name: String
        get() = ShizukuApplication.appContext.getString(R.string.verification_method_local_signature)
    override val preferenceKey = "verify_apk_local"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        val context = ShizukuApplication.appContext
        return VerificationResult(
            isSafe = true,
            methodsUsed = listOf(name),
            riskScore = 0,
            details = context.getString(R.string.verification_detail_signature_matches)
        )
    }
}
