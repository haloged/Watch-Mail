# WatchMail 技术文档

> 本文档面向开发者，涵盖技术选型、数据库 Schema、核心模块实现、圆形表盘适配推导与异常处理策略。
> 项目介绍与使用说明请参阅 [README.md](README.md)。

---



## 1. 技术选型说明

| 项目 | 选型 | 理由 |
|------|------|------|
| 开发语言 | Kotlin 2.3 | 需求指定；空安全、协程适合 IO 密集的邮件同步 |
| UI 框架 | Jetpack Compose + Wear Compose | 圆形表盘原生支持（Scaling/圆形裁剪/表冠 API），声明式 UI 利于 466px 小屏精细布局 |
| 邮件协议 | JavaMail（`com.sun.mail:jakarta.mail`） | 同时覆盖 **IMAP（收件）** 与 **SMTP（发件）**，成熟稳定，支持 SSL/TLS/STARTTLS、UID FETCH、Flags |
| 本地数据库 | Room（SQLite） | 编译期校验 SQL、Flow 响应式查询、天然支持 LRU 清理所需的 `ORDER BY cachedAt` 索引 |
| 后台同步 | WorkManager | 遵循系统功耗规范，最小周期 15 分钟，网络约束 + 指数退避，进程被杀可恢复 |
| 设置持久化 | DataStore（Preferences） | 异步无 ANR，比 SharedPreferences 更适合协程链路 |
| 扫码配对 | 自研轻量 HTTP 服务（ServerSocket）+ ZXing + 纯 JS 密码学 | 手表免打字配置；零额外服务端依赖；**纯 JS SHA-256-CTR + HMAC 加密**（HTTP 下可用，不依赖 WebCrypto） |
| 密码加密 | Android KeyStore + AES-GCM | 密钥永不落盘、不出 TEE/StrongBox；IV 随密文存储，认证失败即拒解密 |
| 异步模型 | Kotlin Coroutines + Flow | 单线程 IO 调度，避免手表上多线程内存峰值 |

> 收件必须走 **IMAP**，发件必须走 **SMTP**；两者均已独立实现，见 `data/remote/imap/` 与 `data/remote/smtp/`。

---

## 2. 项目目录结构

```
app/src/main/java/com/haloged/watchmail/
├── WatchMailApplication.kt        # 应用入口、后台同步初始化（频率由设置驱动）
├── MainActivity.kt                # 导航宿主、通知点击路由、通知运行时权限
├── data/
│   ├── local/
│   │   ├── WatchMailDatabase.kt   # Room 数据库（4 表）
│   │   ├── SettingsRepository.kt  # DataStore 设置持久化
│   │   ├── dao/                   # AccountDao / EmailDao / EmailBodyDao / DraftDao
│   │   └── entity/                # AccountEntity / EmailEntity / EmailBodyEntity / DraftEntity
│   ├── remote/
│   │   ├── imap/ImapSyncManager.kt# IMAP 收件同步（认证/增量/正文/标记/删除）
│   │   ├── smtp/SmtpSendManager.kt# SMTP 发件（SSL/STARTTLS/AUTH）
│   │   ├── pairing/               # 扫码配对：QrPairingServer + 手机端 H5
│   │   │   ├── QrPairingServer.kt # 本地 Web 服务 + AES-GCM 加解密
│   │   │   └── PairingWebPage.kt  # 手机端配置表单（内嵌 H5）
│   │   └── EmailAutoConfigDetector.kt # 服务商配置自动探测
│   └── repository/EmailRepository.kt  # 本地+远程协调、LRU 缓存、精确新增计数
├── service/
│   └── EmailSyncWorker.kt         # 周期/一次性同步 Worker + 新邮件通知 Worker
├── ui/
│   ├── components/                # WatchIconButton(48dp) / EmailListItem / FilterChip ...
│   ├── screen/
│   │   ├── inbox/InboxScreen.kt   # 统一收件箱（主页面）
│   │   ├── detail/EmailDetailScreen.kt
│   │   ├── compose/ComposeScreen.kt
│   │   ├── drafts/DraftsScreen.kt
│   │   ├── account/               # AccountScreen / AddAccountScreen / EditAccountScreen / PairScreen
│   │   └── settings/SettingsScreen.kt
│   ├── theme/                     # WatchMailColors / WatchMailTypography
│   ├── util/CircularDisplayUtil.kt# 圆形几何：弦长/安全区/边缘渐隐（px 单位）
│   └── viewmodel/MainViewModel.kt
└── util/                          # EncryptionUtil(KeyStore+AES-GCM) / HapticUtil / EmailConfigUtil / NetworkUtil / QrCodeUtil
```

