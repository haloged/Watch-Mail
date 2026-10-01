# 测试与验证（WearMail）

> ⚠️ **文档状态说明**：本文档在 2026-10-01 因一次**文件编码事故**（PowerShell 以 ANSI 读取、
> 以 UTF-8 写回，中文被 .NET best-fit 替换，不可逆）被**重建**。所有数据均为重建时的真实实测值
> （测试数量取自 `dist/reports/test-summary.txt`，产物大小取自 `dist/` 实际文件），
> 但行文可能与损坏前的版本不完全一致。事故本身与教训记在 §5.5。

---

## 1. 测试策略

手表应用的 UI 逻辑很难在无设备环境下回归，因此本项目采取「**把逻辑下沉为纯函数 + 纯 JVM 测试**」的策略：

| 层次 | 能否纯 JVM 测试 | 做法 |
|------|:----------:|------|
| 圆形表盘几何（弦长/安全区/触控热区） | ✅ | `CircularMetrics` 全是纯数学，无 Android 依赖 |
| HTML → 纯文本剥离 | ✅ | `HtmlTextExtractor` 只用 Kotlin 正则，不用 `android.text.Html` |
| 邮件地址解析/序列化 | ✅ | `MimeAddressSupport` 基于 `javax.mail.internet`（Android 版 JavaMail 是普通 jar） |
| 退避策略 | ✅ | `BackoffPolicy` 纯函数 + 注入时钟 |
| 凭据加解密 | ✅ | `CryptoManagerImpl` 只依赖 `KeyProvider` 接口 + `java.util.Base64`，测试用内存密钥实现 |
| OAuth2（设备码流） | ✅ | 端点/scope/响应解析/轮询状态机/刷新；网络层抽成 `FormPoster` 接口，测试注入假实现与假时钟 |
| 通知链路自检结论 | ✅ | `NotificationDiagnostics` 是纯数据 + 纯函数（三层开关的优先级判定） |
| 局域网配置服务（HTTP） | ✅ | `ConfigWebServer` 只用 `java.net`，测试真实起服务并用 `HttpURLConnection` 请求 |
| 配对网页与服务端的契约 | ✅ | `PairingPage` / `PairingFormDefaults` 是纯字符串函数 |
| 列表文案（发件人/主题截断、未读角标） | ✅ | `InboxRowFormatter` 纯函数 |
| 数据库读写 | ❌ | 依赖 Android `SQLiteDatabase`，需要 Robolectric/instrumentation（本项目未引入，见 §6） |
| Compose 渲染 / 手势 / 表冠 / 通知实际弹出 | ❌ | 需要真机，见 §7 手工清单 |

**为什么不用 Robolectric / instrumentation**：手表应用体积与构建时间敏感，且本次交付环境无法运行模拟器；
把可测逻辑抽成纯函数能以零额外依赖获得更高的性价比。

## 2. 运行方式

```bash
# 全部单元测试（纯 JVM，无需设备）
./gradlew test

# 只跑 debug 变体（更快）
./gradlew testDebugUnitTest

# 静态检查（无需 Gradle / SDK）
python tools/static_checks.py

# 汇总测试结果（读 JUnit XML，输出每个测试类的用例数）
python tools/test_summary.py
```

## 3. 覆盖矩阵

