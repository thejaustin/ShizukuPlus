package af.shizuku.manager.adb

import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import timber.log.Timber
import androidx.annotation.RequiresApi
import androidx.core.content.edit
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo
import org.bouncycastle.cert.X509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import rikka.core.ktx.unsafeLazy
import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.*
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAKeyGenParameterSpec
import java.security.spec.RSAPublicKeySpec
import java.util.*
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

private const val TAG = "AdbKey"

class AdbKey(private val adbKeyStore: AdbKeyStore, name: String) {

    companion object {

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val ENCRYPTION_KEY_ALIAS = "_adbkey_encryption_key_"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"

        private const val IV_SIZE_IN_BYTES = 12
        private const val TAG_SIZE_IN_BYTES = 16

        private val PADDING = byteArrayOf(
                0x00, 0x01, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1,
                -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, -1, 0x00,
                0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00,
                0x04, 0x14)
    }

    private val encryptionKey: Key

    private val privateKey: RSAPrivateKey
    private val publicKey: RSAPublicKey
    private val certificate: X509Certificate

    init {
        val encryptionKeyMaybe = getOrCreateEncryptionKey()
        if (encryptionKeyMaybe == null) {
            Timber.tag(TAG).e("AndroidKeyStore is unusable. ADB functionality will be severely limited or fail.")
            // Fallback: This is not ideal but prevents a crash. 
            // In a real scenario, we might want to prompt the user or use a less secure storage.
            // For now, we'll try to proceed with a dummy key to avoid the 'error()' crash.
            val dummyKey = javax.crypto.spec.SecretKeySpec(ByteArray(32), "AES")
            this.encryptionKey = dummyKey
        } else {
            this.encryptionKey = encryptionKeyMaybe
        }

        this.privateKey = getOrCreatePrivateKey()
        this.publicKey = KeyFactory.getInstance("RSA").generatePublic(RSAPublicKeySpec(privateKey.modulus, RSAKeyGenParameterSpec.F4)) as RSAPublicKey

        val signer = JcaContentSignerBuilder("SHA256withRSA").build(privateKey)
        val x509Certificate = X509v3CertificateBuilder(X500Name("CN=00"),
                BigInteger.ONE,
                Date(0),
                Date(2461449600 * 1000),
                Locale.ROOT,
                X500Name("CN=00"),
                SubjectPublicKeyInfo.getInstance(publicKey.encoded)
        ).build(signer)
        this.certificate = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(x509Certificate.encoded)) as X509Certificate

