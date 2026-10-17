package moe.shizuku.manager.adb

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.android.org.conscrypt.Conscrypt
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

/**
 * 配对连接的超时（毫秒）。与 [AdbClient] 保持一致（10s 连接 / 15s 读）。
 *
 * 必须有：配对时用户可能停在系统「使用配对码配对设备」对话框里不动，
 * connect / TLS 握手 / 读包任何一步永久阻塞，都会让通知一直停在「配对中」、
 * 分屏配对对话框的按钮永远禁用，只能杀进程恢复。
 */
private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

private class PeerInfo(
        val type: Byte,
        data: ByteArray) {

    val data = ByteArray(kMaxPeerInfoSize - 1)

    init {
        data.copyInto(this.data, 0, 0, data.size.coerceAtMost(kMaxPeerInfoSize - 1))
    }

    enum class Type(val value: Byte) {
        ADB_RSA_PUB_KEY(0.toByte()),
        ADB_DEVICE_GUID(0.toByte()),
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(type)
            put(data)
        }

        Log.d(TAG, "write PeerInfo ${toStringShort()}")
    }

    override fun toString(): String {
        return "PeerInfo(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "type=$type, data=${data.contentToString()}"
    }

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
        val payload: Int) {

    enum class Type(val value: Byte) {
        SPAKE2_MSG(0.toByte()),
        PEER_INFO(1.toByte())
    }

    fun writeTo(buffer: ByteBuffer) {
        buffer.run {
            put(version)
            put(type)
            putInt(payload)
        }

        Log.d(TAG, "write PairingPacketHeader ${toStringShort()}")
    }

    override fun toString(): String {
        return "PairingPacketHeader(${toStringShort()})"
    }

    fun toStringShort(): String {
        return "version=${version.toInt()}, type=${type.toInt()}, payload=$payload"
    }

    companion object {

        fun readFrom(buffer: ByteBuffer): PairingPacketHeader? {
            val version = buffer.get()
            val type = buffer.get()
            val payload = buffer.int

            if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
                Log.e(TAG, "PairingPacketHeader version mismatch (us=$kCurrentKeyHeaderVersion them=${version})")
                return null
            }
            if (type != Type.SPAKE2_MSG.value && type != Type.PEER_INFO.value) {
                Log.e(TAG, "Unknown PairingPacket type=${type}")
                return null
            }
            if (payload <= 0 || payload > kMaxPayloadSize) {
                Log.e(TAG, "header payload not within a safe payload size (size=${payload})")
                return null
            }

            val header = PairingPacketHeader(version, type, payload)
            Log.d(TAG, "read PairingPacketHeader ${header.toStringShort()}")
            return header
        }
    }
}

