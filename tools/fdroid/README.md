# 上架 F-Droid：准备清单与拦路虎

> 这份文档记录把 Shizako 提交到 F-Droid 需要做的事。recipe 见 `com.churan.shizako.yml`，
> 实施方案与进度见 `变体方案.md`（第六节有逐项勾选）。
>
> ## 提交状态（2026-10-01 首次提交 → 2026-10-02 按模板重提）
>
> **Merge Request：<https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50819>**
> （`!50819`，标题 **`New app: Shizako`**，源分支 `cr1459/fdroiddata:com.churan.shizako` → `master`）
>
> 首次提交被维护者 linsui **程序性关闭**：`Closing because Merge Request template is not followed.`
> 已按「App inclusion」模板逐条对照修正并重新打开：
>
> | 问题 | 修正 |
> |---|---|
> | 标题格式 | `add com.churan.shizako (Shizako)` → **`New app: Shizako`** |
> | `commit` 用了 tag | 改为完整哈希 `77beb7ed…`（模板明确要求不许用 tag/分支） |
> | 上游缺 Fastlane 元数据 | 新增 `fastlane/metadata/android/{en-US,zh-CN}/`：标题、短/长描述、changelog 313、512 图标、特色图、真机截图 |
> | 没用模板 | 描述改为模板全文 + 逐条勾选（4 项如实留空并写明原因：RFP 无、可复现构建待指导、abi split 不需要、CI 需他们触发） |
>
> 提交前做过「模拟 F-Droid 构建机」的验证：**全新克隆 + 冷依赖缓存 + 只用官方源
> （无阿里云镜像）**，跑 recipe 里那条 `:manager:assembleRelease -Pfdroid`
> → `BUILD SUCCESSFUL in 12m 54s`，产出 `shizako-zako3.13-f-release.apk`（8.22 MB）。
> 上游 MR 普遍不带流水线（同期 AppShelf / Perfica / Cryo / OsmAnd 等 10 条均无），
> fork 侧 failed 是环境噪声。
> 现在等 review；第 4 节列了可能被追问的点。

