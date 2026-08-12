package af.shizuku.manager.installer.verifier

import af.shizuku.manager.R
import af.shizuku.manager.ShizukuApplication
import af.shizuku.manager.ShizukuSettings
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class PithusClient : ApkVerificationClient {
    override val name: String
        get() = ShizukuApplication.appContext.getString(R.string.verification_method_pithus)
    override val preferenceKey = "verify_apk_pithus"

    override suspend fun verifyApk(apkFile: File, sha256: String): VerificationResult {
        val context = ShizukuApplication.appContext
        return try {
            val apiKey = ShizukuSettings.getPithusApiKey()
            val url = URL("https://beta.pithus.org/api/beta/search/hash/$sha256/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.requestMethod = "GET"
            if (apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Token $apiKey")
            }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_NOT_FOUND) {
                conn.disconnect()
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = context.getString(R.string.verification_detail_hash_not_found))
            }
            if (code != HttpURLConnection.HTTP_OK) {
                conn.disconnect()
                return VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                    details = context.getString(R.string.verification_detail_http_skipped, code))
            }
            val body = conn.inputStream.use { it.bufferedReader().readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val riskScore = json.optInt("risk_score", 0)
            val isSafe = riskScore < 50
            VerificationResult(
                isSafe = isSafe,
                methodsUsed = listOf(name),
                riskScore = riskScore,
                details = context.getString(R.string.verification_detail_risk_score, riskScore)
            )
        } catch (e: Exception) {
            VerificationResult(isSafe = true, methodsUsed = listOf(name), riskScore = 0,
                details = context.getString(
                    R.string.verification_detail_lookup_failed,
                    e.message ?: e.javaClass.simpleName
                ))
        }
    }
}