| 测试类 | 用例数 | 覆盖行为 | 对应需求 |
|--------|-----:|---------|---------|
| `CircularMetricsTest` | 14 | 安全半径 210px / 边缘缓冲 23px / 弦长公式 / 列表项取最远端收窄 / 触控 48px 下限 / 左右对称 | 圆形适配、触控热区 |
| `TimeFormatTest` | 11 | 今天 HH:mm、本周 周X、同年 MM/DD、跨年 yyyy/MM/DD、ISO 跨年周、相对时间 | 时间智能格式 |
| `HtmlTextExtractorTest` | 22 | 标签剥离、`<br>/<p>` 转换、脚本样式移除、实体解码、空行压缩 | 正文纯文本渲染 |
| `MimeAddressSupportTest` | 22 | `"张三" <a@b.com>` 解析、多地址、带逗号的显示名、往返序列化、非法输入 | 发件人与收件人 |
| `BackoffTest` | 14 | 初始/倍增/上限/抖动区间、不可重试错误不重试、取消异常透传 | 断线重连 |
| `CryptoManagerImplTest` | 19 | AES-GCM 往返、随机 IV、密文不含明文、篡改检测、异常输入返回 null、空 IV 拒绝 | 加密存储（核心安全断言） |
| `MicrosoftOAuthTest` | 24 | 租户规范化与端点地址、IMAP+SMTP+离线三个 scope、设备码/令牌响应解析（含 `authorization_pending`/`slow_down`/`declined`/`expired_token`）、轮询状态机（含网络抖动容忍与 `slow_down` 间隔 +5s）、refresh 保留旧 refresh token | Outlook OAuth2 |
| `OAuthTokensTest` | 10 | 令牌组序列化往返、损坏/越界输入返回 null、提前 2 分钟刷新判定、可刷新性 | OAuth2 令牌加密存储 |
| `NotificationDiagnosticsTest` | 10 | 三层开关判定、结论优先级（系统权限 → 渠道 → 应用内 → 账户级）、被静音账户列表 | 通知测试与自检 |
| `ConfigWebServerTest` | 9 | 真实起 HTTP 服务、GET/POST 解析、URL 解码、404、stop 幂等 | 扫码配置 |
| `PairingFormDefaultsTest` | 14 | 主机留空时按「显式预设 → 邮箱域名」兜底、两侧独立回填、空白裁剪、企业域名返回 null | 扫码配置（域名识别） |
| `PairingPageTest` | 13 | 网页与**服务端解析的契约**：字段名、枚举值（非中文标签）、主机不得 `required`、无 `novalidate`、token 转义 | 扫码配置（防前端/后端漂移） |
| `AccountFormValidatorTest` | 18 | 邮箱/端口/主机校验、边界值 | 账户配置 |
| `InboxRowFormatterTest` | 21 | 发件人/主题截断、空主题、未读角标边界 | 列表项展示 |
| **合计** | **221** | | |

## 4. 静态检查

`tools/static_checks.py` 的检查项与失败含义（**12 项全部通过**）：

| 检查 | 失败含义 |
|------|---------|
| XML 结构合法 | 清单或资源文件语法错误，构建必然失败 |
| 资源引用可解析 | `@drawable/x`、`R.string.y` 指向不存在的资源（AAPT 链接期错误） |
| 契约类/函数齐备 | `AppContainer` 引用的实现类、导航层引用的屏幕函数缺失（编译错误） |
| 日志不含敏感字段 | `Logs.*(...)` 调用中出现 password/token/密码 等字样（**安全红线**） |
| 统一使用 Logs 门面 | 直接使用 `android.util.Log`，绕过统一出口 |
| 无 System.out 打印 | 手表端无 stdout，且可能泄露信息 |
| 加解密不依赖 `android.util.Base64` | 会破坏纯 JVM 单元测试能力 |
| **AES-GCM 与 Android Keystore 兼容** | 加密时向 `Cipher.init` 传了调用方 IV（Keystore 会抛 `InvalidAlgorithmParameterException`），或解密路径没传 IV |
| **圆屏纵向预算** | 顶部/底部固定条把信息流挤到干净可视带 < 100dp（用户两次反馈"信息流太小"的量化形式），或迷你按钮低于 48px 触控下限 |
| **覆盖层不透明且拦截指针** | 覆盖层根节点漏画背景（会透出下层页面）或导航层漏拦指针（空白处会误触下层分页） |
| Kotlin 括号配平 | 明显的语法破损 |
| 每个 Kotlin 文件声明包名 | 文件头缺失 `package`，构建失败 |

> 静态规则的价值在于**把"真机才发现的问题"变成可自动检查的约束**。上面三条加粗规则
> 分别对应三个真实缺陷（§5.3 的 3/4/5/6），且每条都用「模拟回到缺陷版本」验证过它确实会 FAIL。

## 5. 构建与测试结果

