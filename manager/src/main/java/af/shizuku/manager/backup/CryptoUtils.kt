package af.shizuku.manager.backup

import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtils {

    // The auth-required alias is versioned (_v2): keys created before the device-credential fix
    // (#332/#315) were biometric-only, so a password/PIN-locked device could pass canAuthenticate,
    // authenticate with its credential, yet the credential couldn't unlock the biometric-only key -
    // surfacing as IllegalBlockSizeException("Key user not authenticated") at doFinal. Those old
    // keys can't be "upgraded" in place, so v2 forces a fresh, device-credential-capable key for
    // everyone. Cost: auth-protected backups made before this fix can't be restored (re-create
    // them); acceptable since that path was largely broken on non-biometric devices anyway.
    private const val KEY_ALIAS_AUTH = "ShizukuPlusBackupKey_v2"
    private const val KEY_ALIAS_NO_AUTH = "ShizukuPlusBackupKey_no_auth"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

    private fun keyAlias(userAuthRequired: Boolean) = if (userAuthRequired) KEY_ALIAS_AUTH else KEY_ALIAS_NO_AUTH

    fun getOrCreateSecretKey(userAuthRequired: Boolean): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
        keyStore.load(null)

        val alias = keyAlias(userAuthRequired)

        try {
            if (keyStore.containsAlias(alias)) {
                val key = keyStore.getKey(alias, null) as? SecretKey
                if (key != null) return key
            }
        } catch (e: Exception) {
            try { keyStore.deleteEntry(alias) } catch (ignored: Exception) {}
        }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            
        if (userAuthRequired) {
            builder.setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Accept EITHER a biometric or the device credential (PIN/pattern/password), matching
                // what BiometricLock allows. Without this the key defaults to biometric-only, so a
                // password-locked device with no biometric enrolled can never unlock it (#315/#332).
                // Timeout 0 = authenticate per use, keeping the existing CryptoObject flow intact.
                builder.setUserAuthenticationParameters(
                    0,
                    KeyProperties.AUTH_BIOMETRIC_STRONG or KeyProperties.AUTH_DEVICE_CREDENTIAL
                )
            }
            // Pre-R keeps the legacy biometric-only CryptoObject binding (BiometricLock already
            // forces BIOMETRIC_STRONG when a CryptoObject is passed below API 30).
        }

        keyGenerator.init(builder.build())
        return keyGenerator.generateKey()
    }

    private fun deleteKey(userAuthRequired: Boolean) {
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            keyStore.deleteEntry(keyAlias(userAuthRequired))
        } catch (ignored: Exception) {}
    }

    fun getCipherForEncryption(userAuthRequired: Boolean): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        try {
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(userAuthRequired))
        } catch (e: KeyPermanentlyInvalidatedException) {
            // Android invalidates the key by design when the device's biometrics/screen-lock
            // change (#332). Safe to regenerate here since we're encrypting a brand-new backup,
            // not trying to decrypt existing ciphertext tied to the old key material.
            deleteKey(userAuthRequired)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateSecretKey(userAuthRequired))
        }
        return cipher
    }

    fun getCipherForDecryption(iv: ByteArray, userAuthRequired: Boolean): Cipher {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        val spec = GCMParameterSpec(128, iv)
        // No auto-regeneration here: an invalidated key's material is gone for good, so a fresh
        // key could never decrypt ciphertext written under the old one. Let it throw - the
        // caller shows KeyPermanentlyInvalidatedException with a message explaining the backup
        // is unrecoverable, rather than silently producing a cipher that can't decrypt anything.
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateSecretKey(userAuthRequired), spec)
        return cipher
    }
}
