package af.shizuku.manager.backup

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object CryptoUtils {

    private const val KEY_ALIAS = "ShizukuPlusBackupKey"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "${KeyProperties.KEY_ALGORITHM_AES}/${KeyProperties.BLOCK_MODE_GCM}/${KeyProperties.ENCRYPTION_PADDING_NONE}"

    private fun keyAlias(userAuthRequired: Boolean) = if (userAuthRequired) KEY_ALIAS else "${KEY_ALIAS}_no_auth"

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
