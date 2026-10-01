> 本文档是 **WearMail 的技术与验证文档**：完整功能清单、构建与测试方式、目录结构、
> 安全与隐私说明、已知限制。面向使用者的项目介绍请看根目录 [README.md](../README.md)。

# 腕邮 WearMail —— Wear OS 智能手表邮箱客户端

面向 **466 × 466 圆形表盘**的 Wear OS 邮箱客户端：多账户统一收件箱、IMAP 收信、SMTP 发信、
新邮件通知、局域网扫码配置账户，凭据使用 Android Keystore 加密存储。

技术栈：**Kotlin + Jetpack Compose for Wear OS（`androidx.wear.compose:compose-material3`）**。

---

## 验证状态

| 项目 | 结果 |
|------|------|
| 单元测试（纯 JVM，无需设备） | ✅ **221 / 221 通过**（14 个测试类） |
| Debug APK | ✅ 构建成功，**27.76 MB** → [`dist/wearmail-debug.apk`](../dist/wearmail-debug.apk) |
| Release APK（R8 + 资源压缩） | ✅ 构建成功，**3.35 MB** → [`dist/wearmail-release.apk`](../dist/wearmail-release.apk) |
| 静态检查（资源引用/契约/安全红线/Keystore/信息流预算/覆盖层等 12 项） | ✅ 全部通过 → [`dist/reports/static-checks.txt`](../dist/reports/static-checks.txt) |
| 测试报告 | [`dist/reports/test-summary.txt`](../dist/reports/test-summary.txt)、[`dist/reports/unit-tests/index.html`](../dist/reports/unit-tests/index.html) |
| 真机端到端（IMAP/SMTP 实收实发、通知、表冠手感） | ⚠️ **未验证**，需真实手表，清单见 [docs/TESTING.md](TESTING.md) §7 |

> 六类问题在验证过程中被发现并修复（根因与修复都记在 [docs/TESTING.md](TESTING.md) §5.3）：
> ① 单元测试发现的凭据密文缺少分隔符（导致加密后无法解密）；
> ② 真机反馈的覆盖层根节点漏画背景（详情/撰写/添加账户会透出上一页）；
> ③ 真机反馈的扫码提交时主机留空只回一句笼统的「提交失败」；
> ④ 真机反馈的 `InvalidAlgorithmParameterException`（Android Keystore 不允许加密时自带 IV）；
> ⑤⑥ 两次反馈的「信息流太小」（顶部/底部固定条吃掉纵向空间，已把按钮减半）。
> ②③④⑤⑥ 属于纯 JVM 测试覆盖不到的类型，已分别补上**静态检查规则**防回归。

---

## 功能一览

