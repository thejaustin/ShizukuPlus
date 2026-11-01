package af.shizuku.manager.adb

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import javax.crypto.Cipher

class AdbTokenSignatureTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val token = ByteArray(20) { it.toByte() }

    // What an adb client sends: raw RSA over 00 01 FF..FF 00 || DigestInfo(SHA-1) || token, like AdbKey.sign.
    private fun adbSign(data: ByteArray): ByteArray {
        val digestInfo =
            byteArrayOf(0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14)
        val padLength = 256 - 3 - digestInfo.size - data.size
        val block = byteArrayOf(0x00, 0x01) + ByteArray(padLength) { -1 } + byteArrayOf(0x00) + digestInfo + data
        return Cipher.getInstance("RSA/ECB/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, keys.private)
            doFinal(block)
        }
    }

    @Test
    fun genuineAdbSignatureVerifies() {
        assertTrue(verifyAdbTokenSignature(keys.public, token, adbSign(token)))
    }

    @Test
    fun signatureOverAnotherTokenFails() {
        assertFalse(verifyAdbTokenSignature(keys.public, token, adbSign(ByteArray(20))))
    }

    @Test
    fun blockThatOnlyEndsWithTheTokenFails() {
        // The old check accepted any block ending in the token; build one with no PKCS#1 padding.
        val n = (keys.public as RSAPublicKey).modulus
        val forgedBlock = BigInteger(1, ByteArray(236) + token).mod(n)
        val forged =
            Cipher.getInstance("RSA/ECB/NoPadding").run {
                init(Cipher.ENCRYPT_MODE, keys.private)
                doFinal(forgedBlock.toByteArray().let { if (it.size > 256) it.copyOfRange(it.size - 256, it.size) else it })
            }
        assertFalse(verifyAdbTokenSignature(keys.public, token, forged))
    }

    @Test
    fun garbageFailsWithoutThrowing() {
        assertFalse(verifyAdbTokenSignature(keys.public, token, ByteArray(3)))
    }
}
