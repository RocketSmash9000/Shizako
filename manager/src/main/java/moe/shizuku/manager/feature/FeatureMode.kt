package moe.shizuku.manager.feature

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import moe.shizuku.manager.ShizukuSettings

/**
 * 一个功能所属的「模式档位」。
 *
 * 背景：有人反馈功能太多，一眼望过去不知道该点哪儿。于是分两档：
 * **简单模式**只留常用功能，其余收起来；需要的人打开进阶模式，全部放出来。
 *
 * 判定只写一处：每个入口（底栏 Tab、工具箱条目、设置行）标上自己的 [FeatureLevel]，
 * 由 [FeatureMode.allows] 统一裁决 —— 不要在界面里零散写 `if (isAdvanced)`，
 * 否则以后加功能就会漏掉某处，出现「简单模式下还能点到」的破口。
 */
enum class FeatureLevel {
    /** 简单模式下也显示。 */
    SIMPLE,

    /** 只在进阶模式显示。 */
    ADVANCED,
}

/**
 * 使用模式的**运行时可观察状态**（简单 / 进阶），带持久化记忆。
 *
 * 和 [moe.shizuku.manager.ui.style.UiStyle]、[moe.shizuku.manager.ui.nav.NavTabs] 同一套路：
 * - 值存在偏好里（[KEY_MODE]），下次启动还在同一个模式 —— 这就是「记忆」；
 * - 用 Compose 状态暴露，切模式后底栏 / 工具箱 / 设置页各自重组，不需要重建 Activity；
 * - 不把状态塞进 Activity，避免旋转、深色模式切换、进程恢复时各页面读到不同的值。
 *
 * 默认是 [MODE_SIMPLE]：这个开关就是冲「功能太多」来的，默认给清爽的那一档，
 * 想要全部功能的人自己拨一下，之后一直被记住。
 */
object FeatureMode {

    /** 偏好的键。 */
    const val KEY_MODE = "feature_mode"

    const val MODE_SIMPLE = "simple"
    const val MODE_ADVANCED = "advanced"

    /** 当前模式（"simple" / "advanced"）。Compose 侧直接读这个状态。 */
    var current: String by mutableStateOf(load())
        private set

    val isAdvanced: Boolean get() = current == MODE_ADVANCED

    private fun normalize(value: String?): String =
        if (value == MODE_ADVANCED) MODE_ADVANCED else MODE_SIMPLE

    private fun load(): String = try {
        normalize(ShizukuSettings.getPreferences()?.getString(KEY_MODE, MODE_SIMPLE))
    } catch (e: Throwable) {
        // 偏好还没初始化（例如某些极早期调用点）：按默认档位走，不要让界面因为读设置而崩
        MODE_SIMPLE
    }

    /** 切换到某个模式并记住它。 */
    fun apply(value: String) {
        val normalized = normalize(value)
        try {
            ShizukuSettings.getPreferences()?.edit()?.putString(KEY_MODE, normalized)?.apply()
        } catch (_: Throwable) {
            // 写失败也让本次会话生效：界面上用户看到的必须和自己刚点的那个一致
        }
        current = normalized
    }

    fun setAdvanced(advanced: Boolean) = apply(if (advanced) MODE_ADVANCED else MODE_SIMPLE)

    /** 从偏好重新读取（首次进入、或被外部改动后）。 */
    fun refresh() {
        current = load()
    }

    /**
     * 某个档位的功能在当前模式下是否可见。
     *
     * 简化约定：进阶模式看得见一切；简单模式只看得到 [FeatureLevel.SIMPLE]。
     */
    fun allows(level: FeatureLevel): Boolean =
        level == FeatureLevel.SIMPLE || isAdvanced
}
