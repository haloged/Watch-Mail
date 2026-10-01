# WearMail 架构设计

> 单模块 Wear OS 应用：Kotlin + Jetpack Compose for Wear OS（`androidx.wear.compose:compose-material3`）。

---

## 1. 分层结构

```
┌──────────────────────────────────────────────────────────────────┐
│ UI 层（Compose for Wear OS）                                      │
│  ui/nav      WearMailNav        三页横向分页 + 覆盖层路由          │
│  ui/kit      CircularMetrics / CircularSafeLazyColumn / Components │
│  ui/theme    WearMailTheme（深色 OLED 配色）                       │
│  ui/screen   inbox / detail / compose / accounts / settings       │
│              （每页 = Screen + ViewModel，状态单向流动）            │
└───────────────▲──────────────────────────────┬───────────────────┘
                │ StateFlow（只读状态）          │ 用户意图
┌───────────────┴──────────────────────────────▼───────────────────┐
│ 领域/同步层                                                        │
│  sync/SyncService (接口) ← SyncEngine                              │
│   · 定时前台同步 / WorkManager 后台同步 / 手动刷新                  │
│   · 正文按需加载、标记已读、删除、发送、草稿补投                     │
│   · 唯一持有「网络 + 缓存 + 通知」编排权                            │
│  notify/MailNotifier ← NotificationCenter  （新邮件通知）           │
│  pairing/PairingService ← PairingController（局域网扫码配置）        │
└───────────────▲──────────────────────────────┬───────────────────┘
                │ 领域模型                       │ 仓储接口
┌───────────────┴──────────────────────────────▼───────────────────┐
│ 数据层                                                             │
│  data/repo   AccountRepository / EmailRepository /                 │
│              DraftRepository / ContactRepository （接口 + Impl）    │
│  data/db     MailDatabase(SQLiteOpenHelper) + 5 个 DAO             │
│  data/crypto CryptoManager（AES-256-GCM）+ AndroidKeystoreKeyProvider│
│  data/prefs  SettingsStore（SharedPreferences + StateFlow）        │
└───────────────▲──────────────────────────────────────────────────┘
                │ IMAP/SMTP 协议调用
┌───────────────┴──────────────────────────────────────────────────┐
│ 协议层  mail/                                                      │
│  ImapClient(Impl) / SmtpClient(Impl) / ServerProbe(Impl)           │
│  HtmlTextExtractor / MimeAddressSupport / BackoffPolicy / MailError │
│  实现：JavaMail（com.sun.mail:android-mail）                        │
└──────────────────────────────────────────────────────────────────┘
```

**依赖方向**：UI → 同步层 → 数据层 → 协议层。协议层不认识 Android/Wear；
数据层不认识网络；UI 只依赖接口（`SyncService`、仓储接口），便于替换与测试。

依赖注入使用**手写容器** `core/AppContainer`（14 个单例，全部 `by lazy`）。

## 2. 数据模型与存储

### 2.1 领域模型（`model/`）

| 模型 | 说明 |
|------|------|
| `Account` | 账户配置。**刻意不含密码字段**，防止误打印/误落库 |
| `AccountSecrets` | 凭据容器（内存态），仅在加密落盘前短暂存在；`password` 与 `oauth` 二选一 |
| `OAuthTokens` | OAuth2 令牌组（access + refresh + 过期时间），`serialize()` 后整体加密存入 `oauth_token_enc` |
| `EmailMeta` | 邮件元数据（本地主键 + accountId + folder + uid + 头部字段 + flag） |
| `EmailBody` | 正文缓存 |
| `Draft` | 草稿（含 `lastError`，非空表示需重试） |
| `MailFolder` / `Contact` / `MailAddress` | 辅助模型 |
| `SyncReport` / `SyncUiState` / `SendState` | 同步与发送状态 |

### 2.2 数据库表（`data/db/MailDatabase.kt`，SQLite，库名 `wearmail.db` v1）

| 表 | 关键列 | 说明 |
|----|--------|------|
| `accounts` | `email` UNIQUE、`password_enc`、`oauth_token_enc` | 凭据列为**密文**（`v1:base64(iv):base64(ct)`） |
| `emails` | `UNIQUE(account_id, folder, uid)`、`date_millis`、`is_read`、`last_access_at` | 元数据缓存；外键级联删除 |
| `email_bodies` | `email_id` PK、`body_text`、`last_access_at` | 正文缓存，按需写入 |
| `drafts` | `account_id`、`last_error` | 离线草稿 |
| `contacts` | `address` UNIQUE、`used_count` | 常用联系人（发件人自动累积） |

索引：`emails(date_millis DESC)`、`emails(account_id, folder)`、`emails(is_read)`、
`email_bodies(last_access_at)`、`contacts(used_count DESC, last_used_at DESC)`。
`onConfigure` 中开启外键约束（`setForeignKeyConstraintsEnabled(true)`）。

