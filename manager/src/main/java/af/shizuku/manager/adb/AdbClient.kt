package af.shizuku.manager.adb

import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import af.shizuku.manager.adb.AdbProtocol.A_AUTH
import af.shizuku.manager.adb.AdbProtocol.A_CLSE
import af.shizuku.manager.adb.AdbProtocol.A_CNXN
import af.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import af.shizuku.manager.adb.AdbProtocol.A_OKAY
import af.shizuku.manager.adb.AdbProtocol.A_OPEN
import af.shizuku.manager.adb.AdbProtocol.A_STLS
import af.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import af.shizuku.manager.adb.AdbProtocol.A_VERSION
import af.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import timber.log.Timber
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.atomic.AtomicBoolean
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"


class AdbClient(
    private val host: String,
    private val port: Int,
    private val key: AdbKey,
    /** Called once, right after the public key was offered to adbd and the connection starts
     *  waiting for the user to accept the "Allow USB debugging?" dialog. */
    private val onAuthorizationPending: (() -> Unit)? = null,
) : Closeable {
    @Volatile
    private var socket: Socket? = null
    private var plainInputStream: DataInputStream? = null
    private var plainOutputStream: DataOutputStream? = null

    private var useTls = false

    private var tlsSocket: SSLSocket? = null
    private var tlsInputStream: DataInputStream? = null
    private var tlsOutputStream: DataOutputStream? = null

    private val inputStream get() =
        (if (useTls) tlsInputStream else plainInputStream)
            ?: throw IllegalStateException("inputStream is null - AdbClient not connected or closed")
    private val outputStream get() =
        (if (useTls) tlsOutputStream else plainOutputStream)
            ?: throw IllegalStateException("outputStream is null - AdbClient not connected or closed")

    fun connect() {
        require(port in 1..65535) { "port out of range: $port" }
        useTls = false // Reset TLS state for each connection attempt
        val s = Socket()
        socket = s
        val address = InetSocketAddress(host, port)

        try {
            s.connect(address, 5000)
            s.tcpNoDelay = true
            s.soTimeout = 15000 // 15 seconds read timeout to prevent infinite hangs
            s.keepAlive = true

            val pin = DataInputStream(s.getInputStream())
            plainInputStream = pin
            val pout = DataOutputStream(s.getOutputStream())
            plainOutputStream = pout

            write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

            var message = read()
            if (message.command == A_STLS) {
                if (!BuildUtils.atLeast29) {
                    error("Connect to adb with TLS is not supported before Android 9")
                }
                write(A_STLS, A_STLS_VERSION, 0)

                val sslContext = key.sslContext
                val ts = sslContext.socketFactory.createSocket(s, host, port, true) as SSLSocket
                tlsSocket = ts
                ts.startHandshake()
                Timber.tag(TAG).d("Handshake succeeded.")

                tlsInputStream = DataInputStream(ts.inputStream)
                tlsOutputStream = DataOutputStream(ts.outputStream)
                useTls = true

                message = read()
            } else if (message.command == A_AUTH) {
                if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
                write(A_AUTH, ADB_AUTH_SIGNATURE, 0, key.sign(message.data))

                message = read()
                if (message.command != A_CNXN) {
                    message = offerKeyAndAwaitAuthorization(s)
                }
            }

            if (message.command != A_CNXN) error("not A_CNXN")
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    /**
     * adbd answers an offered public key only when the user accepts its dialog: a denied dialog
     * gets no reply at all and leaves the connection open and unauthorised (AOSP adbd_auth.cpp,
     * DenyUsbDevice), so a rejection and an unanswered dialog look the same here. adbd raises one
     * dialog per key offer and queues the rest behind the one showing. Reconnecting after the
     * normal read timeout would therefore stack dialogs, so this holds the one connection open
     * for [AdbAuthWait.TIMEOUT_MS]. Anything that goes wrong once the key has been offered (the
     * deadline, a dropped connection, a malformed reply) surfaces as [AdbAuthTimeoutException],
     * which callers do not retry.
     */
    private fun offerKeyAndAwaitAuthorization(s: Socket): AdbMessage {
        // Claim the wait slot before the key goes out: the claim is a single compare-and-set, so
        // two connections racing past their callers' advisory isWaiting() checks cannot both
        // offer a key and stack two dialogs. The loser aborts without adbd ever seeing its key.
        // Everything constructed after the CAS lives inside the try, so the slot cannot leak if
        // setup (e.g. the Timer's thread creation) fails: end() runs on every post-claim path.
        val deadlineHit = AtomicBoolean(false)
        if (!AdbAuthWait.tryBegin()) {
            throw AdbAuthPendingException("another start is already waiting for the adbd authorisation dialog")
        }
        var deadline: Timer? = null
        val timeoutMs = AdbAuthWait.timeoutMs
        try {
            Timber.tag(TAG).i("Waiting up to %d ms for the user to accept the adbd authorisation dialog", timeoutMs)
            // soTimeout bounds each read call, not the whole wait, so the connection (and the
            // process-wide gate) could stay held past it. Close the socket at a deadline.
            deadline = Timer("adb-auth-deadline", true)
            // Recorded before the key goes out and removed only when adbd accepts it, so every
            // other way this wait can end (the deadline, a rejection, a dropped connection, the
            // worker being stopped or cancelled, this process dying) leaves the marker that stops
            // unattended starts from offering the key again. Whoever offered it, worker or not.
            // An offer that cannot be recorded is not made.
            check(AdbAuthWait.markUnanswered()) { "the pending authorisation could not be recorded" }
            write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, key.adbPublicKey)
            runCatching { onAuthorizationPending?.invoke() }
            s.soTimeout = timeoutMs
            deadline.schedule(
                object : TimerTask() {
                    override fun run() {
                        deadlineHit.set(true)
                        runCatching { s.close() }
                    }
                },
                timeoutMs.toLong(),
            )
            val message = read()
            if (message.command != A_CNXN) error("not A_CNXN")
            AdbAuthWait.clearUnanswered()
            return message
        } catch (e: Exception) {
            throw if (deadlineHit.get() || e is java.net.SocketTimeoutException) {
                AdbAuthTimeoutException("adbd authorisation dialog was not answered within ${timeoutMs / 1000}s")
            } else {
                AdbAuthTimeoutException("adbd did not accept the key: ${e.message}")
            }
        } finally {
            deadline?.cancel()
            AdbAuthWait.end()
            runCatching { s.soTimeout = 15000 }
        }
    }

    fun command(
        cmd: String,
        listener: ((ByteArray) -> Unit)? = null,
    ) {
        val localId = 1
        write(A_OPEN, localId, 0, cmd)

        var message = read()
        when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    if (message.command == A_WRTE) {
                        message.data?.let { listener?.invoke(it) }
                        write(A_OKAY, localId, remoteId)
                    } else if (message.command == A_CLSE) {
                        write(A_CLSE, localId, remoteId)
                        break
                    } else {
                        error("not A_WRTE or A_CLSE")
                    }
                }
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
            }
            else -> {
                error("not A_OKAY or A_CLSE")
            }
        }
    }

    private fun write(
        command: Int,
        arg0: Int,
        arg1: Int,
        data: ByteArray? = null,
    ) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(
        command: Int,
        arg0: Int,
        arg1: Int,
        data: String,
    ) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        val os = if (useTls) tlsOutputStream else plainOutputStream
        if (os == null) {
            Timber.tag(TAG).w("write called on closed/unconnected AdbClient - dropping ${message.toStringShort()}")
            return
        }
        os.write(message.toByteArray())
        os.flush()
        Timber.tag(TAG).d("write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, 24)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        val checksum = buffer.int
        val magic = buffer.int
        val data: ByteArray?
        if (dataLength >= 0) {
            data = ByteArray(dataLength)
            inputStream.readFully(data, 0, dataLength)
        } else {
            data = null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Timber.tag(TAG).d("read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        try {
            plainInputStream?.close()
        } catch (_: Throwable) {
        } finally {
            plainInputStream = null
        }
        try {
            plainOutputStream?.close()
        } catch (_: Throwable) {
        } finally {
            plainOutputStream = null
        }
        try {
            socket?.close()
        } catch (_: Exception) {
        } finally {
            socket = null
        }

        if (useTls) {
            try {
                tlsInputStream?.close()
            } catch (_: Throwable) {
            } finally {
                tlsInputStream = null
            }
            try {
                tlsOutputStream?.close()
            } catch (_: Throwable) {
            } finally {
                tlsOutputStream = null
            }
            try {
                tlsSocket?.close()
            } catch (_: Exception) {
            } finally {
                tlsSocket = null
            }
        }
    }
}