| 需求 | 实现 |
|------|------|
| 多邮箱账户（≥5 个流畅） | `AccountRepository` + 账户列表页；Gmail / Outlook / QQ / 163 / 企业邮箱预设（`ProviderPresets`） |
| 账户字段齐全 | 邮箱、密码/应用专用密码/**OAuth2 令牌**、IMAP/SMTP 主机端口、SSL/TLS/STARTTLS、账户别名 |
| **Outlook OAuth2（完整实现）** | 设备码授权流：手表显示短码 + 二维码 → 手机浏览器登录 → 手表轮询取令牌；access token 到期前自动用 refresh token 续期；微软已停用 IMAP/SMTP 基础认证，Outlook/Office 365 **必须**走这条路径 |
| **手机扫码配置** | 手表开启本地 HTTP 服务 → 表盘显示二维码 → 同 Wi-Fi 手机扫码填写 → 加密保存 |
| 加密本地存储 | Android Keystore（AES-256-GCM）+ `accounts.password_enc` 密文列 |
| 统一收件箱 | 所有账户按时间倒序合并（`observeInbox`），顶部账户筛选器 |
| 列表项信息 | 发件人 / 主题（单行截断）/ 智能时间（今天 HH:mm、本周 周X、更早 MM/DD）/ 未读标记 / 来源账户色点 |
| 下拉刷新 | NestedScroll 下拉手势，阈值 48dp |
| 邮件详情 | 发件人、收件人、时间、主题、**纯文本正文**（HTML 已剥离）、可滚动 + 底部固定操作栏（回复/删除/已读） |
| 撰写并发送 | 收件人（常用联系人快捷选择）、主题、正文（500 字提示）、发件账户选择；`Sending → Success(✓+振动) / Failure(✗+振动+重试)` |
| 离线草稿 | 发送失败自动存草稿，联网后自动补投（`flushPendingDrafts`） |
| 新邮件通知 | `NotificationCenter`：发件人 + 主题前 30 字符，点击直达详情；支持按账户关闭；设置页提供**测试通知 + 链路自检**（三层开关 + 系统权限/渠道状态会明确告诉你是哪一层拦下的） |
| IMAP 收信 | JavaMail；首次 50 封元数据、增量按 UID、IDLE 推送（支持时）、10 秒超时 |
| SMTP 发信 | JavaMail；AUTH LOGIN/PLAIN、**XOAUTH2**、SSL/TLS/STARTTLS |
| 断线重连 | `BackoffPolicy` 指数退避（1s→60s，±20% 抖动，区分可重试错误） |
| 数据同步策略 | 首次仅元数据、正文按需、增量 UID/SINCE、前台 5 分钟 / 后台 15–30 分钟 / 手动 |
| 本地缓存上限 | 元数据 500 封 + 正文 50 封，超出按 LRU 淘汰 |
| 圆形表盘适配 | 安全半径 210px、按 Y 坐标动态计算弦长收窄列表项、顶/底弧形区分区 |
| 交互 | 上下滑滚动、左右滑切换页面、点击、长按菜单、表冠旋转、全操作振动反馈 |

## 快速开始

### 环境要求

- JDK 17（AGP 8.7 要求）
- Android SDK：`compileSdk 35`、`build-tools 35.0.0`（`local.properties` 中配置 `sdk.dir`）
- Gradle 由 wrapper 提供（8.11.1），首次构建会联网下载

### 构建

```bash
# 单元测试（纯 JVM，无需设备）
./gradlew test

# 构建调试包
./gradlew assembleDebug        # 产物：app/build/outputs/apk/debug/app-debug.apk

# 构建发布包（开启 R8 压缩，keep 规则见 app/proguard-rules.pro）
./gradlew assembleRelease
```

Windows：把 `./gradlew` 换成 `gradlew.bat`。

### 安装到手表

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
# 或
./gradlew installDebug
```

首次进入需在「添加账户」中配置邮箱：建议在手机上扫码填写（手表上输入邮箱密码体验较差）。

### 启用 Outlook（OAuth2）

微软已对 Exchange Online / Outlook.com **停用 IMAP/SMTP 基础认证**，Outlook 账户必须走 OAuth2：

1. 打开 [Azure 门户 → 应用注册](https://portal.azure.com/#view/Microsoft_AAD_RegisteredApps/ApplicationsListBlade)
   →「新注册」；
2. 「受支持的账户类型」选**「任何组织目录中的账户和个人 Microsoft 账户」**（个人
   outlook.com / hotmail 账号也能用）；
3. 「重定向 URI」留空 —— **设备码流不需要**；
4. 进入「身份验证」→ 拉到底部「高级设置」→ **「允许公共客户端流」选「是」**
   （不开启的话设备码流会被直接拒绝）；
5. 复制概览页的**「应用程序(客户端) ID」**；
6. 手表上进入「设置 → Outlook OAuth2」，把该 ID 填进去（租户保持 `common`）；
7. 「添加账户」→ 邮箱填 Outlook 地址 → 登录方式选 **OAuth2 令牌** → 「获取授权」→
   用手机扫码（或手输网址）打开授权页、输入手表显示的短码 → 授权成功后保存。

> 公共客户端 + 设备码流**不需要客户端密码**（也不建议生成：注册成机密客户端反而会让设备码流失败）。
> 权限在授权时动态申请：`offline_access`、`https://outlook.office.com/IMAP.AccessAsUser.All`、
> `https://outlook.office.com/SMTP.Send`。
>
> 为什么用设备码流而不是"跳浏览器登录"：手表没有可用的浏览器控件，而设备码流正是为
> 输入受限设备设计的 —— 手表只显示短码与二维码，登录在手机浏览器完成。

## 目录结构

```
.
├── app/
│   ├── build.gradle.kts               # 模块构建脚本（签名/压缩/打包排除）
│   ├── proguard-rules.pro             # JavaMail 等反射依赖的 keep 规则
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml    # 手表应用声明、权限、standalone 元数据
│       │   ├── res/                   # 圆形表盘主题、图标、通知小图标
│       │   └── java/com/wm/wearmail/
│       │       ├── MainActivity.kt            # 单 Activity、表冠事件、通知入口
│       │       ├── core/                      # 依赖容器、日志、表冠总线、Application
│       │       ├── model/                     # 领域模型 + 服务商预设
│       │       ├── mail/                      # IMAP/SMTP/自动探测/HTML 剥离/退避
│       │       ├── data/
│       │       │   ├── crypto/                # AES-256-GCM + Android Keystore
│       │       │   ├── db/                    # MailDatabase + 5 个 DAO
│       │       │   ├── prefs/                 # 设置存储
│       │       │   └── repo/                  # 仓储接口 + 实现
│       │       ├── sync/                      # SyncService / SyncEngine / SyncWorker
│       │       ├── notify/                    # 通知渠道与新邮件通知
│       │       ├── pairing/                   # 局域网 HTTP 服务 + 二维码 + 网页
│       │       ├── util/                      # 时间智能格式化
│       │       └── ui/
│       │           ├── theme/                 # 深色 OLED 配色
│       │           ├── kit/                   # 圆屏几何/安全列表/通用组件/振动
│       │           ├── nav/                   # 分页 + 覆盖层导航
│       │           └── screen/                # inbox / detail / compose / accounts / settings
│       └── test/java/com/wm/wearmail/         # 纯 JVM 单元测试
├── docs/
│   ├── ARCHITECTURE.md                # 分层架构、同步策略、安全设计、性能功耗、取舍
│   ├── UI_SPEC.md                     # 圆形表盘 UI 规格（弦长表 + 六页布局）
│   └── TESTING.md                     # 测试策略、验证结果、真机手工清单
├── tools/
│   ├── static_checks.py               # 静态检查（资源引用/契约/安全红线，无需 Gradle）
│   ├── test_summary.py                # 汇总 JUnit XML 测试结果
│   └── mirrors-init.gradle            # 可选：国内 Maven 镜像加速（不改动项目配置）
├── dist/                              # 已验证的交付产物
│   ├── wearmail-debug.apk             # 调试包（可直接 adb install）
│   ├── wearmail-release.apk           # R8 压缩后的发布包
│   └── reports/                       # 单元测试报告 + 静态检查报告
├── gradle/libs.versions.toml          # 版本目录
└── keystore/debug.keystore            # 仓库内自签名调试证书（口令 android）
```

## 构建说明

- **依赖下载**：项目 `settings.gradle.kts` 使用官方仓库（`google()` / `mavenCentral()`）。
  若在中国大陆网络下下载缓慢，可用附带的初始化脚本注入国内镜像（**不改动项目配置**）：

  ```bash
  gradlew --init-script tools/mirrors-init.gradle assembleDebug
  ```

  注意 Gradle 发行版（`services.gradle.org`）在某些网络下会被限速，
  可改用 `gradle/wrapper/gradle-wrapper.properties` 中的 `distributionUrl` 指向镜像，或预置发行版。
- **离线构建**：依赖下载完成后可加 `--offline`；但若首次是通过镜像仓库下载的，
  离线时仍需保留 `--init-script tools/mirrors-init.gradle`（Gradle 会校验缓存产物的来源仓库）。
- **无需联网的检查**：`python tools/static_checks.py` 只读源码，可在任何环境运行。
- **受限/容器环境**：若 Kotlin 编译守护进程无法启动（例如沙箱禁止写用户目录，
  报 `AccessDeniedException: .../kotlin/daemon/...`），可改为进程内编译：

  ```bash
  gradlew "-Pkotlin.compiler.execution.strategy=in-process" assembleDebug
  ```

## 导航结构

```
        ┌── 右滑 ──┐                  ┌── 左滑 ──┐
        ▼          │                  │          ▼
   ┌─────────┐  ┌──────────┐  ┌──────────────┐
   │  设置   │  │ 统一收件箱 │  │  账户管理    │     ← 顶层三页（HorizontalPager）
   └─────────┘  └────┬─────┘  └──────┬───────┘
                     │ 点击邮件        │ 添加账户
                     ▼                ▼
              ┌────────────┐   ┌────────────┐
              │  邮件详情   │   │  添加账户   │        ← 覆盖层（返回键 / 返回按钮关闭）
              │  └ 回复 ────┼──▶│  撰写邮件   │
              └────────────┘   └────────────┘
               底部按钮 ──────▶ 撰写邮件
```

系统级「右滑退出应用」已禁用（`android:windowSwipeToDismiss=false`），
否则会与「右滑进入设置」冲突；因此覆盖层通过返回按钮或系统返回键关闭。

## 安全说明

- 邮箱密码 / 应用专用密码 / OAuth 令牌：**Android Keystore 生成密钥 + AES-256-GCM 加密**后
  写入数据库，绝不明文落盘；`Account` 模型本身不含密码字段；
- 日志（`core/Logs.kt`）明令禁止输出密码、令牌、正文、密文；
- 扫码配置页面：一次性随机 token 校验、仅绑定局域网地址、离开页面立即关闭端口；
- 邮件传输全程 SSL/TLS 或 STARTTLS；
- `AndroidManifest` 中 `allowBackup=false`，防止通过备份通道导出数据库；
- 仓库中的 `keystore/debug.keystore` 仅供本地调试，**正式发布请替换为独立证书**。

## 已知限制

1. **仅在真实手表上完成端到端验证才能确认的项**：IMAP IDLE 推送在不同服务商上的表现、
   各厂商表冠事件频率差异、通知在 Wear OS 上的实际样式。代码已按各平台文档实现并留有日志。
2. **Outlook OAuth2 需要你自己注册 Azure 应用**：协议与刷新逻辑已完整实现（设备码流），
   但 `clientId` 必须由使用者去 Azure 门户注册应用后填入（手表「设置 → Outlook OAuth2」，
   或 `gradle.properties` 的 `wearmail.microsoftOAuthClientId`）—— 没人能替你注册。
   本地无 Azure 应用与真实 Outlook 账户，因此**授权往返与 XOAUTH2 登录未经真机验证**，
   其余（响应解析、轮询状态机、刷新、存储）有 34 个单元测试覆盖。详见
   [docs/TESTING.md](TESTING.md) §5.3 与 §6。
3. **左右滑删除邮件**改为「长按菜单 + 二次确认」，因为该手势与顶层页面切换冲突（详见
   `docs/UI_SPEC.md` §6）。
4. **附件**：列表仅用 `multipart/mixed` 启发式标记「含附件」，不下载、不展示附件内容。
5. **文件夹管理**：IMAP 具备 `listFolders` 能力，UI 目前只使用收件箱；
   删除邮件会优先移动到 `\Trash`，否则打 `\Deleted` + EXPUNGE。
6. `release` 构建开启了 R8 压缩，keep 规则已覆盖 JavaMail 的反射用法，但
   未能通过真机回归验证压缩后的运行行为；如需稳妥可先关闭 `isMinifyEnabled`。

## 相关文档

- 架构与设计取舍：[docs/ARCHITECTURE.md](ARCHITECTURE.md)
- 圆形表盘 UI 规格与六页布局：[docs/UI_SPEC.md](UI_SPEC.md)
- 测试策略与验证结果：[docs/TESTING.md](TESTING.md)

---

## 发布前检查（哪些文件不要上传）

`git add` 之前确认下列内容**没有**进入仓库（`.gitignore` 已排除）：

| 路径 | 为什么不要上传 |
|------|---------------|
| `local.properties` | 含本机 Android SDK 绝对路径（`sdk.dir=...`），对他人无用且暴露本机目录结构 |
| `keystore/`（`debug.keystore`） | 签名证书。即使是密码公开的调试证书，提交进仓库也是坏习惯（可能被误用于签名发布）；构建脚本已改为**证书缺失也能构建**（无证书时 debug 用 AGP 默认调试证书） |
| `.gradlehome/`、`.gradle-dist/`、`.gradle/`、`.kotlin/` | Gradle/Kotlin 缓存与本地解压的发行版（数百 MB） |
| `.tmp/`、`.refsrc/`、`.androidhome/` | 构建过程临时目录 / 依赖源码解压目录 / Android 用户目录 |
| `build/`、`app/build/` | 构建产物（每次构建都变） |
| `dist/`（含 `*.apk` 与测试报告） | APK 二进制（27.76 MB / 3.35 MB）与生成的报告。建议用 **GitHub Releases** 分发 APK，用 CI artifacts 保存报告；确实要提交某一份时用 `git add -f dist/reports/test-summary.txt` 强制添加 |
| `*.iml`、`.idea/`、`.DS_Store`、`Thumbs.db` | IDE 与系统文件 |

**可以（也应该）上传**：`app/src/**`、`gradle/wrapper/**`（含 `gradle-wrapper.jar`）、
`gradle/libs.versions.toml`、`*.gradle.kts`、`gradle.properties`、`tools/**`、`docs/**`、
`README.md`、`.gitignore`。

**首次公开前建议做的三件事**：

1. 选一个开源许可证并放置 `LICENSE` —— 当前仓库**没有**许可证，法律上等同于保留所有权利；
2. 自查历史里没有真实凭据：`git log -p | grep -i -E 'clientId|password|token|keystore'`
   （客户端 ID 属半公开信息，但截图与日志里可能夹带真实邮箱地址）；
3. 在 README 里说明「Outlook OAuth2 的客户端 ID 需使用者自行注册」，避免他人以为开箱即用。