---

## 3. 数据库 Schema（SQL DDL）

```sql
-- 账户配置：密码列只存 AES-GCM 密文（IV 拼接 Base64），绝不存明文
CREATE TABLE accounts (
    id                 INTEGER PRIMARY KEY AUTOINCREMENT,
    email              TEXT    NOT NULL,
    encryptedPassword  TEXT    NOT NULL,          -- KeyStore+AES-GCM 密文
    alias              TEXT    NOT NULL,
    imapHost           TEXT    NOT NULL,
    imapPort           INTEGER NOT NULL,
    imapEncryption     TEXT    NOT NULL,          -- SSL / TLS / STARTTLS / NONE
    smtpHost           TEXT    NOT NULL,
    smtpPort           INTEGER NOT NULL,
    smtpEncryption     TEXT    NOT NULL,
    color              INTEGER NOT NULL,          -- 账户标识色（UI 小圆点）
    isEnabled          INTEGER NOT NULL DEFAULT 1,
    notificationEnabled INTEGER NOT NULL DEFAULT 1,
    lastSyncTime       INTEGER NOT NULL DEFAULT 0,
    createdAt          INTEGER NOT NULL
);

-- 邮件元数据：首次同步只写本表（最多缓存 500 条，LRU）
CREATE TABLE emails (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    accountId         INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    uid               INTEGER NOT NULL,           -- IMAP UID，增量同步游标
    folder            TEXT    NOT NULL DEFAULT 'INBOX',
    fromAddress       TEXT    NOT NULL,
    fromName          TEXT,
    toAddress         TEXT    NOT NULL,
    subject           TEXT    NOT NULL,
    preview           TEXT    NOT NULL DEFAULT '',
    receivedAt        INTEGER NOT NULL,
    isRead            INTEGER NOT NULL DEFAULT 0,
    isStarred         INTEGER NOT NULL DEFAULT 0,
    hasAttachment     INTEGER NOT NULL DEFAULT 0,
    flags             TEXT    NOT NULL DEFAULT '',
    isBodyDownloaded  INTEGER NOT NULL DEFAULT 0,
    cachedAt          INTEGER NOT NULL            -- LRU 时间戳，读取时刷新
);
CREATE INDEX idx_emails_account     ON emails(accountId);
CREATE UNIQUE INDEX idx_emails_uid  ON emails(uid, accountId);
CREATE INDEX idx_emails_receivedAt  ON emails(receivedAt);
CREATE INDEX idx_emails_cachedAt    ON emails(cachedAt);   -- LRU 淘汰用

-- 邮件正文：点击详情时按需下载（最多缓存 50 条，LRU）
CREATE TABLE email_bodies (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    emailId      INTEGER NOT NULL REFERENCES emails(id) ON DELETE CASCADE,
    bodyText     TEXT    NOT NULL,                -- 纯文本（HTML 已剥离标签）
    bodyHtml     TEXT,                            -- 原始 HTML 备份
    downloadedAt INTEGER NOT NULL                 -- LRU 时间戳
);
CREATE INDEX idx_bodies_email ON email_bodies(emailId);
CREATE INDEX idx_bodies_lru   ON email_bodies(downloadedAt);

-- 草稿：发送失败/离线自动转存，联网后补发
CREATE TABLE drafts (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    accountId  INTEGER NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    toAddress  TEXT    NOT NULL,
    subject    TEXT    NOT NULL,
    body       TEXT    NOT NULL,
    isSending  INTEGER NOT NULL DEFAULT 0,
    failCount  INTEGER NOT NULL DEFAULT 0,
    lastError  TEXT,
    createdAt  INTEGER NOT NULL,
    updatedAt  INTEGER NOT NULL
);
CREATE INDEX idx_drafts_account ON drafts(accountId);
```

