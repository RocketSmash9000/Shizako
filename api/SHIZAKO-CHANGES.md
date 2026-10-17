# Shizako 对 Shizuku-API 的本地修改

本目录源自 [RikkaApps/Shizuku-API](https://github.com/RikkaApps/Shizuku-API)（MIT License），
自 zako2.1 起由 git 子模块改为直接内置，便于随本仓库一起分发与修改。

上游代码保持原样，仅做了以下改动：

| 文件 | 改动 | 原因 |
|------|------|------|
| `provider/src/main/AndroidManifest.xml` | 追加声明 `<uses-permission android:name="com.churan.shizako.permission.API_V23"/>` | 双管理器支持：使用本库的应用同时请求官方 Shizuku 与 Shizako 两个权限，两台服务器各自只推送、只授予自己定义的权限，因此同一份应用可连接任一管理器 |
| `shared/src/main/java/rikka/shizuku/ShizukuApiConstants.java` | 服务端版本常量与 Shizako 版本体系对齐 | 避免"激活后仍显示上游 13.6 版本号" |

## 为什么不直接声明官方权限让官方 API 应用直连？

上游 Shizuku 的许可（Apache 2.0 附加条款）明确禁止 fork 声明
`moe.shizuku.manager.permission.*` 权限；技术上，重复定义同名 runtime 权限也会在
与官方 Shizuku 并存安装时产生"定义归属取决于安装顺序、卸载即失效"的冲突。
因此本库选择"双权限请求"路线：**换用本库编译的应用**可同时兼容两个生态。

而**未改动的官方 `dev.rikka.shizuku:api` 应用一样能连上 Shizako** —— 靠的是服务端的
「匹配上游权限名 + 按授权记录放行」两条，不需要对方重编译，也不需要本库改动：

1. **推送**。`server/.../ShizukuService.java` 的 `sendBinderToClient()` 遍历已安装包，
   只要清单里声明了下面两者之一就主动推送 binder：
   - `PERMISSION` = `com.churan.shizako.permission.API_V23`（Shizako 自己定义的）
   - `PERMISSION_UPSTREAM_API` = `moe.shizuku.manager.permission.API_V23`（**上游权限名**）

   该处注释写明：*apps built with the official dev.rikka.shizuku:api (e.g. MT Manager)
   only request the upstream permission name. Without this branch they get no binder…*；
   `ServerConstants` 里也注明这个上游权限名「**仅作为匹配条件被引用**，
   Shizako 不声明、不定义该权限」—— 所以既不违反上游许可，又能匹配到官方客户端。

2. **授权**。`api/server-shared/.../Service.java` 的 `checkCallerPermission` 里，
   已 attach 的客户端按**授权记录**放行，而不是按权限名：
   *attached clients are authorized by the server-side grant record (ClientRecord.allowed)
   … they hold no Shizaku permission (undefined on this side), but went through the same
   user-confirmation dialog, so record.allowed is the source of truth.*

   也就是说官方 API 应用持有的是上游权限，Shizako 侧并不检查「你是不是持有 Shizako 的权限」，
   只检查「你有没有被授权过」。授/撤权限时则用它**实际声明的那一个**权限名
   （见 `updateFlagsForUid` 里的同类分支），否则会抛异常。

> ⚠️ 这段曾经写成「未改动的官方 API 应用仍按原样连接官方 Shizuku」，是**错的**，
> 并直接导致过一次误判：有人据此认为官方 API 应用连不上 Shizako，还去"修正"了 README。
> 结论以本节的代码依据为准。

## 与上游同步

```bash
git remote add shizuku-api https://github.com/RikkaApps/Shizuku-API.git
git fetch shizuku-api
git diff HEAD shizuku-api/master -- api/   # 人工核对后合并，保留上述两处改动
```