### 5.1 构建

| 任务 | 结果 | 产物 |
|------|------|------|
| `:app:testDebugUnitTest` | ✅ 221 / 221 通过 | `app/build/reports/tests/testDebugUnitTest/index.html` |
| `:app:assembleDebug` | ✅ BUILD SUCCESSFUL（**27.76 MB**） | `app/build/outputs/apk/debug/app-debug.apk` |
| `:app:assembleRelease`（R8 + 资源压缩） | ✅ BUILD SUCCESSFUL（**3.35 MB**） | `app/build/outputs/apk/release/app-release.apk` |

代码规模：87 个 Kotlin 文件 / 约 15,800 行。编译期仅有弃用警告（`Icons.Filled.ArrowBack`
等迁移到 `Icons.AutoMirrored`，Material Icons 1.7.7 的 deprecation），不影响功能。

### 5.2 单元测试：221 / 221 全部通过

逐类结果见 §3 的用例数，合计 **221 用例 / 0 失败 / 0 错误 / 0 跳过**，总耗时约 0.7 秒。

其中 `ConfigWebServerTest` 是**真实起 HTTP 服务**（`ServerSocket` 绑定随机端口）并用
`HttpURLConnection` 发 GET/POST 的集成式单元测试，覆盖查询串 URL 解码、表单正文解析、
404 分支、`stop()` 幂等与端口重绑定。

`MicrosoftOAuthTest` 与 `NotificationDiagnosticsTest` 都把「网络」与「时钟」抽成可注入依赖
（`FormPoster` 假实现 + 假时钟 + 假 sleeper），因此**不需要网络、不需要等待**即可覆盖
`authorization_pending` 轮询、`slow_down` 间隔递增、网络抖动容忍、连续失败上限等全部分支。

### 5.3 测试与真机反馈发现的真实缺陷（值得记录的案例）

**（1）生产缺陷：密文格式缺少分隔符 → 加密后的密码永远无法解密**

首轮运行时有 **10 个失败**，定位到 1 个**生产代码缺陷** + 2 个测试用例自身的边界输入错误。

缺陷：`CryptoManagerImpl.encrypt()` 拼密文时漏了版本前缀后的分隔符。

```
期望（契约与 decrypt 的解析约定）：v1:<base64(iv)>:<base64(ct)>  → split(":") 得到 3 段
实际（缺陷版本）：                v1<base64(iv)>:<base64(ct)>    → split(":") 得到 2 段 → decrypt 返回 null
```

后果是**任何密码经加密落盘后都无法再解出**（即添加账户后无法收信）。该缺陷在人工代码评审时被漏过，
由「加密→解密往返」「密文必须以 v1: 开头」「密文分段数量应为 3」三个断言捕获（10 个失败用例均由此引发）。

> 由于该缺陷下密文从未能被成功解密，历史上不可能写入过可用凭据数据，因此**不涉及数据迁移**。

**（2）测试自身的边界输入错误（2 例）**

断言与实现约定不一致：测试传入的字符串**正好等于**上限长度（14 字符 / 8 字符），
而实现约定「放得下就完整显示」—— 即断言与输入不匹配。修正为传入「超出 1 个字符」的输入后通过；
实现保持「恰好等于上限不截断」，因为截断能放下的文本会白白浪费一个字符预算。

**（3）覆盖层透明缺陷（由用户反馈发现，非测试发现）**

用户反馈「添加账户界面的背景能看到上一页的东西」。根因：覆盖层（详情/撰写/添加账户）
与顶部分页是同一个 `Box` 的兄弟节点，而 `CircularScreen` 根节点**没有绘制任何背景** ——
分页背后是 Activity 的黑色窗口所以看不出问题，覆盖层透明就直接透出了下面的收件箱。

修复：① `CircularScreen` 根节点统一绘制 `colorScheme.background`；② `WearMailNav` 在覆盖层内容
之下、分页之上增加一层不透明遮罩并消费所有 `PointerEvent`（否则空白处的点击/滑动还会穿透到分页，
造成误切换页面/误开邮件）。并新增静态检查「覆盖层不透明且拦截指针」防回归
（已用"模拟回到缺陷版本"验证该规则确实会 FAIL）。