缓存上限：`emails` **500** 条、`email_bodies` **50** 条，超限按 `cachedAt`/`downloadedAt` 升序 LRU 淘汰；每次读取刷新时间戳，保证"最近读过的不被淘汰"。

---

## 4. 核心模块代码实现

### 4.1 IMAP 收件同步（`data/remote/imap/ImapSyncManager.kt`）

关键点：**认证必须带用户名与解密密码**；连接失败按指数退避 1s→2s→4s 重试；认证失败不重试（防锁定）。

```kotlin
/** 创建并认证连接 IMAP Store，带指数退避重连 */
private fun connectStore(account: AccountEntity): Store {
    var lastError: Exception? = null
    var attempt = 0
    while (attempt < MAX_RETRY) {
        try {
            val store = createStore(account)
            // 关键：必须携带用户名与解密后的密码，否则无法通过认证
            store.connect(
                account.imapHost, account.imapPort,
                account.email, getDecryptedPassword(account)   // KeyStore+AES-GCM 解密
            )
            return store
        } catch (e: AuthenticationFailedException) {
            throw e                                   // 认证失败与网络无关，重试无意义
        } catch (e: Exception) {
            lastError = e; attempt++
            if (attempt < MAX_RETRY) {
                val backoff = BACKOFF_BASE_MS * (1L shl (attempt - 1))  // 1s / 2s / 4s
                Thread.sleep(backoff)
            }
        }
    }
    throw MessagingException("IMAP连接失败（已重试${MAX_RETRY}次）", lastError)
}

/** 增量同步：基于 IMAP UID 范围 FETCH，不做全文件夹扫描 */
suspend fun syncEmails(account, sinceUid: Long?, maxCount: Int = 50): ImapSyncResult {
    store = connectStore(account)
    folder = (store as IMAPStore).getFolder("INBOX").apply { open(Folder.READ_ONLY) }
    val uidFolder = folder as UIDFolder
    messages = if (sinceUid != null && sinceUid > 0) {
        val nextUid = sinceUid + 1
        if (nextUid < uidFolder.uidNext) {
            val endUid = minOf(uidFolder.uidNext - 1, nextUid + maxCount - 1)
            uidFolder.getMessagesByUID(nextUid, endUid)        // 增量：只拉新 UID
        } else emptyArray()
    } else {
        // 首次同步：仅最近 maxCount(50) 封元数据
        val n = folder.messageCount
        if (n > 0) folder.getMessages(maxOf(1, n - maxCount + 1), n) else emptyArray()
    }
    // 批量 ENVELOPE/FLAGS/UID FETCH（一次往返），正文不在此阶段下载
    folder.fetch(messages, FetchProfile().apply {
        add(FetchProfile.Item.ENVELOPE); add(FetchProfile.Item.FLAGS)
        add(UIDFolder.FetchProfileItem.UID)
    })
    ...
}
```

正文按需下载 `downloadBody(uid)`；标记已读 `markAsRead(uid, isRead)` 走 `Flags.Flag.SEEN`；删除走 `DELETED` + `expunge()`。

### 4.2 SMTP 发件（`data/remote/smtp/SmtpSendManager.kt`）