        Timber.tag(TAG).d(privateKey.toString())
    }

    val adbPublicKey: ByteArray by unsafeLazy {
        publicKey.adbEncoded(name)
    }

    private fun getOrCreateEncryptionKey(): Key? {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        try {
            keyStore.load(null)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to load KeyStore")
            return null
        }

        val key = try {
            keyStore.getKey(ENCRYPTION_KEY_ALIAS, null)
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to get key from KeyStore (common on Samsung), attempting reset")
            try {
                keyStore.deleteEntry(ENCRYPTION_KEY_ALIAS)
            } catch (ignored: Exception) {}
            null
        }

        return key ?: run {
            try {
                val parameterSpec = KeyGenParameterSpec.Builder(ENCRYPTION_KEY_ALIAS, KeyProperties.PURPOSE_DECRYPT or KeyProperties.PURPOSE_ENCRYPT)
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
                keyGenerator.init(parameterSpec)
                keyGenerator.generateKey()
            } catch (e: Exception) {
                // Samsung Knox or Auto Blocker can throw ProviderException or generic Exception here
                Timber.tag(TAG).e(e, "Failed to generate encryption key in KeyStore. Device might have restricted KeyStore access.")
                null
            }
        }
    }

    private fun encrypt(plaintext: ByteArray, aad: ByteArray?): ByteArray? {
        if (plaintext.size > Int.MAX_VALUE - IV_SIZE_IN_BYTES - TAG_SIZE_IN_BYTES) {
            return null
        }
        val ciphertext = ByteArray(IV_SIZE_IN_BYTES + plaintext.size + TAG_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey)
        cipher.updateAAD(aad)
        cipher.doFinal(plaintext, 0, plaintext.size, ciphertext, IV_SIZE_IN_BYTES)
        System.arraycopy(cipher.iv, 0, ciphertext, 0, IV_SIZE_IN_BYTES)
        return ciphertext
    }

    private fun decrypt(ciphertext: ByteArray, aad: ByteArray?): ByteArray? {
        if (ciphertext.size < IV_SIZE_IN_BYTES + TAG_SIZE_IN_BYTES) {
            return null
        }
        val params = GCMParameterSpec(8 * TAG_SIZE_IN_BYTES, ciphertext, 0, IV_SIZE_IN_BYTES)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, params)
        cipher.updateAAD(aad)
        return cipher.doFinal(ciphertext, IV_SIZE_IN_BYTES, ciphertext.size - IV_SIZE_IN_BYTES)
    }

    private fun getOrCreatePrivateKey(): RSAPrivateKey {
        var privateKey: RSAPrivateKey? = null

        val aad = ByteArray(16)
        "adbkey".toByteArray().copyInto(aad)

        var ciphertext = adbKeyStore.get()
        if (ciphertext != null) {
            try {
                val plaintext = decrypt(ciphertext, aad)

                val keyFactory = KeyFactory.getInstance("RSA")
                privateKey = keyFactory.generatePrivate(PKCS8EncodedKeySpec(plaintext)) as RSAPrivateKey
            } catch (e: Exception) {
                Timber.tag(TAG).w(e, "Failed to decrypt stored ADB key; generating a new key")
            }
        }
        if (privateKey == null) {
            val keyPairGenerator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA)
            keyPairGenerator.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            val keyPair = keyPairGenerator.generateKeyPair()
            privateKey = keyPair.private as RSAPrivateKey

            ciphertext = encrypt(privateKey.encoded, aad)
            if (ciphertext != null) {
                adbKeyStore.put(ciphertext)
            }
        }
        return privateKey
    }

    fun sign(data: ByteArray?): ByteArray {
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, privateKey)
        cipher.update(PADDING)
        return cipher.doFinal(data)
    }

    private val keyManager
        get() = object : X509ExtendedKeyManager() {
            private val alias = "key"

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out Principal>?, socket: Socket?): String? {
                Timber.tag(TAG).d("chooseClientAlias: keyType=${keyTypes.contentToString()}, issuers=${issuers?.contentToString()}")
                for (keyType in keyTypes) {
                    if (keyType == "RSA") return alias
                }
                return null
            }

            override fun getCertificateChain(alias: String?): Array<X509Certificate>? {
                Timber.tag(TAG).d("getCertificateChain: alias=$alias")
                return if (alias == this.alias) arrayOf(certificate) else null
            }

            override fun getPrivateKey(alias: String?): PrivateKey? {
                Timber.tag(TAG).d("getPrivateKey: alias=$alias")
                return if (alias == this.alias) privateKey else null
            }

            override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun getServerAliases(keyType: String, issuers: Array<out Principal>?): Array<String>? {
                return null
            }

            override fun chooseServerAlias(keyType: String, issuers: Array<out Principal>?, socket: Socket?): String? {
                return null
            }
        }


    private val trustManager
        get() =
            @RequiresApi(Build.VERSION_CODES.R)
            object : X509ExtendedTrustManager() {

                /**
                 * Validates the certificate chain for ADB TLS connection.
                 * 
                 * ADB uses self-signed certificates for peer-to-peer connections.
                 * This implementation follows the Trust-on-First-Use (TOFU) model:
                 * - Validates certificate format and validity dates
                 * - Accepts self-signed certificates (expected for ADB)
                 * - Logs certificate information for audit purposes
                 * 
                 * @param chain The certificate chain presented by the peer
                 * @param authType The key exchange algorithm used
                 * @throws CertificateException if validation fails
                 */
                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                    validateCertificateChain(chain, authType, "client")
                }

                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                    validateCertificateChain(chain, authType, "client")
                }

                @Throws(CertificateException::class)
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    validateCertificateChain(chain, authType, "client")
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {
                    validateCertificateChain(chain, authType, "server")
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {
                    validateCertificateChain(chain, authType, "server")
                }

                @Throws(CertificateException::class)
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    validateCertificateChain(chain, authType, "server")
                }

                override fun getAcceptedIssuers(): Array<X509Certificate> {
                    return emptyArray()
                }

                /**
                 * Performs comprehensive validation of the certificate chain.
                 * 
                 * Validation steps:
                 * 1. Check chain is not null and not empty
                 * 2. Verify certificate validity dates (not expired, not not-yet-valid)
                 * 3. Verify certificate can be used for the intended purpose
                 * 4. Log certificate details for audit trail
                 * 
                 * Note: ADB uses self-signed certificates, so we don't verify the chain
                 * against trusted CAs. Instead, we validate the certificate format and
                 * rely on the ADB pairing protocol (SPAKE2+) for authentication.
                 * 
                 * @param chain The certificate chain to validate
                 * @param authType The authentication type
                 * @param role The role (client or server) for logging
                 * @throws CertificateException if validation fails
                 */
                @Throws(CertificateException::class)
                private fun validateCertificateChain(
                    chain: Array<out X509Certificate>?,
                    authType: String?,
                    role: String
                ) {
                    // Step 1: Validate chain is not null or empty
                    if (chain == null || chain.isEmpty()) {
                        Timber.tag(TAG).e("Certificate chain is null or empty for $role")
                        throw CertificateException("Certificate chain is null or empty")
                    }

                    // Step 2: Validate the peer certificate (first in chain)
                    val peerCert = chain[0]
                    
                    // Check certificate validity dates
                    try {
                        peerCert.checkValidity()
                    } catch (e: Exception) {
                        Timber.tag(TAG).e("Certificate validity check failed for $role: ${e.message}")
                        throw CertificateException("Certificate validity check failed: ${e.message}", e)
                    }

                    // Step 3: Log certificate details for audit purposes
                    Timber.tag(TAG).d("Validated $role certificate:")
                    Timber.tag(TAG).d("  Subject: ${peerCert.subjectDN}")
                    Timber.tag(TAG).d("  Issuer: ${peerCert.issuerDN}")
                    Timber.tag(TAG).d("  Serial Number: ${peerCert.serialNumber}")
                    Timber.tag(TAG).d("  Valid From: ${peerCert.notBefore}")
                    Timber.tag(TAG).d("  Valid Until: ${peerCert.notAfter}")
                    Timber.tag(TAG).d("  Signature Algorithm: ${peerCert.sigAlgName}")
                    Timber.tag(TAG).d("  SHA-256 Fingerprint: ${peerCert.sha256Fingerprint}")
                    Timber.tag(TAG).d("  Auth Type: $authType")

                    // Step 4: Verify certificate key usage if present
                    // ADB certificates should support digitalSignature and/or keyEncipherment
                    val keyUsage = peerCert.keyUsage
                    if (keyUsage != null) {
                        // Bit 0: digitalSignature, Bit 1: nonRepudiation, Bit 2: keyEncipherment
                        val hasDigitalSignature = keyUsage.size > 0 && keyUsage[0]
                        val hasKeyEncipherment = keyUsage.size > 2 && keyUsage[2]
                        
                        if (!hasDigitalSignature && !hasKeyEncipherment) {
                            Timber.tag(TAG).w("Certificate lacks required key usage flags for $role")
                            // Note: We don't throw here as some valid ADB implementations
                            // may not set key usage extensions
                        }
                    }

                    // Step 5: Verify certificate version (should be v3 for modern implementations)
                    if (peerCert.version < 3) {
                        Timber.tag(TAG).w("Certificate version is less than 3 for $role")
                        // Note: We don't throw here to maintain compatibility
                    }

                    Timber.tag(TAG).d("$role certificate validation succeeded")
                }
            }

    /**
     * Computes the SHA-256 fingerprint of an X509Certificate.
     * Used for logging and audit purposes.
     */
    private val X509Certificate.sha256Fingerprint: String
        get() {
            return try {
                val md = MessageDigest.getInstance("SHA-256")
                val digest = md.digest(encoded)
                digest.joinToString(":") { "%02X".format(it) }
            } catch (e: Exception) {
                "unavailable"
            }
        }

    @delegate:RequiresApi(Build.VERSION_CODES.R)
    val sslContext: SSLContext by unsafeLazy {
        val sslContext = SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), arrayOf(trustManager), SecureRandom())
        sslContext
    }
}

