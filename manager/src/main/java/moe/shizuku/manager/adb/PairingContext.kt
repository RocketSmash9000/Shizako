package moe.shizuku.manager.adb

/**
 * ADB 配对用的原生上下文（native 实现在 `manager/src/main/jni/adb_pairing.cpp`）。
 *
 * **必须**是**顶层类**、且全名正好是 `moe.shizuku.manager.adb.PairingContext`：
 * JNI 那边是 `FindClass("moe/shizuku/manager/adb/PairingContext")`，
 * 一旦把它写成别的类里的嵌套类（二进制名会变成 `AdbPairingClient$PairingContext`），
 * `FindClass` 就找不到类，`RegisterNatives` 带着未决异常继续跑 →
 * JNI 直接 abort，**App 一启动就闪退**（native crash，不是普通异常）。
 */
internal class PairingContext private constructor(private var nativePtr: Long) {

    /**
     * 本端的 SPAKE2 公钥消息。
     *
     * 声明为可空：native 侧在上下文已销毁或构造异常时会返回 `nullptr`，而 `nullptr`
     * 回到 Kotlin 就是 `null`（JNI 对返回 `jbyteArray` 的函数返回空指针即 null）。
     * 若这里写成非空 `ByteArray`，第一次解引用就会抛 NullPointerException —— 那是崩溃，
     * 不是可处理的失败。调用方 [AdbPairingClient] 会把它当作协议层失败处理。
     */
    val msg: ByteArray?

    init {
        msg = nativeMsg(nativePtr)
    }

    fun initCipher(theirMsg: ByteArray) = nativeInitCipher(nativePtr, theirMsg)

    fun encrypt(`in`: ByteArray) = nativeEncrypt(nativePtr, `in`)

    fun decrypt(`in`: ByteArray) = nativeDecrypt(nativePtr, `in`)

    /**
     * 销毁原生上下文，**幂等**。
     *
     * 关键在先把 [nativePtr] 置 0 再调 native：否则重复调用会把同一个已释放的指针
     * 再传一次进去（每个 `nativeXxx(nativePtr)` 都是在调用那一刻重新读字段的值，
     * 传的是值拷贝，C++ 那边置空改不到 Java 字段）—— 那就是 double free。
     * 置 0 之后重复调用变成无害的 no-op；native 侧 `PairingContext_Destroy` 也对 0
     * 和已销毁状态各有一道闸（`adb_pairing.cpp`），两层防护是有意的冗余。
     */
    fun destroy() {
        val ptr = nativePtr
        if (ptr == 0L) return
        nativePtr = 0L
        nativeDestroy(ptr)
    }

    private external fun nativeMsg(nativePtr: Long): ByteArray

    private external fun nativeInitCipher(nativePtr: Long, theirMsg: ByteArray): Boolean

    private external fun nativeEncrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDecrypt(nativePtr: Long, inbuf: ByteArray): ByteArray?

    private external fun nativeDestroy(nativePtr: Long)

    companion object {

        fun create(password: ByteArray): PairingContext? {
            val nativePtr = nativeConstructor(true, password)
            return if (nativePtr != 0L) PairingContext(nativePtr) else null
        }

        @JvmStatic
        private external fun nativeConstructor(isClient: Boolean, password: ByteArray): Long
    }
}
