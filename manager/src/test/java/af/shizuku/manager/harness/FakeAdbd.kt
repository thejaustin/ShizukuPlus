package af.shizuku.manager.harness

import af.shizuku.manager.adb.AdbMessage
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import af.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import af.shizuku.manager.adb.AdbProtocol.A_AUTH
import af.shizuku.manager.adb.AdbProtocol.A_CLSE
import af.shizuku.manager.adb.AdbProtocol.A_CNXN
import af.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import af.shizuku.manager.adb.AdbProtocol.A_OKAY
import af.shizuku.manager.adb.AdbProtocol.A_OPEN
import af.shizuku.manager.adb.AdbProtocol.A_VERSION
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * A loopback adbd speaking the plain (non-TLS) AUTH handshake. Each key offer made while the key
 * is not yet authorised counts as one dialog ([offers]) and then waits, as adbd does, for the
 * user's answer. [accept] completes the handshake. [reject] and [silent] look the same on the
 * wire, as on a real device: adbd sends nothing for a denied dialog and keeps the connection open
 * and unauthorised (AOSP adbd_auth.cpp, DenyUsbDevice), so the client only gives up at its own
 * deadline; a key offered again on that connection raises a new dialog. A key offered while a
 * dialog is still up is queued behind it and raises its own dialog once that one is answered, even
 * when the answer was Allow: adbd prompts for every offer, on an authorised connection too, so
 * offers after acceptance count as well. Connections that close before saying anything (the
 * starter's port probes) are ignored.
 */
class FakeAdbd(
    private val onShell: (String) -> Unit = {},
) : Closeable {
    enum class Answer { ACCEPT, REJECT, SILENT }

    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
    val port: Int get() = server.localPort

    private val offerCount = AtomicInteger(0)
    val offers: Int get() = offerCount.get()

    private val helloCount = AtomicInteger(0)

    /** Connections that said hello (port probes that close first are not counted). */
    val connections: Int get() = helloCount.get()
    private val offered = Semaphore(0)
    private val answers = LinkedBlockingQueue<Answer>()
    private val open = CopyOnWriteArrayList<Socket>()

    /** The key has been accepted once; adbd then answers its signature without a dialog. */
    @Volatile
    var authorized = false

    /** Runs on adbd's thread when a client says hello, before it can record or offer anything. */
    @Volatile
    var onHello: () -> Unit = {}

    init {
        Thread({ acceptLoop() }, "fake-adbd").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Waits for the next not-yet-awaited key offer, calling [pump] while it waits (the caller may
     * be the thread other work needs, e.g. Robolectric's main looper).
     */
    fun awaitOffer(
        timeoutMs: Long = 30_000,
        pump: () -> Unit = {},
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!offered.tryAcquire(10, TimeUnit.MILLISECONDS)) {
            check(System.currentTimeMillis() < deadline) { "no key offer within ${timeoutMs}ms (offers=$offers)" }
            pump()
        }
    }

    fun accept() = answers.put(Answer.ACCEPT)

    fun reject() = answers.put(Answer.REJECT)

    fun silent() = answers.put(Answer.SILENT)

    /** Drops every connection, as a dying peer process would. */
    fun dropAll() = open.forEach { runCatching { it.close() } }

    override fun close() {
        runCatching { server.close() }
        dropAll()
    }

    private fun acceptLoop() {
        while (!server.isClosed) {
            val s =
                try {
                    server.accept()
                } catch (_: IOException) {
                    return
                }
            open += s
            Thread({ serve(s) }, "fake-adbd-conn").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun serve(s: Socket) {
        try {
            val stream = PushbackInputStream(s.getInputStream())
            val input = DataInputStream(stream)
            val output = s.getOutputStream()
            val hello = read(input) ?: return
            if (hello.command != A_CNXN) return
            helloCount.incrementAndGet()
            onHello()
            write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
            val signature = read(input) ?: return
            check(signature.command == A_AUTH && signature.arg0 == ADB_AUTH_SIGNATURE) { "expected the token signature" }
            if (!authorized) {
                write(output, AdbMessage(A_AUTH, ADB_AUTH_TOKEN, 0, ByteArray(20)))
                while (true) {
                    val key = read(input) ?: return
                    check(key.command == A_AUTH && key.arg0 == ADB_AUTH_RSAPUBLICKEY) { "expected the public key" }
                    offerCount.incrementAndGet()
                    offered.release()
                    when (awaitAnswer(s, stream)) {
                        Answer.ACCEPT -> break
                        // Nothing is sent: the connection stays open until the client closes it
                        // or offers the key again.
                        Answer.REJECT, Answer.SILENT -> continue
                        null -> return
                    }
                }
                authorized = true
            }
            write(output, AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "device::"))
            while (true) {
                val message = read(input) ?: return
                // A queued or late offer: adbd shows its dialog even though this key is accepted.
                if (message.command == A_AUTH && message.arg0 == ADB_AUTH_RSAPUBLICKEY) offerCount.incrementAndGet()
                if (message.command != A_OPEN) continue
                val command = message.data?.let { String(it).trimEnd('\u0000') }.orEmpty()
                if (command.startsWith("shell:")) onShell(command)
                write(output, AdbMessage(A_OKAY, REMOTE_ID, message.arg0, ByteArray(0)))
                write(output, AdbMessage(A_CLSE, REMOTE_ID, message.arg0, ByteArray(0)))
            }
        } catch (_: IOException) {
        } finally {
            open -= s
            runCatching { s.close() }
        }
    }

    // Polls rather than blocking on the queue so that a connection the client has already closed
    // gives up and cannot consume an answer meant for a later one. Null: the peer went away. A byte
    // the client sends meanwhile (a key offered again) is pushed back for the next read.
    private fun awaitAnswer(
        s: Socket,
        stream: PushbackInputStream,
    ): Answer? {
        s.soTimeout = 100
        try {
            var pending = false
            while (true) {
                answers.poll()?.let { return it }
                if (pending) {
                    Thread.sleep(10)
                    continue
                }
                try {
                    val b = stream.read()
                    if (b == -1) return null
                    stream.unread(b)
                    pending = true
                } catch (_: SocketTimeoutException) {
                }
            }
        } finally {
            if (!s.isClosed) s.soTimeout = 0
        }
    }

    private fun read(input: DataInputStream): AdbMessage? {
        val header = ByteArray(AdbMessage.HEADER_LENGTH)
        try {
            input.readFully(header)
        } catch (_: EOFException) {
            return null
        }
        val b = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val command = b.int
        val arg0 = b.int
        val arg1 = b.int
        val length = b.int
        val crc = b.int
        val magic = b.int
        val data = ByteArray(length).also { input.readFully(it) }
        return AdbMessage(command, arg0, arg1, length, crc, magic, data).also { it.validateOrThrow() }
    }

    private fun write(
        output: OutputStream,
        message: AdbMessage,
    ) {
        output.write(message.toByteArray())
        output.flush()
    }

    private companion object {
        const val REMOTE_ID = 100
    }
}
