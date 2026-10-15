package moe.shizuku.manager.adb

import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * 「配对到底成没成功」的记录。
 *
 * 为什么需要单独记一笔：本地存着 adb 私钥**不等于**设备认识这把密钥。
 * [AdbKey] 在构造时（也就是用户点下「确定」发起配对的那一刻）就会生成密钥并写进
 * [AdbKeyStore]，所以配对码输错、端口不通、中途取消 —— 任何一种失败都会在本地
 * 留下一把 adbd 从来没见过的密钥。只看「密钥在不在」来判断配对状态，界面必然谎报：
 * 用户明明没配上，激活页却显示「已配对」。
 *
 * 所以把「成功」当成一个独立事实记录下来，只有 [AdbPairingClient] 真正跑完配对
 * 流程才写入；忘记配对、或确认凭据已失效时清掉。
 */
object AdbPairingState {

    private const val KEY_PAIRED_AT = "adb_paired_at"

    /** 配对成功的时间戳；0 表示没有成功记录（从未配对，或来自旧版本、无从考证）。 */
    fun pairedAt(preferences: SharedPreferences): Long =
        preferences.getLong(KEY_PAIRED_AT, 0L)

    /** 是否确认配对成功过。 */
    fun isPaired(preferences: SharedPreferences): Boolean = pairedAt(preferences) > 0L

    /** 配对成功：记一笔。 */
    fun markPaired(preferences: SharedPreferences) {
        preferences.edit { putLong(KEY_PAIRED_AT, System.currentTimeMillis()) }
    }

    /** 忘记配对 / 凭据失效：连记录一起清掉，免得残留的成功记录继续骗人。 */
    fun clear(preferences: SharedPreferences) {
        preferences.edit { remove(KEY_PAIRED_AT) }
    }
}