```kotlin
suspend fun sendEmail(account, toAddress, subject, body): SmtpSendResult {
    if (body.length > MAX_BODY_LENGTH /*500*/) return SmtpSendResult.Error("正文超过500字限制")
    val session = createSession(account)            // mail.smtp.auth=true + 加密属性
    transport = session.getTransport("smtp")
    transport.connect(
        account.smtpHost, account.smtpPort,
        account.email, getDecryptedPassword(account) // AUTH LOGIN/PLAIN
    )
    transport.sendMessage(createMessage(session, account, toAddress, subject, body),
                          /*allRecipients*/ ...)
}

private fun createSession(account) = Session.getInstance(Properties().apply {
    put("mail.smtp.auth", "true")
    put("mail.smtp.connectiontimeout", "10000")     // 连接超时 10s
    put("mail.smtp.timeout", "30000")
    when (account.smtpEncryption) {
        EncryptionType.SSL      -> { put("mail.smtp.ssl.enable", "true")          // 隐式 TLS(465)
                                     put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory") }
        EncryptionType.STARTTLS -> { put("mail.smtp.starttls.enable", "true")     // 显式 TLS(587)
                                     put("mail.smtp.starttls.required", "true") }
        EncryptionType.NONE     ->  put("mail.smtp.ssl.enable", "false")
    }
}, Authenticator() { /* 返回解密后的账号口令 */ })
```

### 4.3 多账户合并排序（统一收件箱）

```sql
-- EmailDao：全账户按接收时间倒序合并
SELECT * FROM emails ORDER BY receivedAt DESC
-- 账户筛选
SELECT * FROM emails WHERE accountId = :accountId ORDER BY receivedAt DESC
```

```kotlin
// InboxScreen：先合并后筛选，无需 N 路归并排序（SQLite 已按 receivedAt 建索引）
val filteredEmails = remember(emails, selectedAccountId) {
    if (selectedAccountId == null) emails
    else emails.filter { it.accountId == selectedAccountId }
}
```

新增邮件精确计数（REPLACE 会覆盖旧记录，不能直接用 size）：

```kotlin
val fetchedUids = result.emails.map { it.uid }
val existing   = emailDao.getExistingUids(account.id, fetchedUids).toSet()
val newEmails  = result.emails.filter { it.uid !in existing }   // 只有这些才推送通知
emailDao.insertEmails(result.emails)
```

### 4.4 圆形表盘动态宽度（`ui/util/CircularDisplayUtil.kt` + `EmailListItem.kt`）

安全区：圆心 (233,233)、`SAFE_RADIUS = 210px`、`EDGE_BUFFER = 23px`。

```kotlin
/** 指定 Y 坐标处的可用弦长（px）。半弦长 h = √(R² − dy²)，弦长 = 2h − 2×缓冲 */
fun calculateChordWidth(y: Float, safeRadius: Float = SAFE_RADIUS): Float {
    val dy = abs(y - SCREEN_CENTER_Y)
    if (dy >= safeRadius) return 0f
    val halfChord = sqrt((safeRadius * safeRadius - dy * dy).toDouble()).toFloat()
    return (halfChord * 2 - EDGE_BUFFER * 2).coerceAtLeast(0f)
}

/** 列表项宽度随 Y 坐标自适应；4px 量化避免滚动时每帧重组，保证 ≥30fps */
@Composable
fun Modifier.circularAdaptedWidth(): Modifier {
    val density = LocalDensity.current
    var widthPx by remember { mutableFloatStateOf(CircularDisplayUtil.SAFE_RADIUS * 2f - CircularDisplayUtil.EDGE_BUFFER * 2f) }
    return this
        .onGloballyPositioned { coords ->
            val centerY = coords.positionInWindow().y + coords.size.height / 2f
            val quantized = (CircularDisplayUtil.calculateChordWidth(centerY) / 4f).toInt() * 4f
            if (quantized != widthPx) widthPx = quantized
        }
        .width(with(density) { widthPx.toDp() })
}
```

触控热区：`WatchIconButton` 触控区固定 48×48dp（调用方 `size()` 无法缩小它）；字号下限 14sp（`WatchMailTypography.Caption/EmailTime` 均已 ≥14sp）。

---

