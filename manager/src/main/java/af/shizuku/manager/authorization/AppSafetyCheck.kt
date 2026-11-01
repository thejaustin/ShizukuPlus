package af.shizuku.manager.authorization

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * The quick safety check shown when an app asks for Shizuku+ access: where it came from, who
 * signed it, and (if the user added a key) what VirusTotal and Pithus know about its APK.
 *
 * Only the APK's SHA-256 leaves the device, and only to the services the user turned on.
 * Nothing is uploaded, and an app nobody has seen is reported as unknown, not as safe.
 */
object AppSafetyCheck {
    data class LocalInfo(
        /** Installer package, or null when Android doesn't know (sideloaded via adb, etc). */
        val installer: String?,
        val installerLabel: String?,
        val signerSha256: String?,
        val firstInstallTime: Long,
        val apkPath: String?,
    )

    sealed class Verdict {
        /** Scanned, and no engine flags it. */
        data class Clean(
            val engines: Int,
        ) : Verdict()

        data class Flagged(
            val detections: Int,
            val engines: Int,
            /** A known malware family name, when the service has one. */
            val family: String? = null,
        ) : Verdict()

        /** The service has never seen this exact APK. */
        object Unknown : Verdict()

        /** The service said the key is wrong. */
        object BadKey : Verdict()

        /** Offline, timed out, or an unexpected answer. */
        object Unavailable : Verdict()
    }

    private val json = Json { ignoreUnknownKeys = true }
    private const val TIMEOUT_MS = 8_000

    fun localInfo(
        context: Context,
        packageName: String,
    ): LocalInfo? {
        val pm = context.packageManager
        val info =
            runCatching {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(
                    packageName,
                    if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES,
                )
            }.getOrNull() ?: return null
        val installer =
            runCatching {
                if (Build.VERSION.SDK_INT >= 30) {
                    pm.getInstallSourceInfo(packageName).installingPackageName
                } else {
                    @Suppress("DEPRECATION")
                    pm.getInstallerPackageName(packageName)
                }
            }.getOrNull()
        val installerLabel =
            installer?.let { pkg ->
                runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrNull()
            }
        val cert =
            if (Build.VERSION.SDK_INT >= 28) {
                info.signingInfo?.apkContentsSigners?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()
            }
        return LocalInfo(
            installer = installer,
            installerLabel = installerLabel,
            signerSha256 = cert?.toByteArray()?.let { sha256Hex(it) },
            firstInstallTime = info.firstInstallTime,
            apkPath = info.applicationInfo?.sourceDir,
        )
    }

    enum class Source { STORE, OPEN_SOURCE_STORE, FILE, OTHER_APP, UNKNOWN }

    private val STORES = setOf("com.android.vending", "com.sec.android.app.samsungapps", "com.amazon.venezia", "com.huawei.appmarket", "com.xiaomi.market", "com.heytap.market", "com.oppo.market")
    private val OPEN_SOURCE_STORES =
        setOf(
            "org.fdroid.fdroid",
            "org.fdroid.basic",
            "com.looker.droidify",
            "com.machiav3lli.fdroid",
            "in.sunilpaulmathew.izzyondroid",
            "dev.imranr.obtainium",
            "dev.imranr.obtainium.fdroid",
            "com.aurora.store",
            "org.gdroid.gdroid",
        )
    private val FILE_INSTALLERS = setOf("com.google.android.packageinstaller", "com.android.packageinstaller", "com.samsung.android.packageinstaller")

    fun source(installer: String?): Source =
        when {
            installer == null -> Source.UNKNOWN
            installer in STORES -> Source.STORE
            installer in OPEN_SOURCE_STORES || installer.startsWith("dev.imranr.obtainium") -> Source.OPEN_SOURCE_STORE
            installer in FILE_INSTALLERS -> Source.FILE
            else -> Source.OTHER_APP
        }

    fun sha256Of(file: File): String? =
        runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            digest.digest().toHex()
        }.getOrNull()

    /** VirusTotal v3 file report. Blocking: call off the main thread. */
    fun virusTotal(
        apiKey: String,
        sha256: String,
    ): Verdict {
        val (code, body) = get("https://www.virustotal.com/api/v3/files/$sha256", "x-apikey" to apiKey) ?: return Verdict.Unavailable
        return when (code) {
            200 -> body?.let { parseVirusTotal(it) } ?: Verdict.Unavailable
            404 -> Verdict.Unknown
            401, 403 -> Verdict.BadKey
            else -> Verdict.Unavailable
        }
    }

    /** Pithus report; it carries its own VirusTotal stats and any MalwareBazaar match. Blocking. */
    fun pithus(
        apiKey: String,
        sha256: String,
    ): Verdict {
        val base = "https://beta.pithus.org/api/report/$sha256"
        // The existence check needs no key, so a key is only sent for APKs Pithus actually has.
        val (existsCode, _) = get("$base/exists") ?: return Verdict.Unavailable
        if (existsCode == 404) return Verdict.Unknown
        if (existsCode != 200) return Verdict.Unavailable
        val (code, body) = get(base, "Authorization" to "Token $apiKey") ?: return Verdict.Unavailable
        return when (code) {
            200 -> body?.let { parsePithus(it) } ?: Verdict.Unavailable
            401, 403 -> Verdict.BadKey
            else -> Verdict.Unavailable
        }
    }

    internal fun parseVirusTotal(body: String): Verdict? =
        runCatching {
            val stats =
                json
                    .parseToJsonElement(body)
                    .jsonObject["data"]!!
                    .jsonObject["attributes"]!!
                    .jsonObject["last_analysis_stats"]!!
                    .jsonObject
            verdictFromStats(stats, family = null)
        }.getOrNull()

    internal fun parsePithus(body: String): Verdict? =
        runCatching {
            val report = json.parseToJsonElement(body).jsonObject["report"]!!.jsonObject
            val family =
                (report["malware_bazaar"] as? JsonObject)
                    ?.let { it["signature"]?.jsonPrimitive?.contentOrNull }
                    ?.takeIf { it.isNotBlank() }
            val stats = report["vt"] as? JsonObject
            when {
                stats != null -> verdictFromStats(stats, family)
                family != null -> Verdict.Flagged(1, 1, family)
                // Analyzed by Pithus, but no scanner results to count.
                else -> Verdict.Unknown
            }
        }.getOrNull()

    private fun verdictFromStats(
        stats: JsonObject,
        family: String?,
    ): Verdict {
        fun n(key: String) = stats[key]?.jsonPrimitive?.intOrNull ?: 0
        val detections = n("malicious") + n("suspicious")
        val engines = detections + n("undetected") + n("harmless")
        return when {
            detections > 0 || family != null -> Verdict.Flagged(detections, engines, family)
            engines == 0 -> Verdict.Unknown
            else -> Verdict.Clean(engines)
        }
    }

    private fun get(
        url: String,
        vararg headers: Pair<String, String>,
    ): Pair<Int, String?>? =
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = TIMEOUT_MS
                conn.readTimeout = TIMEOUT_MS
                conn.setRequestProperty("Accept", "application/json")
                headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                val code = conn.responseCode
                val body = if (code == 200) conn.inputStream.bufferedReader().use { it.readText() } else null
                code to body
            } finally {
                conn.disconnect()
            }
        }.getOrNull()

    private fun sha256Hex(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
}
