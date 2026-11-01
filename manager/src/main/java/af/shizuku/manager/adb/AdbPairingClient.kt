package af.shizuku.manager.adb

import android.os.Build
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
import io.sentry.Breadcrumb
import io.sentry.Sentry
import timber.log.Timber
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbPairClient"

private const val kCurrentKeyHeaderVersion = 1.toByte()
private const val kMinSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxSupportedKeyHeaderVersion = 1.toByte()
private const val kMaxPeerInfoSize = 8192
private const val kMaxPayloadSize = kMaxPeerInfoSize * 2

private const val kExportedKeyLabel = "adb-label\u0000"
private const val kExportedKeySize = 64

private const val kPairingPacketHeaderSize = 6

private class PeerInfo(
    val type: Byte,
    data: ByteArray,
) {
    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(
        val value: Byte,
    ) {
        ADB_RSA_PUB_KEY(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Timber.tag(TAG).d("write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String = "PeerInfo(${toStringShort()})"

    fun toStringShort(): String = "type=$type, data=${data.contentToString()}"

    companion object {
        fun readFrom(buffer: ByteBuffer): PeerInfo {
            val type = buffer.get()
            val data = ByteArray(kMaxPeerInfoSize - 1)
            buffer.get(data)
            return PeerInfo(type, data)
        }
    }
}

private class PairingPacketHeader(
    val version: Byte,
    val type: Byte,
    val payload: Int,
) {
    enum class Type(
        val value: Byte,
    ) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Timber.tag(TAG).d("write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String = "PairingPacketHeader(${toStringShort()})"

    fun toStringShort(): String = "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"

    companion object {
        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Timber.tag(TAG).e("PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=$version)")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Timber.tag(TAG).e("Unknown PairingPacket type=$type")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Timber.tag(TAG).e("header payload not within a safe payload size (size=$payload)")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Timber.tag(TAG).d("read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

private class PairingContext private constructor(
    private val nativePtr: Long,
) {
    val msg: ByteArray

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    fun destroy() = nativeDestroy(nativePtr)

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(
        nativePtr: Long,
        theirMsg: ByteArray,
    ): Boolean

    private external fun nativeEncrypt(
        nativePtr: Long,
        inbuf: ByteArray,
    ): ByteArray?

    private external fun nativeDecrypt(
        nativePtr: Long,
        inbuf: ByteArray,
    ): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {
        fun create(password: ByteArray): PairingContext? {
            if (!AdbPairingClient.isNativeAvailable()) return null
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(
            isClient: Boolean,
            password: ByteArray,
        ): Long
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairCode: String,
    private val key: AdbKey,
) : Closeable {
    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped,
    }

    private var socket: Socket? = null
    private var inputStream: DataInputStream? = null
    private var outputStream: DataOutputStream? = null

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private var pairingContext: PairingContext? = null
    private var state: State = State.Ready

    fun start(): Boolean {
        Sentry.addBreadcrumb(
            Breadcrumb("ADB Pairing started").apply {
                // No host or port: they identify the device's network.
                category = "adb.pairing"
            },
        )
        try {
            setupTlsConnection()

            state = State.ExchangingMsgs

            if (!doExchangeMsgs()) {
                Sentry.addBreadcrumb(
                    Breadcrumb("ADB Pairing failed at message exchange").apply {
                        category = "adb.pairing"
                        level = io.sentry.SentryLevel.ERROR
                    },
                )
                state = State.Stopped
                return false
            }

            state = State.ExchangingPeerInfo

            if (!doExchangePeerInfo()) {
                Sentry.addBreadcrumb(
                    Breadcrumb("ADB Pairing failed at peer info exchange").apply {
                        category = "adb.pairing"
                        level = io.sentry.SentryLevel.ERROR
                    },
                )
                state = State.Stopped
                return false
            }

            Sentry.addBreadcrumb(Breadcrumb("ADB Pairing succeeded").apply { category = "adb.pairing" })
            state = State.Stopped
            return true
        } catch (e: Exception) {
            Sentry.addBreadcrumb(
                Breadcrumb("ADB Pairing error: ${e.message}").apply {
                    category = "adb.pairing"
                    level = io.sentry.SentryLevel.ERROR
                },
            )
            if (e is AdbInvalidPairingCodeException) {
                Timber.tag(TAG).w("Invalid pairing code entered")
            } else {
                Timber.tag(TAG).e(e, "Error during pairing process")
            }
            state = State.Stopped
            return false
        } finally {
            cleanupResources()
        }
    }

    private fun setupTlsConnection() {
        val candidates = AdbPortProber.getCandidateHosts(host)
        var connectedSocket: Socket? = null
        var lastException: Exception? = null
        var successfulHost = host

        for (candidate in candidates) {
            val s = Socket()
            try {
                s.connect(java.net.InetSocketAddress(candidate, port), 3000)
                s.tcpNoDelay = true
                s.soTimeout = 15000 // 15 seconds read timeout to prevent infinite hangs during pairing
                s.keepAlive = true
                connectedSocket = s
                successfulHost = candidate
                break
            } catch (e: Exception) {
                s.close()
                lastException = e
                if (e is java.net.ConnectException || e is java.net.NoRouteToHostException || e is java.net.SocketTimeoutException) {
                    continue
                } else {
                    throw e
                }
            }
        }

        val s = connectedSocket ?: throw (lastException ?: java.net.ConnectException("Could not connect to pairing service on port $port across $candidates"))
        socket = s

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(s, successfulHost, port, true) as SSLSocket
        sslSocket.startHandshake()
        Timber.tag(TAG).d("Handshake succeeded on $successfulHost:$port.")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        val context = PairingContext.create(passwordBytes)
        checkNotNull(context) { "Unable to create PairingContext." }
        this.pairingContext = context
    }

    private fun createHeader(
        type: PairingPacketHeader.Type,
        payloadSize: Int,
    ): PairingPacketHeader = PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        val input = inputStream ?: return null
        input.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(
        header: PairingPacketHeader,
        payload: ByteArray,
    ) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        val output = outputStream ?: return
        output.write(buffer.array())
        output.write(payload)
        Timber.tag(TAG).d("write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs(): Boolean {
        val context = pairingContext ?: return false
        val msg = context.msg
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream?.readFully(theirMessage)

        if (!context.initCipher(theirMessage)) return false
        return true
    }

    private fun doExchangePeerInfo(): Boolean {
        val context = pairingContext ?: return false
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = context.encrypt(buf.array()) ?: return false

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader() ?: return false
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) return false

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream?.readFully(theirMessage)

        val decrypted = context.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            Timber.tag(TAG).e("Got size=${decrypted.size} PeerInfo.size=$kMaxPeerInfoSize")
            return false
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Timber.tag(TAG).d(theirPeerInfo.toString())
        return true
    }

    private fun cleanupResources() {
        try {
            inputStream?.close()
        } catch (e: Throwable) {
            Timber.tag(TAG).w("Failed to close inputStream: ${e.message}")
        } finally {
            inputStream = null
        }
        try {
            outputStream?.close()
        } catch (e: Throwable) {
            Timber.tag(TAG).w("Failed to close outputStream: ${e.message}")
        } finally {
            outputStream = null
        }
        try {
            socket?.close()
        } catch (e: Exception) {
            Timber.tag(TAG).w("Failed to close socket: ${e.message}")
        } finally {
            socket = null
        }

        pairingContext?.let {
            it.destroy()
            pairingContext = null
        }
    }

    override fun close() {
        cleanupResources()
    }

    companion object {
        @Volatile
        private var nativeLoaded: Boolean = false

        @Volatile
        var nativeLoadError: String? = null
            private set

        init {
            try {
                System.loadLibrary("adb")
                nativeLoaded = true
            } catch (t: Throwable) {
                nativeLoaded = false
                nativeLoadError = "${t.javaClass.simpleName}: ${t.message}"
                Timber.tag(TAG).e(t, "Failed to load native libadb.so — wireless pairing JNI unavailable")
            }
        }

        @JvmStatic
        fun isNativeAvailable(): Boolean =
            nativeLoaded &&
                try {
                    available()
                } catch (t: Throwable) {
                    Timber.tag(TAG).w(t, "libadb.so available() check failed")
                    false
                }

        @JvmStatic
        external fun available(): Boolean
    }
}