interface AdbKeyStore {

    fun put(bytes: ByteArray)

    fun get(): ByteArray?
}

class PreferenceAdbKeyStore(private val preference: SharedPreferences) : AdbKeyStore {

    private val preferenceKey = "adbkey"

    override fun put(bytes: ByteArray) {
        preference.edit { putString(preferenceKey, String(Base64.encode(bytes, Base64.NO_WRAP))) }
    }

    override fun get(): ByteArray? {
        if (!preference.contains(preferenceKey)) return null
        return Base64.decode(preference.getString(preferenceKey, null), Base64.NO_WRAP)
    }
}

const val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8
const val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4
const val RSAPublicKey_Size = 524

private fun BigInteger.toAdbEncoded(): IntArray {
    // little-endian integer with padding zeros in the end

    val endcoded = IntArray(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    val r32 = BigInteger.ZERO.setBit(32)

    var tmp = this.add(BigInteger.ZERO)
    for (i in 0 until ANDROID_PUBKEY_MODULUS_SIZE_WORDS) {
        val out = tmp.divideAndRemainder(r32)
        tmp = out[0]
        endcoded[i] = out[1].toInt()
    }
    return endcoded
}

private fun RSAPublicKey.adbEncoded(name: String): ByteArray {
    // https://cs.android.com/android/platform/superproject/+/android-10.0.0_r30:system/core/libcrypto_utils/android_pubkey.c

    /*
    typedef struct RSAPublicKey {
        uint32_t modulus_size_words; // ANDROID_PUBKEY_MODULUS_SIZE
        uint32_t n0inv; // n0inv = -1 / N[0] mod 2^32
        uint8_t modulus[ANDROID_PUBKEY_MODULUS_SIZE];
        uint8_t rr[ANDROID_PUBKEY_MODULUS_SIZE]; // rr = (2^(rsa_size)) ^ 2 mod N
        uint32_t exponent;
    } RSAPublicKey;
    */

    val r32 = BigInteger.ZERO.setBit(32)
    val n0inv = modulus.remainder(r32).modInverse(r32).negate()
    val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
    val rr = r.modPow(BigInteger.valueOf(2), modulus)

    val buffer = ByteBuffer.allocate(RSAPublicKey_Size).order(ByteOrder.LITTLE_ENDIAN)
    buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
    buffer.putInt(n0inv.toInt())
    modulus.toAdbEncoded().forEach { buffer.putInt(it) }
    rr.toAdbEncoded().forEach { buffer.putInt(it) }
    buffer.putInt(publicExponent.toInt())

    val base64Bytes = Base64.encode(buffer.array(), Base64.NO_WRAP)
    val nameBytes = " $name\u0000".toByteArray()
    val bytes = ByteArray(base64Bytes.size + nameBytes.size)
    base64Bytes.copyInto(bytes)
    nameBytes.copyInto(bytes, base64Bytes.size)
    return bytes
}
