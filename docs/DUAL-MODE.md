# 简单模式 / 进阶模式

「功能太多，一眼望过去不知道点哪儿」——为此把界面分两档。

- **简单模式（默认）**：只留日常真正会用到的入口。
- **进阶模式**：全部功能放出来。

开关在**首页**，服务器状态卡下面那张卡片（整张卡可点，不用精确点小开关）。

## 记忆

选择存在偏好里（`ShizukuSettings`，键 `feature_mode`），值只有 `simple` / `advanced`。

`FeatureMode` 是运行时可观察状态（和 `UiStyle`、`NavTabs` 同一套路），所以切换后：

- 底栏立刻按新档位重组；
- 工具箱重新过滤条目；
- 设置页收放对应行。

**不需要重建 Activity**，也不会把用户从当前页面踢出去。

应用启动时，`ShizukuApplication.onCreate()` 会在 `ShizukuSettings.initialize()` **之后**
再调一次 `FeatureMode.refresh()`。这一步不是多余的：`FeatureMode` 是单例，
如果它在偏好初始化完成之前就被谁读了一下，初值会被锁成默认档，
用户上次选的进阶模式就「失忆」了。

## 默认值

默认 `simple`：这个开关本来就是冲「功能太多」来的，第一次装上应该看到清爽的那一档。
想要全部功能的人拨一下，之后一直被记住。

## 简单模式下收起什么

| 位置 | 收起 | 保留 |
| --- | --- | --- |
| 底栏 | 日志 / 模块 / 控制台 | 首页 / 应用 / 工具箱 / 设置 |
| 工具箱 | 模块整组、控制台整组、实验室 | **无障碍** / 激活 / 应用管理 / 设置 |
| 设置 | 底部栏、稳定性与自动化、API 日志 | 界面外观、背景、语言、关于 |

无障碍（`accessibility_manager`）刻意留在简单模式：它属于常用功能，而且除了工具箱没有别的入口。

## 怎么给新功能标档位

**只标一处**，由 `FeatureMode.allows()` 统一裁决：

```kotlin
// 底栏：NavTabs.all 里给 NavTabSpec 加 level
NavTabSpec(KEY_FOO, R.id.foo_fragment, R.string.foo_title, ..., level = FeatureLevel.ADVANCED)

// 工具箱：entry(...) 的第 4 个参数
entry(R.drawable.ic_foo, R.string.foo_title, R.string.foo_summary, FeatureLevel.ADVANCED) { ... }

// 设置页：整行包一层 if
if (FeatureMode.isAdvanced) {
    NavRow(...)
}
```

**不要在界面里零散写 `if (isAdvanced)` 去判断「这个功能属于哪档」** ——
那会变成两处真相，加功能时漏掉一处就会出现「简单模式下还能点到」的破口。
设置页那种包一层的写法只用于「这一行要不要出现」，档位本身仍由 `FeatureLevel` 定义。

`level` 默认是 `SIMPLE`，所以旧代码不改就是「简单模式可见」——加新功能时如果不确定，
先想想它是不是大多数人根本不会点。

## 相关文件

- `feature/FeatureMode.kt` — 档位枚举、状态、持久化
- `home/UsageModeViewHolder.kt` + `res/layout/home_usage_mode.xml` — 首页开关卡片
- `ui/nav/NavTabs.kt` — 底栏过滤
- `toolbox/ToolboxScreen.kt`、`toolbox/ToolboxFragment.kt` — 工具箱过滤
- `settings/SettingsScreen.kt` — 设置行收放
