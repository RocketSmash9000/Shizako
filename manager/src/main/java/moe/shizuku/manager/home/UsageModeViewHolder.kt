package moe.shizuku.manager.home

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import moe.shizuku.manager.R
import moe.shizuku.manager.databinding.HomeUsageModeBinding
import moe.shizuku.manager.feature.FeatureMode
import rikka.recyclerview.BaseViewHolder
import rikka.recyclerview.BaseViewHolder.Creator

/**
 * 首页的「使用模式」卡片：一个开关切换简单 / 进阶。
 *
 * 为什么放在首页：功能太多是**第一眼**的感受，所以总开关也该在第一眼能看到的地方。
 * 开关状态由 [FeatureMode] 持久化 —— 这次开了进阶，下次打开还在进阶。
 *
 * 切换后底栏、工具箱、设置页会各自重组（它们都读 [FeatureMode] 的 Compose 状态），
 * 本卡片只需要把文案和开关位置同步一下。
 */
class UsageModeViewHolder(
    private val binding: HomeUsageModeBinding,
    root: View,
) : BaseViewHolder<Any?>(root) {

    companion object {
        val CREATOR = Creator<Any> { inflater: LayoutInflater, parent: ViewGroup? ->
            val binding = HomeUsageModeBinding.inflate(inflater, parent, false)
            UsageModeViewHolder(binding, binding.root)
        }
    }

    /**
     * 正在用代码同步开关位置。
     *
     * [android.widget.CompoundButton.setChecked] 也会触发监听器，而监听器里又会去写
     * [FeatureMode] —— 不挡一下就会出现「绑定数据 → 触发写入 → 状态变化 → 再次绑定」的回环。
     * 值相同虽是无害写入，但在滚动/重建时会把用户刚点的动作覆盖掉，所以这里显式屏蔽。
     */
    private var syncing = false

    init {
        binding.switchWidget.setOnCheckedChangeListener { _, checked ->
            if (syncing) return@setOnCheckedChangeListener
            FeatureMode.setAdvanced(checked)
            renderSummary()
        }
        // 整张卡片都可点：不用精确点到那个小开关上
        binding.root.setOnClickListener {
            binding.switchWidget.isChecked = !binding.switchWidget.isChecked
        }
    }

    override fun onBind() {
        val advanced = FeatureMode.isAdvanced
        syncing = true
        binding.switchWidget.isChecked = advanced
        syncing = false
        renderSummary()
    }

    private fun renderSummary() {
        binding.text2.setText(
            if (FeatureMode.isAdvanced) {
                R.string.settings_usage_mode_advanced
            } else {
                R.string.settings_usage_mode_simple
            }
        )
    }
}