> 这条属于**渲染/交互类缺陷，本地无设备无法自动化验证**，因此补了静态规则 + 真机手工清单项（§7 第 21 条）。

**（4）手机扫码「提交失败」（由用户反馈发现）**

用户反馈「手机扫码提示提交失败」。根因有三层，全部与"依赖前端、失败信息又太笼统"有关：

| 问题 | 说明 |
|------|------|
| 主机留空 → 直接判失败 | IMAP/SMTP 主机输入框**没有默认值**，完全依赖内联 JS 在选中预设/邮箱失焦时填充；域名不在预设表里（企业邮箱、自建、iCloud、sina…）时必然为空，服务端却直接回"IMAP 服务器地址不能为空" |
| 前端不提示、后端只说"提交失败" | 表单带 `novalidate`，浏览器的就地必填提示被关掉；结果页标题固定为"提交失败"，具体原因在小字里，手机上极易被忽略 |
| 重复扫码撞唯一约束 | 同一邮箱第二次扫码走 `insert` → UNIQUE 冲突 → 仍然只回一句"提交失败" |

修复（4 处，含 1 个连带 bug）：

1. **服务端按域名兜底**：新增纯函数 `PairingFormDefaults.resolve()` —— 表单填全就尊重表单；
   没填全则按「显式选中的服务商 → 邮箱域名」补齐；确实识别不出来才报错，并明确说明
   "请向邮箱服务商查询后填写"。
2. **前端不再拦必填 + 后端错误标题即原因**：去掉 `novalidate`，主机改为可留空，
   邮箱/密码保留 `required` 交给浏览器就地提示；失败页标题直接显示**具体原因**
   （如"IMAP 端口必须是 1-65535 之间的数字（收到：「９９３」）"）。
3. **重复邮箱改为"重新配置"**：同邮箱再次提交时走 `update`（保留标识色/通知开关/同步时间），
   成功页区分"已保存账户"与"已更新账户"。
4. **连带 bug**：错误页的「返回修改」链接原本是 `/pair`（丢了 token），点回去会命中 403
   「配对链接已失效」；现在链接带回 `?t=<token>`。

新增 27 个用例（`PairingFormDefaultsTest` 14 + `PairingPageTest` 13），其中
`PairingPageTest` 是**网页与服务端的契约测试**：断言表单包含服务端解析的全部字段名、
`select` 的 value 必须是枚举名（而不是中文标签）、主机不得加 `required`
（否则服务端兜底失效）、token 做了 HTML 转义等 —— 这类漂移过去只能靠用户在手机上撞见。

**（5）真机保存账户失败 `InvalidAlgorithmParameterException`（由用户反馈发现）**

用户反馈错误页显示：`手表端保存失败（InvalidAlgorithmParameterException），请重试，或改用手表上的「添加账户」。`

（顺带说明：这条信息之所以能直接定位，是因为上一轮把异常类型写进了结果页 —— 笼统的"提交失败"是无法自诊断的。）

根因：**Android Keystore 生成的密钥默认 `setRandomizedEncryptionRequired(true)`，此时不允许调用方自带 IV**。
原实现自己用 `SecureRandom` 生成 12 字节 IV 并传给
`Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))` —— 在普通 JCE 密钥下完全合法，
但在 Keystore 密钥上直接抛 `InvalidAlgorithmParameterException`。

**为什么单元测试没抓到**：`CryptoManagerImplTest` 用的是内存假密钥（普通 JCE），
自带 IV 是允许的；这条约束只在真实 Keystore 上成立，纯 JVM 测试无法覆盖。

修复：

1. 加密改为**不传 IV**（`cipher.init(ENCRYPT_MODE, key)`），从 `cipher.iv` 读回
   Keystore 生成的 IV 并随密文保存 —— 这正是 Android 官方文档推荐写法；
