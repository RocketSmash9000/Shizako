package moe.shizuku.manager.security

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import moe.shizuku.manager.R
import moe.shizuku.manager.fdroid.FdroidBuild
import moe.shizuku.manager.ui.glass.GlassWindow
import java.security.MessageDigest

/**
 * 应用内签名自检（防伪）。
 *
 * 仿冒品（重打包改名、解包换签）与本项目最大的区别就是**签名**：
 * 任何人都能抄界面、抄包名，但抄不走发布私钥。
 *
 * 做法：
 * - 读取**当前这份安装包自己的**签名证书 SHA-256（取第一个签名者）；
 * - 与 [OFFICIAL_SHA256]（GitHub 发布版签名证书指纹，见 README「官方构建的签名指纹」）比对；
 * - **只警告、不阻断**（用户选择）：不符时启动弹一次对话框，引导去官方发布页核对 / 复制自己的指纹；
 * - F-Droid 版由 F-Droid 用自己的密钥重签，指纹必然与官方不同且**无法在应用内预知**，
 *   所以 F-Droid 版不做警告，只在关于页标注「F-Droid 签名」。
 */
object SignatureGuard {

    /** GitHub 发布版的签名证书 SHA-256（从正式发布的 APK 实测读出）。 */
    const val OFFICIAL_SHA256 =
        "515baf0c727f2afbaba8a22379a05f8f2cc98682e5beb24fb166565acc9f9c8a"

    /** 官方发布页（GitHub Releases）。 */
    const val OFFICIAL_RELEASE_URL = "https://github.com/cr1437/Shizako-KernelSU-UI-Desktop/releases"

    /** 校验结论。 */
    enum class Result { OFFICIAL, FDROID_SIGNED, UNKNOWN }

    /** 当前安装包的签名指纹（小写十六进制），读不到返回 null。 */
    fun ownFingerprint(context: Context): String? = runCatching {
        val pm = context.packageManager
        val info = if (android.os.Build.VERSION.SDK_INT >= 28) {
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
        }
        // 签名者的 Certificate.getEncoded() 就是 X.509 原始字节（v1 时代的 Signature 同理）
        val encoded: ByteArray = if (android.os.Build.VERSION.SDK_INT >= 28) {
            val si = info.signingInfo ?: return null
            (if (si.hasMultipleSigners()) si.apkContentsSigners else si.signingCertificateHistory)
                .firstOrNull()?.toByteArray() ?: return null
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.firstOrNull()?.toByteArray() ?: return null
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
        digest.joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** 校验当前安装包。 */
    fun verify(context: Context): Result {
        val fp = ownFingerprint(context) ?: return Result.UNKNOWN
        return if (fp.equals(OFFICIAL_SHA256, ignoreCase = true)) {
            Result.OFFICIAL
        } else if (FdroidBuild.isFdroid) {
            // F-Droid 重签是必然现象，不算异常
            Result.FDROID_SIGNED
        } else {
            Result.UNKNOWN
        }
    }

    /** 关于页展示用：按结论给状态文案。 */
    fun statusLabel(context: Context, result: Result): String = context.getString(
        when (result) {
            Result.OFFICIAL -> R.string.sig_status_official
            Result.FDROID_SIGNED -> R.string.sig_status_fdroid
            Result.UNKNOWN -> R.string.sig_status_unknown
        },
    )

    /** 关于页展示用：指纹分段显示（64 位十六进制，4 段×16）。 */
    fun groupedFingerprint(fp: String): String =
        fp.chunked(16).joinToString("\n")

    // ---------- 启动警告 ----------

    /** 每个进程只弹一次（防 recreate 后连弹）。 */
    private var warnedThisProcess = false

    /**
     * 启动时自检：GitHub 版签名与官方不符 → 弹警告（不阻断）。
     *
     * 延迟 600ms 再弹：给冷启动入场动画留口气，也不和「更新欢迎页」抢同一帧。
     */
    fun checkOnLaunch(activity: Activity) {
        if (warnedThisProcess) return
        if (FdroidBuild.isFdroid) return
        if (verify(activity) != Result.UNKNOWN) return
        warnedThisProcess = true

        activity.window?.decorView?.postDelayed({
            if (activity.isFinishing || activity.isDestroyed) return@postDelayed
            showWarning(activity)
        }, 600)
    }

    private fun showWarning(activity: Activity) {
        val mine = ownFingerprint(activity) ?: activity.getString(R.string.sig_read_failed)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.sig_warn_title)
            .setMessage(activity.getString(R.string.sig_warn_message, mine))
            .setPositiveButton(R.string.sig_warn_open_official) { _, _ ->
                runCatching {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(OFFICIAL_RELEASE_URL))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }
            .setNeutralButton(R.string.sig_warn_copy) { _, _ ->
                copyFingerprint(activity, mine)
            }
            .setNegativeButton(R.string.sig_warn_understood, null)
            .setCancelable(true)
            .create()
        dialog.show()
        GlassWindow.applyIfGlass(dialog)
    }

    fun copyFingerprint(context: Context, fp: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE)
            as? android.content.ClipboardManager
        clipboard?.setPrimaryClip(
            android.content.ClipData.newPlainText("signature", fp),
        )
        android.widget.Toast.makeText(
            context,
            R.string.sig_copied,
            android.widget.Toast.LENGTH_SHORT,
        ).show()
    }
}