## 4.5 扫码配对添加账户（手机填表）

手表打字极不方便，故提供**局域网扫码配对**通道：手表起一个本地 Web 服务并展示二维码，手机在同一 WiFi 下扫码打开 H5 表单填写邮箱配置，提交后自动回传落库。

```
┌─ 手表 ─────────────┐        ┌─ 手机浏览器 ─────────────┐
│ [←]  扫码配置      │  扫码  │  WatchMail · 邮箱配置    │
│    ┌──────────┐    │ ────▶ │  配对码  ABCD-EFGH-IJKL  │
│    │ QR 码    │    │ HTTP  │  邮箱  [______________]  │
│    └──────────┘    │ 局域网 │  密码  [______________]  │
│ 配对码 ABCD-…-IJKL │        │  别名  [______________]  │
│ 等待手机提交… 5min │ ◀──── │  ▸ 高级：手动指定服务器   │
└────────────────────┘  POST  │  [     保存到手表     ]  │
                              └──────────────────────────┘
```

### 交互流程

1. 账户页 →「手机扫码配置」→ `PairScreen` 启动 `QrPairingServer` 并生成二维码
2. 手机扫码（内容 `http://<手表IP>:8765/#k=<配对码>`）打开 H5 表单
3. 手机填邮箱/密码/别名（可展开「高级」手填 IMAP/SMTP 主机、端口、加密方式；留空则手表自动探测）
4. 浏览器用**纯 JS 自实现加密**（SHA-256-CTR + HMAC-SHA256）加密整个表单 → `POST /submit`
5. 手表解密 → `MainViewModel.createAccountFromPairing()` → 自动探测（如需）→ 落库 → 首次同步

### 安全设计

> **为什么不用 WebCrypto / AES-GCM？**
> 手表服务跑在 `http://<IP>:8765`，浏览器判定为**非安全上下文**，`crypto.subtle` 被整体禁用
> （Chrome/Edge/Safari 皆然，与浏览器版本无关）。因此手机端必须用纯 JS 自实现密码学。
> 选用 SHA-256 + HMAC-SHA256 作为最小原语——两端均可原生实现，避免体积大、易错的纯 JS AES。

| 措施 | 说明 |
|------|------|
| **配对码在 URL fragment** | `#k=…` 不会出现在 HTTP 请求行/请求头中，局域网抓包**看不到配对码**，无法解密或伪造提交 |
| **纯 JS 加密（encrypt-then-MAC）** | `master=SHA-256(pin)`；`encKey=SHA-256(master‖"enc")`；`macKey=SHA-256(master‖"mac")`<br>`ks[i]=SHA-256(encKey‖iv‖u32be(i))`，`ct=pt⊕ks`，`tag=HMAC-SHA256(macKey, iv‖ct)[0..16)`<br>浏览器与手表 `MessageDigest`/`Mac` 实现**逐字节一致**（已用标准测试向量 + 跨端比对验证） |
| **MAC 先行校验** | 手表端先做 128 位 tag 常数时间比较，校验通过才解密，防篡改/伪造 |
| **单次会话** | 每次进页面重新生成 12 位配对码；收到一次提交即关闭服务 |
| **有效期 5 分钟** | 超时自动关闭并释放端口 |
| **防暴力** | 连续 5 次校验失败即关闭服务 |
| **落库再加密** | 解密得到明文后仍走 `EncryptionUtil`（KeyStore+AES-GCM）加密存储，绝不明文落盘 |
| **自动探测兜底** | 手机未填服务器时手表按域名探测 Gmail/Outlook/QQ/163；失败回落通用 `imap./smtp.` 配置 |

> 端口默认 8765，被占用时自动改用随机可用端口；服务只在配对页存活期间监听 `0.0.0.0`。

---

## 4.6 圆形适配：按钮为什么会被裁掉

**关键几何**：圆屏可用宽度随 Y 变化 —— `弦长 = 2√(233² − (y−233)²)`。越靠上下边缘越窄。