**缓存上限与淘汰（LRU）**：元数据 500 封 / 正文 50 封（可配置）。
`EmailRepository.enforceLimits()` 按 `last_access_at ASC, date_millis ASC` 删除超出部分；
每次同步结束后由 `SyncEngine` 调用一次。

### 2.3 为什么不用 Room

需求允许「如 SQLite」的轻量方案。Room 需要 KSP/kapt 注解处理，
在手表这种小工程上会增加构建时间与版本耦合风险（KSP 与 Kotlin 版本强绑定）；
4 张表、10 余条查询用 `SQLiteOpenHelper` 手写更可控，且**零注解处理器依赖**。

## 3. 同步策略（`sync/SyncEngine.kt`）

### 3.1 三种触发源

| 触发 | 频率 | 实现 |
|------|------|------|
| 前台定时 | 每 5 分钟（可配 5/15/30/仅手动） | `startForegroundLoop(scope)`，随 Activity `onStart/onStop` 启停 |
| 后台定时 | 每 15 分钟起（WorkManager 下限），`NetworkType.CONNECTED` 约束 | `scheduleBackgroundSync()` + `SyncWorker` |
| 手动 | 下拉刷新 | `syncAll(SyncReason.MANUAL)` |

所有同步经 `Mutex.withLock` 串行化，避免多路并发打爆电台功耗。

### 3.2 首次同步 vs 增量同步

- **首次**（`latestUid == 0`）：`fetchRecent(limit = 50)`，**只拉元数据**
  （From/To/Subject/Date/Flags/Size），正文不下载；
- **增量**：`UID > lastUid`（`UIDFolder.getMessagesByUID`），失败或 UID 不连续时
  退化为 `SINCE 上次同步时间 − 24h` 的日期条件；
- **正文按需加载**：仅当用户点开邮件时 `loadBody()`，先查缓存再下载；
- **IDLE 推送**：`ImapClient.idle()` 在服务端支持时使用 `IMAPFolder.idle()`，
  不支持则退化为 30 秒轮询；断线用指数退避重连。

### 3.3 断线重连与超时

- 连接/读写超时统一 **10 秒**（JavaMail 属性 `mail.imap(s).connectiontimeout/timeout`）；
- 发送整体超时 20 秒；
- `BackoffPolicy`：初始 1s、倍率 2.0、上限 60s、±20% 抖动；
- `MailError.isRetryable` 区分「可重试」（网络/超时/协议）与「不可重试」（认证/配置），
  后者不重试，直接要求用户修正。

### 3.4 离线降级

| 场景 | 行为 |
|------|------|
| 无网络同步 | 直接返回失败，UI **继续展示本地缓存**，顶部显示「网络不可用，已展示本地缓存」 |
| 无网络打开邮件 | 有缓存直接读缓存；无缓存提示离线 |
| 发送失败 | **自动存为草稿**（含失败原因），联网后 `flushPendingDrafts()` 自动补投 |
| 标记已读/删除失败 | 标记：本地先改、远端失败不回滚；删除：远端成功才删本地 |

## 4. 安全设计

### 4.1 凭据加密（红线：不得明文落盘）

```
用户输入密码
   → AccountSecrets（仅内存）
   → CryptoManager.encrypt()   AES-256-GCM，随机 12 字节 IV，128 位 tag
   → "v1:<base64(iv)>:<base64(ct)>"  写入 accounts.password_enc
读取时：CryptoManager.decrypt() → 失败返回 null（并记 warn，不记录密文）
```

- 密钥由 **Android Keystore** 生成并保管（别名 `wearmail_master_key`，AES-256，
  `setUserAuthenticationRequired(false)`），密钥本身不可导出；
- **IV 由加密器生成**：Keystore 密钥默认 `setRandomizedEncryptionRequired(true)`，
  不允许调用方自带 IV（否则抛 `InvalidAlgorithmParameterException`）——
  因此加密时 `cipher.init(ENCRYPT_MODE, key)` 不传 IV，再从 `cipher.iv` 读回并随密文保存；
  解密时由调用方提供该 IV（该限制只作用于加密方向）；
- GCM 为认证加密：密文被篡改时解密失败，不会返回错误明文；
- `Account` 模型不含密码字段，从类型上杜绝「顺手把密码写进数据库/日志」。

### 4.2 日志红线

`core/Logs.kt` 为唯一日志出口，约定：

- **禁止**输出：密码、OAuth 令牌、邮件正文、密文、扫码页面提交的表单内容；
- **允许**输出：服务器主机/端口、UID、错误码、耗时、条数。

### 4.3 局域网扫码配对的安全边界

