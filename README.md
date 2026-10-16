<div align="center">

# 腕邮 WearMail

**为 466 × 466 圆形表盘从零设计的 Wear OS 邮箱客户端**

多账户统一收件箱 · IMAP 收信 · SMTP 发信 · 新邮件通知 · 手机扫码配置 · Outlook OAuth2

![Platform](https://img.shields.io/badge/Wear%20OS-3.0%2B%20(API%2030%2B)-blue)
![Kotlin](https://img.shields.io/badge/Kotlin-2.1.0-7F52FF)
![Compose](https://img.shields.io/badge/Compose%20for%20Wear-1.5.6-4285F4)
![Tests](https://img.shields.io/badge/tests-285%20passing-brightgreen)
![Static checks](https://img.shields.io/badge/static%20checks-24%20passing-brightgreen)
![APK](https://img.shields.io/badge/release%20APK-3.51%20MB-informational)

</div>

---

## 这是什么

一个**完整的、可编译运行的** Wear OS 邮箱客户端：手写 SQLite + JavaMail 收信发信、Android Keystore
加密保管凭据、局域网扫码从手机配置账户、Outlook/Office 365 走 OAuth2 设备码流。

项目不是"能跑就行"的演示：**285 个纯 JVM 单元测试 + 24 项静态检查**全绿，圆屏布局按几何公式推导，
每一处设计取舍与踩过的坑都写在文档里（包括**至今仍未在真机验证的部分**）。

- **多账户统一收件箱**：Gmail / Outlook / QQ / 163 等预设加自定义服务器，IMAP 收信、SMTP 发信，
  按时间倒序合并展示（账户筛选、未读标记、下拉刷新）；
- **配置不靠手表打字**：手机扫码（手表起一个一次性 token 的局域网 HTTP 服务并显示二维码）
  或在手表上手动/自动探测服务器参数；Outlook 走 OAuth2 设备码流（手表显示短码即可完成授权，
  正式发布版已内置客户端 ID；自行构建时需在 Azure 注册应用后填入设置页）；
- **凭据只存密文**：Android Keystore + AES-256-GCM 加密落盘（含 OAuth2 refresh token），
  日志不写敏感值，全程 TLS/STARTTLS；
- **应用内更新**：启动自动比对 GitHub Releases，弹窗展示更新日志并可直接下载安装
  （直连慢/失败自动切换 gh-proxy 加速），设置页可手动检查。

> 技术文档（完整功能清单 / 构建 / 安全说明 / 已知限制）见 **[docs/TECHNICAL.md](docs/TECHNICAL.md)**，
> 其余文档见 [docs/](docs/)。

## 项目结构

```
app/src/main/java/com/wm/wearmail/
├── core/      AppContainer · Logs · RotaryBus · WearMailApp
├── model/     Account · EmailMeta · Draft · ProviderPresets · AuthChoice · OAuthTokens
├── mail/      ImapClient · SmtpClient · ServerProbe · MailError · oauth/
├── data/      crypto/ · db/ · prefs/ · repo/
├── sync/      SyncEngine · SyncService · SyncWorker
├── notify/    NotificationCenter · NotificationDiagnostics
├── pairing/   PairingController · ConfigWebServer · PairingPage · QrCodeRenderer
├── update/    UpdateCenter · GitHubUpdateChecker · ApkDownloader · ApkInstaller
├── ui/        theme/ · kit/ · nav/ · screen/{inbox,detail,compose,accounts,settings}
└── util/      TimeFormat
app/src/test/  22 个测试类 / 285 个用例
tools/         static_checks.py · test_summary.py · mirrors-init.gradle
docs/          TECHNICAL.md · ARCHITECTURE.md · UI_SPEC.md · TESTING.md
```

## 许可证

MIT

## 免责声明

本项目为个人独立实现，与 Microsoft、Google 无任何关联；Outlook、Wear OS、Android 等名称与商标
归各自所有者。请自行确认你的邮箱服务商条款允许第三方客户端通过 IMAP/SMTP 访问。