按钮要完整落在圆内，其**离圆心最远的角**必须在圆内：

| 按钮尺寸 | 底栏下留白 | 按钮底部 Y | 该处弦宽 | 3 个总宽 | 结论 |
|---|---|---|---|---|---|
| 48dp | 30dp | 398 | 329 px | 288 | 可行，但 chrome=408px，列表仅 58px |
| **36dp** | **18dp** | **430** | **249 px** | **216** | **✅ chrome=267px，列表 199px** |
| 32dp | 12dp | 442 | 206 px | 192 | 底栏 OK，但顶栏放不下按钮+标题 |

**原实现的 bug**（两处）：
1. 栏用 `fillMaxWidth()` 把按钮排到 466px 画布左右两端，且贴着屏幕上下边缘
   —— 底栏按钮底部 y=458 处弦宽仅 121px，连一个 96px 按钮都放不下
2. `Modifier.width()` 无法收窄：外层 `fillMaxWidth()` 把约束强制成父宽，里面的 `width()` 被 coerce 掉**完全不生效**

**修复**（`ui/util/ChordConstraint.kt`）：
1. **按钮 48dp → 36dp**（icon 24→20dp）：只需抬高 18dp 即可避开圆弧，纵向空间大幅回收
2. **抬高固定栏**：顶栏/底栏 `padding(18dp)`，内容中心移到弦宽足够处
3. **`Modifier.chordConstrained()`** —— 自定义 `Layout`：测量栏自身 Y 范围取最窄弦长，
   内容收窄到该宽度并**水平居中**（不能用 `width()`，见上）
4. 弦长按 `LocalUiScale` 还原到未缩放坐标再算，与整体缩放兼容

真机验证（OWW251）：200 个内容行 vs 该行可见弦宽逐行检查，**越界 0 行**；
邮件列表区 **58px → 199px**，一屏可见约 3 条。

## 4.7 界面整体缩放

设置 → 显示 → **界面大小**（70% ~ 130%，默认 100%）。

- `graphicsLayer { scaleX = scaleY = s }` 等比缩放**整个应用画面**，中心对齐 → 缩小后四周露出黑色画布
- 命中测试同样经过该变换，缩放后触控仍准确
- 持久化于 DataStore（`ui_scale_percent`）
- 用途：不同机型可视区差异大时的微调。**注意它不能替代上面的弦宽适配** —— 只有缩到 ≤70% 时整个 466×466 画布才完全落入圆内（正方形对角线 = 直径）

## 4.8 表冠适配（OPPO 专项）

表冠事件形态由厂商决定，用 `dumpsys input` 查设备分类即可判定：

| dumpsys input | 框架归类 | 应监听 |
|---|---|---|
| `Sources 0x00004000` | ROTARY_ENCODER | `onRotaryScrollEvent` |
| `Sources 0x00002002` | SOURCE_MOUSE（鼠标滚轮） | `PointerEvent.scrollDelta` |

**实测 OPPO Watch X2（OWW251）**：`oplus_crown`
```
Classes: 0x00000008   (INPUT_DEVICE_CLASS_CURSOR)
Sources: 0x00002002   (SOURCE_MOUSE)
input props: <none>   ← 缺 INPUT_PROP_ROTARY_ENCODER
```
→ 系统把表冠当**鼠标滚轮**派发（`ACTION_SCROLL` / `AXIS_VSCROLL`），`onRotaryScrollEvent` **完全不会触发**，这就是"表冠震动没适配"的根因。

因此 `crownScroll()` 同时接两条链路：真旋转编码器走 `onRotaryScrollEvent`，OPPO 这类走 `PointerEvent.scrollDelta`；另有根布局 `crownWheelRouter()` 兜底转发（表冠事件坐标常为 (0,0)，落在圆形裁剪外，子节点收不到）。