2. 解密仍由调用方提供 IV（随机化加密限制**只作用于加密方向**）；
3. 解密不再写死"IV 必须 12 字节"：不同厂商 Keystore 生成长度可能有差异，
   放宽为"非空即可"，错误 IV 由 GCM 认证标签兜底拒绝（安全性不变）；
   新增 `解密空 IV 返回 null` 用例。

防回归：新增静态检查「AES-GCM 与 Android Keystore 兼容」——
断言 `encrypt` 不出现 `GCMParameterSpec`、且必须从 `cipher.iv` 取 IV；解密路径必须传 IV。
并用"模拟回到缺陷写法"验证过该规则确实会 FAIL。

**（6）信息流被固定条挤小（由用户两次反馈发现）**

用户两次反馈「中间显示信息流的地方太小了」，第二次明确要求「缩小按钮到原来的一半」。
根因是一条公式：`干净可视带 = 233dp − 顶部固定占用 − 底部固定占用`。

| 版本 | 顶部 | 底部 | 干净可视带 |
|------|-----:|-----:|-----------:|
| 初版 | 88dp | 136dp | 9dp（不到一行） |
| 上一轮 | 84dp | 84dp | 65dp（约 1.5 行） |
| **本轮** | **56dp** | **54dp** | **123dp（约 3 行，+89%）** |

本轮做法：新增 `MiniActionButton` / `MiniOptionButton`（高度 24dp = 52dp 按钮的一半），
筛选胶囊与「新建邮件」全部换用；24dp × 密度 2.0 = **48px** 恰好是需求要求的触控下限，
因此这是"不能再小"的硬下限。注意**不能**用 `CompactButton` + `Modifier.height(24.dp)`：
它内部固定 48dp 高，外部压高度只会裁切内容。

防回归：新增静态检查「圆屏纵向预算」，断言干净带 ≥ 100dp 且迷你按钮 ≥ 48px
（已用 65dp / 9dp 两个历史布局验证规则会 FAIL）。

### 5.4 复现命令

```bash
# 首次（需联网下载依赖）
./gradlew --init-script tools/mirrors-init.gradle :app:testDebugUnitTest :app:assembleDebug

# 依赖已缓存后（可离线）
./gradlew --offline :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease

# 静态检查（不需要 Gradle / SDK）
python tools/static_checks.py
```

静态检查结果：**12 项全部通过**（XML 结构、资源引用、20 个契约点、日志不泄漏敏感值、
统一日志门面、无 `System.out`、加解密不依赖 `android.util.Base64`、
**AES-GCM 与 Android Keystore 兼容**、**圆屏纵向预算**、**覆盖层不透明且拦截指针**、
括号配平、包名声明）。

### 5.5 构建环境说明（不影响交付物本身）

| 现象 | 处理 |
|------|------|
| 该机器 HTTPS 流量被代理根证书拦截，JDK 自带 `cacerts` 不含该根证书，gradle 报 `PKIX path building failed` | 构建时临时用 `-Djavax.net.ssl.trustStoreType=Windows-ROOT` 让构建 JVM 使用 Windows 证书存储；**该参数写在 `gradle.properties` 中并在交付前删除** |
| `services.gradle.org` 被限速到约 50 KB/s（30 MB 需 45 分钟），官方 Maven 源亦较慢 | 用 `tools/mirrors-init.gradle` 初始化脚本临时注入国内镜像（**不改变 `settings.gradle.kts`**，保证交付物的依赖来源仍是官方仓库） |
| 受限沙箱不允许 Kotlin 编译守护进程在用户目录写临时文件（`AccessDeniedException`） | 构建时加 `-Pkotlin.compiler.execution.strategy=in-process` 改为进程内编译（正常开发机不需要） |

**编码事故（本次重建本文档的原因）**：Windows PowerShell 5.1 的 `Get-Content -Raw` 按 **ANSI（cp936）**
读取 UTF-8 文件、`Set-Content -Encoding utf8` 再写回，属于**有损转换**（.NET 的 best-fit 替换），
导致中文文档不可逆损坏（本次损坏了 `docs/TESTING.md`，以及同一批操作涉及的另一个文件）。
教训：**在 Windows 上批量改文本一律用 Python（显式 `encoding='utf-8'`）或编辑器工具，
不要用 PowerShell 文本 cmdlet 做读写往返**；`Copy-Item` 是字节级操作，安全。