@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(private val host: String, private val port: Int, private val pairCode: String, private val key: AdbKey) : Closeable {

    private enum class State {
        Ready,
        ExchangingMsgs,
        ExchangingPeerInfo,
        Stopped
    }

    private lateinit var socket: Socket
    private lateinit var inputStream: DataInputStream
    private lateinit var outputStream: DataOutputStream

    private val peerInfo: PeerInfo = PeerInfo(PeerInfo.Type.ADB_RSA_PUB_KEY.value, key.adbPublicKey)
    private lateinit var pairingContext: PairingContext
    private var state: State = State.Ready

    /**
     * 原生上下文是否已销毁。
     *
     * **必须**有这一道闸：native 侧的 `PairingContext_Destroy` 只做
     * `SPAKE2_CTX_free` + `EVP_AEAD_CTX_free` + `free(ctx)`，既不判空也不置空
     * （`adb_pairing.cpp:202-207`），指针仍留在 Java 字段里 —— 重复 destroy 就是
     * double free。而 [close] 现在是**真的会被调用两次**：[start] 的 finally 一次、
     * 调用方的 `use{}` 再一次。所以幂等性只能在这里保证。
     */
    private var pairingContextDestroyed = false

    /**
     * 跑完整个配对流程；失败一律抛异常。
     *
     * 以前这里用 `return false` 表示失败，调用方（配对对话框 / 通知栏服务）拿不到任何
     * 原因，用户看到的就是「转一下圈，然后什么也没发生」—— 配不上，也不知道为什么。
     * 现在每种失败都有明确的类型：[AdbInvalidPairingCodeException] 是配对码不对或已失效，
     * [AdbPairingFailedException] 是协议/加密层断了，连接与握手阶段的异常（如
     * [java.net.ConnectException]、SSL 握手失败）原样向上抛。
     *
     * **清理由本方法自己负责**：无论成功、失败还是中途异常，都在 finally 里 [close]。
     * 调用方只需要 `use { it.start() }`（或不写 use 也不会泄漏 socket 与原生上下文）。
     * 这里不能用 `state` 当清理守卫 —— `state` 只在各阶段**成功之后**才推进，
     * 建连阶段失败时它仍是 [State.Ready]，守卫会把 `pairingContext.destroy()` 整段跳过，
     * 泄漏一块持有 SPAKE2 密钥的原生内存。
     */
    fun start() {
        try {
            setupTlsConnection()

            state = State.ExchangingMsgs
            doExchangeMsgs()

            state = State.ExchangingPeerInfo
            doExchangePeerInfo()

            state = State.Stopped
        } finally {
            // 建连成功但后续握手失败时，原生上下文同样必须销毁；close() 内部先关流、
            // 再销毁上下文，顺序不能反（destroy 之后 pairingContext 不可再用）。
            close()
        }
    }

    private fun setupTlsConnection() {
        // 必须带超时：配对时用户可能停在系统「使用配对码配对设备」对话框里不动，
        // 而 connect / 握手 / 读包任何一步永久阻塞，都会让通知一直停在「配对中」、
        // 对话框按钮永远禁用（只能杀进程恢复）。数值与 AdbClient 保持一致（10s / 15s）。
        socket = java.net.Socket().apply {
            tcpNoDelay = true
            connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }

        val sslContext = key.sslContext
        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.soTimeout = READ_TIMEOUT_MS
        sslSocket.startHandshake()
        Log.d(TAG, "Handshake succeeded.")

        inputStream = DataInputStream(sslSocket.inputStream)
        outputStream = DataOutputStream(sslSocket.outputStream)

        val pairCodeBytes = pairCode.toByteArray()
        val keyMaterial = Conscrypt.exportKeyingMaterial(sslSocket, kExportedKeyLabel, null, kExportedKeySize)
        val passwordBytes = ByteArray(pairCode.length + keyMaterial.size)
        pairCodeBytes.copyInto(passwordBytes)
        keyMaterial.copyInto(passwordBytes, pairCodeBytes.size)

        val pairingContext = PairingContext.create(passwordBytes)
        checkNotNull(pairingContext) { "Unable to create PairingContext." }
        this.pairingContext = pairingContext
    }

    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun readHeader(): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        inputStream.readFully(bytes)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        return PairingPacketHeader.readFrom(buffer)
    }

    private fun writeHeader(header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        header.writeTo(buffer)

        outputStream.write(buffer.array())
        outputStream.write(payload)
        Log.d(TAG, "write payload, size=${payload.size}")
    }

    private fun doExchangeMsgs() {
        // native 未能产出本地公钥消息（上下文已销毁 / 构造异常）→ 协议层失败，
        // 不是「配对码不对」。这里的 ?: 让 addExchangeMsgs 抛具名异常而不是 NPE。
        val msg = pairingContext.msg
            ?: throw AdbPairingFailedException("no local SPAKE2 message available")
        val size = msg.size

        val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, size)
        writeHeader(ourHeader, msg)

        val theirHeader = readHeader()
            ?: throw AdbPairingFailedException("no SPAKE2 message header from peer")
        if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) {
            throw AdbPairingFailedException("unexpected packet type ${theirHeader.type} while waiting for SPAKE2 message")
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        // SPAKE2 的密码校验不过 = 两边算出来的配对码不一致：
        // 输错了，或者那个配对码已经作废（系统配对对话框一关，码就失效）。
        if (!pairingContext.initCipher(theirMessage)) {
            throw AdbInvalidPairingCodeException()
        }
    }

    private fun doExchangePeerInfo() {
        val buf = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
        peerInfo.writeTo(buf)

        val outbuf = pairingContext.encrypt(buf.array())
            ?: throw AdbPairingFailedException("unable to encrypt local peer info")

        val ourHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, outbuf.size)
        writeHeader(ourHeader, outbuf)

        val theirHeader = readHeader()
            ?: throw AdbPairingFailedException("no peer info header from peer")
        if (theirHeader.type != PairingPacketHeader.Type.PEER_INFO.value) {
            throw AdbPairingFailedException("unexpected packet type ${theirHeader.type} while waiting for peer info")
        }

        val theirMessage = ByteArray(theirHeader.payload)
        inputStream.readFully(theirMessage)

        val decrypted = pairingContext.decrypt(theirMessage) ?: throw AdbInvalidPairingCodeException()
        if (decrypted.size != kMaxPeerInfoSize) {
            throw AdbPairingFailedException("peer info size mismatch (got ${decrypted.size}, expected $kMaxPeerInfoSize)")
        }
        val theirPeerInfo = PeerInfo.readFrom(ByteBuffer.wrap(decrypted))
        Log.d(TAG, theirPeerInfo.toString())
    }

    /**
     * 幂等清理：关闭 socket，并销毁原生配对上下文。
     *
     * 不再用 `state != State.Ready` 当守卫 —— [start] 已经在 finally 里调用本方法，
     * 而 `pairingContext` 是 lateinit：只有建连成功过它才算初始化，所以这里必须用
     * [::pairingContext.isInitialized] 判断，否则会在“建连阶段就失败”的路径上抛
     * UninitializedPropertyAccessException（那是崩溃，不是清理）。
     */
    override fun close() {
        try {
            if (::inputStream.isInitialized) inputStream.close()
        } catch (e: Throwable) {
        }
        try {
            if (::outputStream.isInitialized) outputStream.close()
        } catch (e: Throwable) {
        }
        try {
            if (::socket.isInitialized) socket.close()
        } catch (e: Throwable) {
        }

        if (::pairingContext.isInitialized && !pairingContextDestroyed) {
            pairingContextDestroyed = true
            pairingContext.destroy()
        }
    }

    companion object {

        init {
            System.loadLibrary("adb")
        }

        @JvmStatic
        external fun available(): Boolean
    }
}
