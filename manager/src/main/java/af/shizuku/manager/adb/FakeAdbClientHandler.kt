package af.shizuku.manager.adb

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.util.Base64
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuRemoteProcess
import timber.log.Timber
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.security.Signature
import java.security.interfaces.RSAPublicKey
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

class FakeAdbClientHandler(
    private val context: Context,
    private val socket: Socket
) {
    companion object {
        private const val TAG = "FakeAdbClient"
        private const val PREFS_NAME = "fake_adb_keys"
        private const val MAX_ADB_PAYLOAD_SIZE = 16 * 1024 * 1024 // 16MB, well above real adb's ~1MB max payload

        // Multiplexing
        private val localIdCounter = AtomicInteger(1)
    }

    private val inputStream = DataInputStream(socket.getInputStream())
    private val outputStream = DataOutputStream(socket.getOutputStream())

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // We only support one active session per connection, but could support multiple
    private val activeProcesses = ConcurrentHashMap<Int, ShizukuRemoteProcess>()

    fun loop() {
        // Wait for CNXN
        var msg = readMessage()
        if (msg.command != AdbProtocol.A_CNXN) {
            Timber.tag(TAG).e("Expected CNXN, got ${msg.command}")
            return
        }

        // Send AUTH(TOKEN)
        val token = ByteArray(20)
        java.security.SecureRandom().nextBytes(token)
        writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token))

        var authenticated = false
        while (!authenticated) {
            msg = readMessage()
            if (msg.command == AdbProtocol.A_AUTH) {
                if (msg.arg0 == AdbProtocol.ADB_AUTH_SIGNATURE) {
                    val signature = msg.data!!
                    if (verifySignature(token, signature)) {
                        authenticated = true
                        writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                    } else {
                        // Send another token, prompt client to try next key or send public key
                        java.security.SecureRandom().nextBytes(token)
                        writeMessage(AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, token))
                    }
                } else if (msg.arg0 == AdbProtocol.ADB_AUTH_RSAPUBLICKEY) {
                    val pubKeyStr = String(msg.data!!).trimEnd('\u0000')
                    Timber.tag(TAG).i("Received public key: $pubKeyStr")

                    if (isKeyAuthorized(pubKeyStr)) {
                        authenticated = true
                        writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                    } else {
                        // Prompt user
                        val allowed = promptUserForPairingBlocking(pubKeyStr)
                        if (allowed) {
                            authorizeKey(pubKeyStr)
                            authenticated = true
                            writeMessage(AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
                        } else {
                            Timber.tag(TAG).w("User denied pairing for key: $pubKeyStr")
                            return
                        }
                    }
                }
            } else {
                Timber.tag(TAG).e("Expected AUTH, got ${msg.command}")
                return
            }
        }

        Timber.tag(TAG).i("Client authenticated. Entering multiplex loop.")

        while (true) {
            msg = readMessage()
            when (msg.command) {
                AdbProtocol.A_OPEN -> {
                    val remoteId = msg.arg0
                    val destination = String(msg.data!!).trimEnd('\u0000')
                    if (destination.startsWith("shell:")) {
                        val cmd = destination.substring(6)
                        startShellProcess(remoteId, cmd)
                    } else {
                        Timber.tag(TAG).w("Unsupported destination: $destination")
                        writeMessage(AdbMessage(AdbProtocol.A_CLSE, 0, remoteId, ByteArray(0)))
                    }
                }
                AdbProtocol.A_WRTE -> {
                    val localId = msg.arg0
                    val remoteId = msg.arg1
                    val process = activeProcesses[localId]
                    if (process != null) {
                        try {
                            process.outputStream.write(msg.data!!)
                            process.outputStream.flush()
                            writeMessage(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))
                        } catch (e: Exception) {
                            Timber.tag(TAG).e(e, "Error writing to process stdin")
                        }
                    }
                }
                AdbProtocol.A_CLSE -> {
                    val localId = msg.arg0
                    val process = activeProcesses.remove(localId)
                    process?.destroy()
                }
            }
        }
    }

    private fun startShellProcess(remoteId: Int, cmd: String) {
        val localId = localIdCounter.getAndIncrement()
        try {
            // Acknowledge the OPEN
            writeMessage(AdbMessage(AdbProtocol.A_OKAY, localId, remoteId, ByteArray(0)))

            val commandArray = if (cmd.isEmpty()) arrayOf("sh") else arrayOf("sh", "-c", cmd)
            // newProcess() is a Java platform type: null on some chipsets (e.g. MT6833) when the
            // binder is alive but process spawn fails. Treat null the same as an exception.
            val process = Shizuku.newProcess(commandArray, null, null)
                ?: throw IllegalStateException("Shizuku.newProcess returned null")
            activeProcesses[localId] = process

            // Read stdout
            Thread {
                val buf = ByteArray(AdbProtocol.A_MAXDATA)
                try {
                    while (true) {
                        val r = process.inputStream.read(buf)
                        if (r <= 0) break
                        writeMessage(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, buf.copyOf(r)))
                        // Wait for OKAY from client (we ignore it for simplicity, but strictly we should wait)
                        // readMessage() is blocking on main loop, so we can't read OKAY here!
                        // Actually, ADB requires us to wait for OKAY before sending another WRTE.
                        // We will just blast WRTEs for now (fake adb clients might not care).
                    }
                } catch (e: Exception) {}

                // Read stderr (optional, usually multiplexed in ADB but we can just blast it)
                try {
                    while (true) {
                        val r = process.errorStream.read(buf)
                        if (r <= 0) break
                        writeMessage(AdbMessage(AdbProtocol.A_WRTE, localId, remoteId, buf.copyOf(r)))
                    }
                } catch (e: Exception) {}

                process.waitFor()
                process.destroy()
                activeProcesses.remove(localId)
                writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)))
            }.start()

        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Failed to start shell process")
            writeMessage(AdbMessage(AdbProtocol.A_CLSE, localId, remoteId, ByteArray(0)))
        }
    }

    private fun verifySignature(token: ByteArray, signature: ByteArray): Boolean {
        val allKeys = prefs.getStringSet("keys", emptySet()) ?: emptySet()
        for (keyStr in allKeys) {
            try {
                val pubKey = parseAdbPublicKey(keyStr)

                // ADB's signature is a raw RSA encryption of a PKCS#1 padded token.
                // It does NOT hash the token before signing. It treats the token as the hash.
                val cipher = javax.crypto.Cipher.getInstance("RSA/ECB/NoPadding")
                cipher.init(javax.crypto.Cipher.DECRYPT_MODE, pubKey)
                val decrypted = cipher.doFinal(signature)

                // Check if the decrypted payload ends with our exact token
                var match = true
                val offset = decrypted.size - token.size
                if (offset < 0) continue

                for (i in token.indices) {
                    if (decrypted[offset + i] != token[i]) {
                        match = false
                        break
                    }
                }
                if (match) return true
            } catch (e: Exception) {
                Timber.tag(TAG).e(e, "Error verifying signature")
            }
        }
        return false
    }

    private fun isKeyAuthorized(pubKeyStr: String): Boolean {
        val allKeys = prefs.getStringSet("keys", emptySet()) ?: emptySet()
        return allKeys.contains(pubKeyStr)
    }

    private fun authorizeKey(pubKeyStr: String) {
        val allKeys = prefs.getStringSet("keys", emptySet())?.toMutableSet() ?: mutableSetOf()
        allKeys.add(pubKeyStr)
        prefs.edit().putStringSet("keys", allKeys).apply()
    }

    private fun promptUserForPairingBlocking(pubKeyStr: String): Boolean {
        Timber.tag(TAG).i("Prompting user for pairing...")
        // Start an activity to ask the user. We must block until the result is available.
        // For now, let's implement a simple intent to FakeAdbPairingActivity and use a static lock/callback
        return FakeAdbPairingActivity.requestPairingSync(context, pubKeyStr)
    }

    private fun readMessage(): AdbMessage {
        val header = ByteArray(24)
        inputStream.readFully(header)
        val buf = java.nio.ByteBuffer.wrap(header).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val command = buf.int
        val arg0 = buf.int
        val arg1 = buf.int
        val data_length = buf.int
        val data_crc32 = buf.int
        val magic = buf.int

        var data: ByteArray? = null
        if (data_length > 0) {
            // data_length comes straight off the wire before CRC/magic are verified, so a
            // corrupt or hostile peer can claim an arbitrary size here; without a cap this
            // allocates unboundedly and OOMs the process (Sentry SHIZUKUPLUS-8F, 825MB alloc).
            if (data_length > MAX_ADB_PAYLOAD_SIZE) {
                throw java.io.IOException("ADB message data_length $data_length exceeds max $MAX_ADB_PAYLOAD_SIZE")
            }
            data = ByteArray(data_length)
            inputStream.readFully(data)
        }
        return AdbMessage(command, arg0, arg1, data_length, data_crc32, magic, data)
    }

    private fun writeMessage(msg: AdbMessage) {
        synchronized(outputStream) {
            outputStream.write(msg.toByteArray())
            outputStream.flush()
        }
    }
}