> ## 现状（截至 2026-10-17，zako3.16hp）
>
> - 应用侧已到 **zako3.16hp**（versionCode 仍 **316**，只有 `versionName` 加 `hp` 后缀，
>   沿用 `zako3.01-hp` 的处理方式）。zako3.16 移除了整套兼容占位包（`:stub` 模块、
>   `copyStubApk`、`StubManager`），3.16hp 又修了配对资源回收与看门狗恢复出口。
> - **recipe 仍有意留在 `zako3.15` / versionCode 315**（`f1a8e59`），尚未前移 ——
>   前移时机与要改的那一处措辞见仓库 `issues/fdroid-zako316-跟进.md`。
>   注意：3.16 起 `MaintainerNotes` 里「只有 F-Droid 变体才停发占位包」这句已不准确，
>   因为 GitHub 版也一并拔除了；前移 pin 时请顺带改掉。
> - 前移 pin 需要的新值：`versionName: zako3.16`、`versionCode: 316`、
>   `commit:` 指向 `zako3.16` 对应提交；`CurrentVersion` / `CurrentVersionCode` 同步。
>
> ## 现状（截至 2026-10-16，zako3.15）
>
> - reviewer **linsui 亲手试用后报了两个问题**：配对配不上；配对失败之后仍显示「已配对」。
>   两者都是本项目自己的 bug，已在 **zako3.15** 修掉，回复见 MR 的
>   [note 4023333516](https://gitlab.com/fdroid/fdroiddata/-/merge_requests/50819#note_4023333516)。
> - recipe 原本 pin 在 `1f1c4f33`（versionCode 313）—— 比 3.13 的三个修复还早，
>   照它构建出来的 F-Droid 包正好带着 reviewer 碰到的那批 bug。pin 已前移到
>   `f1a8e59`（versionCode 仍是 **315**），并补写了 `MaintainerNotes`。
> - **该变体已不再声明 `REQUEST_INSTALL_PACKAGES`**（此前留着，但这个变体里没有
>   任何使用方）。做法是只做减项的 manifest 覆盖层
>   `manager/src/fdroid/AndroidManifest.xml`（`tools:node="remove"`），仅在 `-Pfdroid`
>   时由 `androidComponents` 喂给变体 —— 仍然不是 product flavor，任务名 / 产物路径 /
>   变体名都不变，recipe 一个字都不用改；GitHub 版不受影响。细节见 `变体方案.md` 第二节。
> - `tools/fdroid/com.churan.shizako.yml` 现在**与 MR 里的文件逐字一致**（按
>   `fdroid rewritemeta` 规范化，无注释），可直接整份覆盖到 fdroiddata 的 `metadata/`。
>   注意：文件里的 `commit:` 指着某次提交，那次提交自己不可能包含这份文件，
>   所以仓库这份副本相对 pin 永远差一次提交，差异只有这一行。
> - recipe 用 `gradleprops: [fdroid]` 传 `-Pfdroid`。pin 的提交必须能带这个开关构建；
>   本机验证要挂代理，因为该开关会关掉国内镜像、改走官方源：
>
>   ```
>   GRADLE_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890" \
>     ./gradlew :manager:assembleRelease -Pfdroid --no-daemon
>   ```
> - 待办：等 linsui 在 fdroiddata 侧再触发一次流水线（fork 侧流水线一向无作业、直接 failed）；
>   可复现构建（RBS）尚未开启，需要维护者给签名指纹方面的指导。
> - GitLab 通道：`gitlab.com` 的 API **直连可用**（本机 curl 要加 `--ssl-no-revoke`），
>   不需要 7890 代理；改文件用 `PUT /projects/:id/repository/files/:path` +
>   `encoding=base64`，比 clone 整个 fdroiddata 轻得多。

## 1. 提交方式

F-Droid 不用 GitHub，走 **GitLab**：

1. 注册/登录 <https://gitlab.com>
2. Fork <https://gitlab.com/fdroid/fdroiddata>
3. 把 `com.churan.shizako.yml` 放到 fork 的 `metadata/` 目录
4. 提 Merge Request（模板会要求你自查一遍下面的清单）

## 2. 两个硬拦路虎 —— 均已解决

### 2.1 仓库里有预编译二进制 —— 已解决，且第二个已彻底消失

仓库里**没有任何预编译二进制入库**。当前 `manager/src/main/assets/` 只有两个文件：

| 文件 | 现状 |
|---|---|
| `manager/src/main/assets/rish` | 启动脚本（882 B），随源码入库 |
| `manager/src/main/assets/rish_shizuku.dex` | ✅ 未入库（`.gitignore` 忽略），由 `:shell:assemble` 构建时生成 |
| ~~`manager/src/main/assets/shizako-stub.apk`~~ | ✅ **已彻底移除（zako3.16）**：`:stub` 模块、`copyStubApk` 任务、`StubManager` 全部删除，占位包不再构建也不再打包。同时 `manager/build.gradle` 里保留了一个无条件的 `removeStubApk` 清理任务，防止旧工作区里的历史文件被重新打进 APK |

验证过：删掉盘上的生成物后重新构建，产物里仍含 `rish` / `rish_shizuku.dex`（新鲜克隆等价场景）。

### 2.2 构建环境：离线 + 国内镜像 + NDK —— 已解决

- 镜像：`settings.gradle` 检测到 `-Pfdroid` 时**自动关闭全部阿里云镜像**，
  改走 google() / mavenCentral() / gradlePluginPortal()。GitHub 版（不带参数）不变。
  依赖仓库因此**不需要** sed。
- Gradle 发行包：仓库 wrapper 指向国内镜像 `mirrors.cloud.tencent.com`（国内开发机下载快），
  recipe 用 `prebuild:` 在构建机上改回官方 `services.gradle.org` —— 构建机出网白名单
  不一定覆盖国内镜像，官方地址最稳，且不改仓库、不影响本地开发。
- 离线验证：`-Pfdroid --offline` 配置与解析通过（本机缓存热；F-Droid 构建机
  对允许源有预置/代理机制 —— 他们 metadata 里 `sudo: curl nodejs.org` 是白名单网络的旁证）。
- NDK：根 `build.gradle` 与 recipe 均为 **29.0.14206865**（一致 ✔）。
  仍需 MR 时确认 F-Droid 构建机有这一版，没有就降级并同步改两处。

## 3. 上架前还要知道的三件事

| 事项 | 影响 |
|---|---|
| **签名不同** | F-Droid 用它自己的密钥签名 APK → 用户**无法从 GitHub 版覆盖升级**（必须卸载重装）。这是 F-Droid 的固有限制，几乎所有应用都这样。stub 的权限是 `dangerous` 级（校验在本体），换签名后运行时无碍 ✔ |
| **版本号规范** | `versionName` 形如 `zako3.16`（F-Droid 版带 `-f` 后缀），recipe 里 `versionCode` **每次发版递增**。当前应用侧已是 `zako3.16hp` / versionCode **316**，但 recipe 有意仍停在 `zako3.15` / **315**（前移时机见文首「现状」） |
| **与官方 Shizuku 的关系** | **可以共存**。两个变体都只声明自己的 authority `com.churan.shizako.shizuku`，不再声明上游 authority，因此不会与官方 Shizuku 冲突（旧版曾声明上游 authority，那时的确不能共存 —— 该行为已在 zako3.16 移除，见 `manager/build.gradle` 里 `manifestPlaceholders` 上方的注释）。F-Droid 审核若问起，按此说明：自用 authority、不占用上游任何标识 |

## 4. 审核可能追问的点（提前准备答复）

1. **为什么用自有的权限名** → 上游许可禁止 fork 使用官方 app id 与权限名（`NOTICE` 已声明）
2. **更新检查请求 api.github.com** → F-Droid 版已彻底关闭自更新与更新检查
   （`FdroidBuild.allowSelfUpdate`，设置页连入口都不显示），该问题不复存在
3. **预编译二进制的来源与构建方式** → 见 2.1，全部构建时从本仓库源码生成
4. ⚠️ **MR 时验证**：libsu（`com.github.topjohnwu.libsu`）只发布在 jitpack.io
   （Maven Central 实测 404）→ 需确认 jitpack 在构建机依赖源允许范围内；
   备选：srclibs 源码构建，或换一个 Maven Central 上的 root 库
5. ⚠️ **MR 时验证**：recipe `UpdateCheckData` 从根 build.gradle 提取的 versionName
   不带 `-f` 后缀 → 若 fdroiddata linter 报不一致，把后缀下沉进根 versionName

## 5. 执行顺序（与 变体方案.md 第六节同步）

1. ✅ 预编译二进制改成「构建时生成」→ 双变体构建验证
2. ✅ 镜像与 NDK（`-Pfdroid` 变体集中差异）→ 纯官方源 + `--offline` 模拟验证
3. ⬜ 发一个**与 recipe 完全对应的 tag**（例如 `zako3.13`），versionCode 递增
4. ⬜ 用 `com.churan.shizako.yml` 提 MR（先过第 4 节两个 ⚠️）

## 6. 本机构建环境备忘（给以后的会话）

> 以下为 **2026-10-17 实测可用**的环境；已随项目迁移更新过一次（见下方「已过期」）。

- **仓库位置**：`E:\DeepSeek Work\Shizako-KernelSU-UI-Desktop-20261001`
  （全 ASCII，只含一个空格 —— 实测可直接构建，无需 junction）。
- **环境变量**（构建前设置，缺一不可）：
  - `JAVA_HOME = E:\DeepSeek Work\_tools\sdk\jdk\jdk-21.0.2`
    —— **必须 JDK 21**：根 `build.gradle` 是 `VERSION_21`、`manager` 是 `jvmTarget = "21"`，
    仓库未声明 toolchain，Gradle 直接用 `JAVA_HOME`。用 JDK 8/17 会在编译阶段失败。
  - `GRADLE_USER_HOME = E:\DeepSeek Work\_tools\gradle-home`
    —— 依赖缓存与 wrapper 发行包都在这里（**不在** `~/.gradle`）。
- **Android SDK**：`E:\DeepSeek Work\_tools\sdk`（写在 `local.properties` 的 `sdk.dir`）。
- **代理**：GitHub 相关操作走 `127.0.0.1:7890`；Gradle 访问官方仓库时需显式给 JVM 传代理
  （系统代理只有 PowerShell/WinINET 用，Java 不读）：
  ```
  -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=7890 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=7890
  ```
  本地依赖已齐全时可加 `--offline` 跳过网络。
- **切换变体时给 `-Pkotlin.incremental=false`**：`-Pfdroid` 翻转 BuildConfig 常量
  触发 K2 增量重编，会出现一批「同模块兄弟类 Unresolved」的幽灵报错
  （报错位置漂移、import 行起头）→ 见到这个形态先怀疑增量缓存，加参数重跑，
  或删 `manager/build/kotlin` + 杀 Kotlin daemon。
- **签名**：`signing.properties` 在仓库根（被 gitignore）。注意 `signing.gradle` 是
  `apply from` 在 **`manager/build.gradle` 末尾**，所以 `file(...)` 相对 **`manager/`** 解析，
  写 `../shizako.jks` 指向仓库根的 `shizako.jks` 才对。**该文件读不到时 `signing.gradle`
  会静默回落到 debug 签名** —— 发版前务必用 `apksigner verify --print-certs` 核对指纹。
- **发版构建 GitHub 版时记得**：AGP 会清掉同变体旧产物，两个包不会同时留在
  `manager/build/outputs/apk/release/`；需要保留就及时把产物拷进 `out/apk/`
  （`:manager:assembleRelease` 的 `doLast` 也会自动往 `out/apk/` 拷一份）。

### 已过期，不要照抄

- ~~必须经 ASCII junction `E:\build\Shizako-KernelSU-UI` 构建~~ —— 那是项目还在
  `E:\工作区\Shizako-KernelSU-UI`（**中文路径**）时的事；项目已迁到上面那个 ASCII 路径，
  junction 与 `android.overridePathCheck` 都不再需要。
- ~~`JAVA_HOME=E:\jdk21`、`GRADLE_USER_HOME=E:\gh`、`-Djava.io.tmpdir=E:/tmp`~~ ——
  这些是旧机器的路径。另外 `E:/tmp` 这种绝对临时目录**绝对不要**写进被跟踪的
  `gradle.properties`（CI 与 F-Droid 构建机是 Linux，会秒挂，见该文件注释）。