## 6. 本地未验证项（诚实说明）

以下内容**未能在本次环境中验证**，原因与风险如下：

| 未验证项 | 原因 | 风险 |
|---------|------|------|
| IMAP/SMTP 真实收发 | 无真实邮箱账号，且沙箱网络仅允许受限出网 | 中：JavaMail 用法按官方约定编写，但各服务商策略差异（如 QQ 需授权码、163 需 ID 命令）需真机确认 |
| IMAP IDLE 推送 | 同上 | 中：已实现"不支持时退化为轮询"的兜底 |
| Outlook OAuth2 授权往返 + XOAUTH2 登录 | 无 Azure 应用注册、无真实 Outlook 账户 | 中高：端点/解析/轮询/刷新/存储有 34 个单测覆盖，但真实端点行为（同意页、令牌下发、XOAUTH2 握手）必须真机确认；客户端 ID 需使用者自行注册 |
| 通知实际弹出 | 需要设备 | 低：`NotificationCompat` 标准用法；**已提供「测试通知」+ 链路自检**（§7 第 25 条）以便真机直接定位 |
| 数据库读写与 LRU 淘汰 | 需要 Android 运行时（未引入 Robolectric） | 中低：SQL 为手写，已用 `SQLiteOpenHelper` 标准用法 |
| Compose UI 渲染 / 手势 / 表冠 | 需要设备或模拟器 | 中：布局按 `docs/UI_SPEC.md` 的弦长表设计，几何部分有单测 |
| R8 压缩后的运行时行为 | 需要设备回归 | 低：已提供 JavaMail 反射 keep 规则；如不放心可关闭 `isMinifyEnabled` |
| 功耗与内存峰值 | 需要真机 Profiler | 中：已按需求做上限与节流设计，但未实测 |

## 7. 真机手工验收清单

安装后按顺序验证（每项都对应一条需求）：

1. **冷启动**：清除后台后启动，秒表计时 < 2s，首帧为空态骨架。
2. **添加账户（扫码）**：进入「账户管理 → 添加账户 → 用手机扫码配置」，
   手机与手表连同一 Wi-Fi，扫码后在手机填写 QQ 邮箱 + 授权码并提交；
   手表端应立即提示"已保存 1 个账户"。
3. **添加账户（手动/自动探测）**：输入邮箱与授权码 → 「自动探测」应回填
   `imap.qq.com:993` 与 `smtp.qq.com:465`；「验证连接」通过后保存。
4. **首次同步**：回到收件箱下拉刷新，5 秒内应出现最近 50 封邮件（仅元数据）。
5. **圆形适配**：滚动到列表两端，确认没有任何文字被圆弧裁切；
   在表盘上下边缘的列表项应自动变窄。
6. **未读与来源标识**：未读邮件主题加粗并有圆点；不同账户的色点颜色不同。
7. **筛选器**：点击账户筛选，列表只显示该账户邮件；点「全部」恢复合并视图。
8. **表冠**：旋转表冠，列表平滑滚动（不应跳变或与分页冲突）。
9. **详情**：点击邮件进入详情，正文为纯文本（HTML 邮件不应出现标签），
   上下滚动流畅；进入后该邮件变为已读。
10. **底部操作栏**：回复 → 跳转撰写页且主题带 `Re:`；删除 → 二次确认后返回列表且邮件消失；
    标记未读 → 返回列表后该行恢复加粗。
11. **发送**：撰写页用常用联系人快捷填入收件人，发送 → 转圈 → ✓ 且振动；对方应收到邮件。
12. **发送失败与草稿**：断开 Wi-Fi 后发送 → ✗ + 振动 + 重试按钮；恢复网络后
    自动补投（或点重试）。
13. **通知**：用另一邮箱给自己发一封邮件；30–60 秒内（或下拉刷新后）手表应收到通知，
    内容为「发件人 + 主题前 30 字符」，点击直达该邮件详情。
