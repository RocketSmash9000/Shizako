package moe.shizuku.manager.adb

@Suppress("NOTHING_TO_INLINE")
inline fun adbError(message: Any): Nothing = throw AdbException(message.toString())

open class AdbException : Exception {

    constructor(message: String, cause: Throwable?) : super(message, cause)
    constructor(message: String) : super(message)
    constructor(cause: Throwable) : super(cause)
    constructor()
}

class AdbInvalidPairingCodeException : AdbException()

class AdbKeyException(cause: Throwable) : AdbException(cause)

/**
 * 配对在协议层断掉了：对端发来的数据没法解析，或者加密通道建立不起来。
 *
 * 单独立一个类型，是为了让界面能区分「配对码不对」和「这次握手本身就失败」——
 * 这两种情况给用户的下一步动作不一样（换配对码 vs 重开系统配对对话框）。
 * [reason] 只进日志，不回显给用户。
 */
class AdbPairingFailedException(reason: String) : AdbException(reason)