- 仅在用户主动进入该界面时监听，离开页面 `onDispose` **立即关闭端口**；
- 绑定到 Wi-Fi 局域网地址，不对外网暴露；
- 页面与提交请求都校验**一次性随机 token**（`UUID`），防止同网段其它设备误提交；
- 提交的凭据同样经 `CryptoManager` 加密后落盘；
- 服务器实现（`ConfigWebServer`）**不依赖任何 Android API**，因此可被纯 JVM 单元测试覆盖。

### 4.4 其他

- `AndroidManifest` 中 `allowBackup=false`：避免备份通道泄露数据库；
- 仓库内只提交**调试用**自签名证书（`keystore/debug.keystore`，口令 `android`），
  正式发布必须替换为独立证书（`.gitignore` 已排除 `*.jks`）；
- 邮件传输始终使用 SSL/TLS（993/465）或 STARTTLS（143/587）；
  `usesCleartextTraffic=true` **仅**服务于局域网配置页面，与邮件协议无关。

### 4.5 OAuth2 授权与令牌续期（Outlook / Office 365）

微软已对 Exchange Online 与 Outlook.com **停用 IMAP/SMTP 基础认证**，Outlook 账户只能走
OAuth2 + SASL XOAUTH2。实现分布在 `mail/oauth/`：

| 组件 | 职责 |
|------|------|
| `FormPoster` / `HttpFormPoster` | 表单 POST 抽象（`java.net`，无第三方网络库）。4xx 也返回正文 —— OAuth2 的错误信息在 JSON 里 |
| `MicrosoftOAuth` | 端点、scope、设备码申请、轮询状态机、refresh；解析函数是**纯函数**（显式传入 `nowMillis`） |
| `OAuthTokenService` | 与仓储/设置粘合：`freshSecrets()` 在同步前按需刷新并写回加密存储；`forceRefresh()` 用于认证失败后的重试 |
| `model.OAuthTokens` | 令牌组 + `serialize()/parse()`；`needsRefresh()` 提前 2 分钟判定 |

**为什么选设备码流（device authorization grant）**：手表没有可用的浏览器控件，
466px 圆屏上也放不下 OAuth 同意页。设备码流正是为输入受限设备（电视/打印机/物联网）设计的：
手表只显示短码与授权网址，登录在手机浏览器完成，手表轮询取令牌。授权网址可以渲染成二维码
（复用扫码配对的 ZXing 渲染器）。微软**不支持** `verification_uri_complete`，因此二维码里
只能放网址，短码仍需在手机上手动输入。

**scope（依据微软官方文档）**：

```
offline_access                                     ← 才会返回 refresh token
https://outlook.office.com/IMAP.AccessAsUser.All   ← IMAP 收信
https://outlook.office.com/SMTP.Send               ← SMTP 发信
```

租户默认 `common`（同时支持个人账号与企业账号），可在设置页改成具体租户 ID。

**令牌续期策略**（三层，缺一不可）：

1. **提前刷新**：`needsRefresh()` 在过期前 2 分钟即为真 —— 手表网络慢，
   等到过期才刷会让一次同步以"认证失败"告终；
2. **集中入口**：所有网络调用都经 `SyncEngine.credentialsFor()` / `withOAuthRetry()`，
   避免"忘了在某个入口刷新"；
3. **失败重试**：认证失败时 `forceRefresh()` 再试一次（令牌可能被用户在别处撤销，
   或手表时钟偏差导致本地误判"未过期"），只重试一次，避免密码真错时反复打服务器。

**存储不加字段**：令牌组 `serialize()` 成单行文本后整体加密写入既有的
`accounts.oauth_token_enc` 列 —— 无需数据库迁移，refresh token 同样是密文。
**令牌绝不进入任何 `data class`**（避免 `toString()` 泄露），`AddAccountViewModel` 用私有字段持有。

**XOAUTH2 的接线**：`mail.imap.auth.mechanisms=XOAUTH2`（IMAP）与
`mail.smtp.auth.mechanisms=XOAUTH2`（SMTP），并把 access token 放在"密码"位置 ——
JavaMail 会据此拼出 `user=<邮箱>^Aauth=Bearer <token>^A^A`。用户名必须是邮箱地址。
`AccountSecrets.authSecret` 同时兼容「授权得到的令牌」与「用户手动粘贴的令牌」。

## 5. 性能与功耗