14. **按账户关闭通知**：在设置里关闭某账户通知，再次收信应不再提示。
15. **左右滑导航**：收件箱左滑 → 账户管理；右滑 → 设置；确认没有触发系统退出。
16. **长按菜单**：长按邮件行 → 弹出标记已读/标星/删除菜单；操作均有振动。
17. **设置项**：切换同步频率后，前台定时同步间隔应随之变化；开启「仅 Wi-Fi」后蜂窝网络不同步。
18. **缓存上限**：设置页显示的元数据/正文数量不应超过 500 / 50。
19. **深色省电**：界面背景为纯黑（OLED），无亮色大色块。
20. **异常降级**：飞行模式下打开应用 → 应展示本地缓存邮件并提示网络不可用，不崩溃。
21. **覆盖层背景**：从收件箱进入「添加账户」「撰写邮件」「邮件详情」时，**不应看到上一页的任何内容**；
    在覆盖层空白处上下/左右滑动、点击，也不应触发后面的分页切换或误开邮件
    （曾经的缺陷，修复见 §5.3 第 3 条）。
22. **扫码配置（含留空兜底）**：手机填写邮箱+授权码后**不填 IMAP/SMTP 地址**直接提交：
    - QQ/Gmail/163/Outlook 邮箱应保存成功（服务端按域名自动识别），并显示「已保存账户」；
    - 企业邮箱/自建域名应给出**明确提示**（而不是笼统的"提交失败"）；
    - 对同一邮箱**再扫一次**应显示「已更新账户」，不应报错；
    - 出错页点「返回修改」应能回到表单（不应出现"配对链接已失效"）。
23. **凭据加密落盘可读写（Keystore 路径）**：添加账户成功后**杀掉应用重开**，
    下拉刷新该账户应能正常收信（说明密码已加密写入、并能用 Keystore 密钥解密回来）。
    若保存时报 `InvalidAlgorithmParameterException`，说明 Keystore 的随机化加密约束又被破坏
    （见 §5.3 第 5 条，已有静态检查守着）。
24. **Outlook OAuth2（设备码流）**：先在「设置 → Outlook OAuth2」填入自己注册的客户端 ID
    （注册步骤见 [TECHNICAL.md](TECHNICAL.md) 的「启用 Outlook（OAuth2）」），然后「添加账户」→
    邮箱填 Outlook 地址 → 登录方式选 **OAuth2 令牌** → 「获取授权」：
    - 手表应显示 8 位短码 + 二维码 + 授权网址；
    - 手机扫码打开授权页、输入短码并同意 → 手表应显示「已授权（有效期至 HH:mm）」；
    - 保存后回收件箱下拉刷新，应能收到该邮箱邮件（**这条同时验证 XOAUTH2 登录**）；
    - 点「取消」或拒绝授权 → 应回到「获取授权」按钮，不留下半成品账户；
    - 未填客户端 ID 时 → 应显示注册指引文案，而不是静默失败或只报"保存失败"；
    - 授权 1 小时后再刷新 → 应自动续期而无需重新授权（**验证 refresh token 链路**）。
25. **通知测试与自检**：设置页「通知」分组：
    - 自检结论应反映真实状态：系统未授权 → 提示去系统设置；渠道被关 → 提示打开渠道；
      应用内总开关关闭 → 明确说"真实邮件不会提醒"；某账户被单独关闭 → 列出该账户名；
    - 点「测试通知」应立刻弹出一条通知（内容为「通知测试 / 这是一条测试通知…」）并振动；
      点击后应打开应用首页（不应进入任何邮件详情）；
    - 把应用内总开关关掉后再点「测试通知」：通知**仍应弹出**（有意设计，
      用于区分"系统不允许"与"应用内被关掉"），但自检结论必须明确说明真实邮件会被拦下。
26. **信息流可视带**：收件箱中间可见的邮件行数应 ≥ 3 行；顶部筛选胶囊与底部「新建邮件」
    按钮视觉高度应约为普通按钮的一半，且**仍然容易点中**（热区 48px）。