触觉反馈采用**双链路 + 多级降级**（`HapticUtil.performCrownTick`）：
1. `View.performHapticFeedback(CLOCK_TICK, FLAG_IGNORE_GLOBAL_SETTING)` — 厂商系统触觉引擎
2. `VibrationEffect.createPredefined(EFFECT_TICK)` — 表冠刻度标准效果
3. `createOneShot(25ms, 显式振幅)` / `createWaveform` 降级

> 旧实现用 `createOneShot(10ms)`，时长过短被多数机型直接丢弃，表现为"没震动"。
> 每滚过 28px 给一次刻度震动，模拟物理表冠的"哒"手感。

---

## 5. 页面圆形适配布局（伪代码）

所有页面共用：**顶弧=状态/返回 · 中带=内容 · 底弧=操作**，内容限制在 `SAFE_RADIUS=210px` 圆内，边缘 23px 缓冲。

```
┌────────────── 统一收件箱（主页面） ──────────────┐
│  [刷新 48dp]   收件箱   [未读数]      ← 顶弧 48px │
│  ( 全部 | 工作 | 个人 | ... )          ← 账户筛选   │
│  ╭──────────────────────────────╮                │
│  │ ●张三  会议纪要          14:32 │ ← 弦宽自适应  │
│  │ ●李四  周报 Re:           周三 │                │
│  │  王五  项目进度           09/12│                │
│  ╰──────────────────────────────╯                │
│        [账户][草稿] (✎写邮件) [设置]  ← 底弧 56px │
└──────────────────────────────────────────────────┘

┌────────────── 邮件详情 ──────────────┐
│  [←返回]      邮件详情      [ 48dp ]  │
│  会议纪要（标题 22sp，最多 3 行）      │
│  (头像)张三  zhang@x.com     09/12   │
│  收件人: me@x.com                     │
│  ──────────────────────              │
│  纯文本正文（HTML 已剥离标签）        │
│  上下滚动阅读 …                      │
│  [回复]  [已读]  [删除]   ← 固定底栏 │
└──────────────────────────────────────┘

┌────────────── 撰写邮件 ──────────────┐
│  [✕取消]    写邮件    [草稿][发送]    │
│  发件账户: [● 工作        ▼]         │
│  收件人:   [________________]        │
│  主题:     [________________]        │
│  正文:     [________________]        │
│            [________________]  32/500│
└──────────────────────────────────────┘

┌────────────── 账户 / 添加 / 编辑 / 扫码配对 ──────────────┐
│  [←]   邮箱账户 / 添加账户 / 编辑账户   [✓]    │
│  ┌ 📱 手机扫码配置 · 同一 WiFi 填表更方便 ┐   │
│  列表：色标+别名+邮箱 + 通知开关 + 编辑/删除   │
│  表单：邮箱 / 密码(留空不改) / 别名            │
│        IMAP host:port + 加密[SSL|STARTTLS|无]  │
│        SMTP host:port + 加密[SSL|STARTTLS|无]  │
│        [测试连接]                              │
│  扫码配对页：[←] 扫码配置                      │
│              ┌─────────┐                       │
│              │ QR 码   │  168dp                │
│              └─────────┘                       │
│           配对码 ABCD-EFGH-IJKL                │
│           同一 WiFi 下用手机扫码                │
│           ● 等待手机提交… 5 分钟内有效          │
└────────────────────────────────────────────────┘

┌────────────── 设置 / 草稿箱 ──────────────┐
│  [←] 设置          /        [←] 草稿箱    │
│  账户 → 邮箱账户管理                      │
│  同步频率  [‹] 15 分钟 [›]                │
│  自动发送草稿          (开关)             │
│  新邮件通知            (开关)             │
│  关于：版本 1.0.0                         │
└──────────────────────────────────────────┘
```

导航：主页面=收件箱；左滑/按钮→账户管理；右滑/按钮→设置；底栏中央→撰写；列表项→详情；通知点击→直达该邮件详情（携带 `email_id`）。

---

## 6. 异常处理策略