| 目标 | 措施 |
|------|------|
| 冷启动 < 2s | `AppContainer` 全部 `by lazy`；启动路径无网络、无数据库查询；账户列表后台异步预热；首个 UI 帧只渲染空态 |
| 列表 ≥ 30fps | 列表项宽度用 `derivedStateOf` 计算（仅在布局变化时重算）；`items(key = mail.id)` 保证复用；文本单行截断减少重排 |
| 单次同步 < 5s | 首次仅 50 封且**只取头部**；增量按 UID；连接超时 10s 兜底；后台与前台同步互斥 |
| 内存峰值 < 50MB | 正文上限 20 万字符（超出截断）；元数据 500 封 / 正文 50 封 LRU；不缓存 Bitmap 大图（仅配对二维码 300×300） |
| 功耗 | 后台同步交给 WorkManager（系统统一调度，≥15 分钟）；前台同步随界面可见性启停；Wi-Fi 可选约束；IDLE 仅在支持时启用 |
| 网络耗时 | JavaMail 超时属性显式设置；`Dispatchers.IO` 承载所有阻塞 IO |

> **实测数据（本机构建验证）**：debug APK **27.76 MB**；release APK 经 R8 压缩 + 资源压缩后
> **3.35 MB**（体积下降 88%，对 512MB–1GB 内存的手表更友好）；
> 211 个单元测试总耗时约 **0.7 秒**（纯 JVM，无需设备）。
> 冷启动耗时、列表帧率、同步耗时、内存峰值需真机 Profiler 验证，见 `docs/TESTING.md` §6。

## 6. 关键设计取舍

| 决策 | 选择 | 理由 |
|------|------|------|
| 依赖注入 | 手写 `AppContainer` | 14 个单例，避免注解处理器拖慢手表工程构建 |
| 本地存储 | `SQLiteOpenHelper` | 表少、查询简单；零注解处理，版本风险低 |
| 导航 | `HorizontalPager` + 覆盖层 | 需求要求「左滑账户 / 右滑设置」，分页天然匹配手势；比引入 Navigation 组件更轻 |
| 列表 | 自实现 `CircularSafeLazyColumn`(LazyColumn) | 需求要求按 Y 动态计算弦长；`ScalingLazyColumn` 自带径向缩放，与弦长收窄叠加会互相干扰 |
| 文本输入 | 自建 `WearTextField`(BasicTextField) | Wear Material3 1.5.6 **没有** TextField 组件 |
| 图标 | 仅 `material-icons-core` + `WearIcons` 收口 | `extended` 含上千矢量资源，会显著增大手表 APK |
| 图标/组件 | 不使用 `Chip` | Wear M3 1.5.6 **没有** Chip，用 `Button` 表达选中态 |
| 系统右滑退出 | 禁用 | 与「右滑进入设置」冲突 |
| 左右滑删除 | 改为长按菜单 + 二次确认 | 与页面切换手势冲突；删除不可逆，代价更高 |
| 通知权限 | 运行时申请 | Android 13+ 未授权会导致通知被静默丢弃 |

## 7. 依赖与版本矩阵

| 依赖 | 版本 | 用途 |
|------|------|------|
| AGP / Gradle / Kotlin | 8.7.3 / 8.11.1 / 2.1.0 | 构建 |
| `androidx.wear.compose:compose-material3` | 1.5.6 | Wear UI 组件（**需求指定**） |
| `androidx.wear.compose:compose-foundation` | 1.5.6 | Wear 基础（弧线文字等） |
| `androidx.compose.*` | 1.8.2 | Compose 基础（wear-compose 1.5.6 的编译基线） |
| `androidx.compose.material:material-icons-core` | 1.7.7 | 图标（仅 core 集） |
| `androidx.activity:activity-compose` | 1.10.1 | `ComponentActivity` + `setContent` + `BackHandler` |
| `androidx.lifecycle:*` | 2.8.7 | ViewModel、`collectAsStateWithLifecycle` |
| `androidx.work:work-runtime-ktx` | 2.9.0 | 后台周期同步 |
| `com.sun.mail:android-mail` / `android-activation` | 1.6.7 | IMAP / SMTP 协议实现 |
| `com.google.zxing:core` | 3.5.3 | 扫码配对二维码 |
| `kotlinx-coroutines-android` | 1.9.0 | 协程 |
| `junit` / `kotlinx-coroutines-test` | 4.13.2 / 1.9.0 | 单元测试 |
| `org.json:json` | 20240303 | **仅单元测试**：Android 平台自带 `org.json`，纯 JVM 单测里只是桩实现 |
| compileSdk / targetSdk / minSdk | 35 / 35 / 30 | Wear OS 3.0+ |

## 8. 错误处理约定

- 协议层：所有异常必须包装为 `MailError`（`Network/Auth/Timeout/Protocol/Config/Unknown`）后
  通过 `Result.failure` 返回，**不允许抛出裸异常**；
- 同步层：以 `Result` / `AccountSyncResult.errorMessage` 上抛，统一取 `MailError.userMessage`
  作为中文提示；
- UI 层：错误内联展示（`ErrorBanner` / `AlertDialog`），**不用 Toast**（圆屏易裁切），
  并提供重试入口；错误提示不放服务器内部细节，避免信息过载；
- 所有 `runCatching` 兜底路径都记日志，避免"静默失败"。