| 场景 | 策略 | 实现位置 |
|------|------|----------|
| **网络超时/断连** | 连接超时 10s、读超时 30s；失败指数退避重连 1s→2s→4s（共 3 次）；仍失败则展示本地缓存数据（优雅降级） | `ImapSyncManager.connectStore` / `SmtpSendManager` |
| **认证失败** | 不重试（防账号锁定）；按服务商给出针对性文案（Gmail 应用专用密码 / QQ 授权码 / 网易授权码…） | `buildAuthErrorMessage` / `buildSmtpAuthErrorMessage` |
| **后台同步失败** | WorkManager 指数退避 `Result.retry()`（仅网络类错误）；其他 `Result.failure()`；部分账户失败不影响其他账户 | `EmailSyncWorker.doWork` / `isNetworkError` |
| **发送失败/离线** | 自动转存 `drafts` 表；联网后由后台任务自动补发；失败计数 `failCount` 供 UI 显示；手动可重试 | `MainViewModel.sendEmail` / `EmailRepository.sendPendingDrafts` |
| **存储满/缓存超限** | `emails` >500、`email_bodies` >50 时按 LRU 时间戳批量淘汰（一次清 50/10 条避免频繁 IO） | `EmailRepository.cleanupCache/cleanupBodyCache` |
| **密码解密失败** | 抛 `EncryptionException`，上层提示"凭据已损坏"，**绝不回退到明文** | `EncryptionUtil.decrypt` |
| **无通知权限**（API 33+） | 启动时请求 `POST_NOTIFICATIONS`；未授权时跳过通知不影响同步 | `MainActivity.requestNotificationPermission` |
| **正文下载失败** | 详情页回退显示 `preview` 字段，不阻塞阅读 | `EmailDetailScreen` |
| **数据库升级** | `fallbackToDestructiveMigration(dropAllTables=true)`（本地缓存可重建；账户需重新绑定，已在文档标明） | `WatchMailDatabase` |
| **配对无 WiFi** | 启动服务前检测局域网 IP，取不到则提示"未连接到 WiFi"，不起服务不生成二维码 | `QrPairingServer.start` / `NetworkUtil` |
| **配对超时/暴力尝试** | 5 分钟超时自动关闭；连续 5 次解密失败即关闭，返回 429 | `QrPairingServer` |
| **配对码不匹配** | HMAC tag 校验失败（常数时间比较），返回 401 并提示核对手表配对码，不回显任何明文 | `QrPairingServer.handleSubmit` |
| **配对数据被篡改** | encrypt-then-MAC：密文或 tag 任一字节被改，MAC 立即不匹配并拒绝 | `QrPairingServer.decrypt` |
| **配对端口被占用** | 回退 `ServerSocket(0)` 随机端口，二维码内容随之更新 | `QrPairingServer.start` |
| **协议库 R8 剥离** | keep `javax.mail/**`+`com.sun.mail/**`，`-dontwarn java.awt/beans/activation`，保留 `META-INF/services` | `proguard-rules.pro` |

---

## 邮箱服务商配置示例

| 服务商 | IMAP | SMTP | 备注 |
|--------|------|------|------|
| Gmail | imap.gmail.com:993 (SSL) | smtp.gmail.com:587 (STARTTLS) | 需应用专用密码 |
| Outlook | outlook.office365.com:993 (SSL) | smtp.office365.com:587 (STARTTLS) | 需应用密码 |
| QQ 邮箱 | imap.qq.com:993 (SSL) | smtp.qq.com:587 (STARTTLS) | 需开启 IMAP 并用授权码 |
| 163 邮箱 | imap.163.com:993 (SSL) | smtp.163.com:465 (SSL) | 需授权码 |
| 企业邮箱 | 通用 IMAP/SMTP | 通用 IMAP/SMTP | 高级配置手动填写 |

## 构建

```bash
./gradlew :app:assembleDebug     # 需 JDK 17+、Android SDK 37
```

环境要求：Android Studio Hedgehog+ / JDK 17+ / Wear OS 3.0+（minSdk 30）设备或模拟器。
