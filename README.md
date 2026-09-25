<div align="center">

# ⌚ WatchMail

**在 466×466 圆形表盘上完整收发邮件的 Wear OS 客户端**

多账户统一收件箱 · IMAP/SMTP · 手机扫码配对 · 表冠滚动 · 离线草稿

</div>

---

## ✨ 特性

| | |
|---|---|
| 📬 **统一收件箱** | 多个邮箱账户的邮件按时间倒序合并展示，可按账户筛选 |
| 📱 **手机扫码配对** | 手表起本地 Web 服务生成二维码，同一 WiFi 下手机扫码填表，免手表打字 |
| ✉️ **收发一体** | IMAP 增量收件（UID 范围 FETCH）+ SMTP 发件（SSL/STARTTLS） |
| 🔔 **智能通知** | 新邮件振动提醒，点击直达该邮件详情；支持按账户开关 |
| 💾 **离线优先** | 邮件元数据本地缓存，正文按需下载；发送失败自动转草稿联网补发 |
| 🔒 **安全** | 密码 KeyStore + AES-GCM 加密落库；扫码配对全程 AES 加密传输 |
| ⚙️ **圆形表盘适配** | 列表项宽度随 Y 坐标动态收窄、固定栏按弦宽布局、界面整体缩放 |
| 🎛️ **表冠支持** | 适配标准旋转编码器与 OPPO 等厂商的鼠标滚轮式表冠，带刻度震动 |

## 📸 页面

| 统一收件箱 | 邮件详情 | 扫码配对 | 设置 |
|---|---|---|---|
| 合并多账户邮件<br>顶栏筛选 / 底栏操作 | 纯文本正文<br>回复 / 已读 / 删除 | 二维码 + 配对码<br>手机填表回传 | 同步频率<br>界面大小 / 通知 |

## 🚀 快速开始

### 环境要求

- Android Studio Hedgehog 或更高
- JDK 17+
- Wear OS 3.0+（API 30+）设备或模拟器

### 构建运行

```bash
git clone https://github.com/haloged/Watch-Mail
cd Watch-Mail
./gradlew :app:assembleDebug
```

或直接用 Android Studio 打开项目，连接手表后 Run。

### 添加账户

**方式一：手机扫码（推荐）**
1. 手表端进入「账户管理 → 手机扫码配置」
2. 手机与手表连接同一 WiFi，扫描二维码
3. 手机浏览器填入邮箱、密码/授权码，提交

**方式二：手表手动输入**
「账户管理 → +」，支持自动探测服务器配置。

## 📮 支持的邮箱

| 服务商 | IMAP | SMTP | 备注 |
|---|---|---|---|
| Gmail | imap.gmail.com:993 | smtp.gmail.com:587 | 需[应用专用密码](https://support.google.com/accounts/answer/185833) |
| Outlook / Hotmail | outlook.office365.com:993 | smtp.office365.com:587 | 需应用密码 |
| QQ 邮箱 | imap.qq.com:993 | smtp.qq.com:587 | 需开启 IMAP 并使用授权码 |
| 163 / 126 | imap.163.com:993 | smtp.163.com:465 | 需授权码 |
| iCloud | imap.mail.me.com:993 | smtp.mail.me.com:587 | 需应用专用密码 |
| 企业邮箱 | 通用 IMAP | 通用 SMTP | 也可在「高级配置」手动填写 |

## 🔐 隐私与安全

- **密码不落明文**：所有凭据用 Android KeyStore + AES-256-GCM 加密后存入本地数据库
- **扫码配对加密**：配对码放在 URL fragment（不随 HTTP 请求发出），浏览器内 AES 加密整表单后提交，局域网抓包无法解密
- **单次会话**：配对服务收到一次提交即关闭，5 分钟超时，连续校验失败自动停止
- **无遥测**：不收集任何使用数据，不接入第三方统计

## 🗂️ 项目结构

```
app/src/main/java/com/haloged/watchmail/
├── data/
│   ├── local/          # Room 数据库、DataStore 设置
│   ├── remote/
│   │   ├── imap/       # IMAP 收件同步
│   │   ├── smtp/       # SMTP 发件
│   │   └── pairing/    # 扫码配对 Web 服务 + 手机端 H5
│   └── repository/     # 数据仓库、LRU 缓存
├── service/            # WorkManager 后台同步、通知
├── ui/
│   ├── components/     # 按钮、列表项等通用组件
│   ├── screen/         # 收件箱 / 详情 / 撰写 / 账户 / 设置 / 开屏
│   ├── util/           # 圆形几何、表冠、弦宽约束
│   └── viewmodel/
└── util/               # 加密、震动、网络、二维码
```

详细设计请参阅 **[TECHNICAL.md](TECHNICAL.md)**（技术选型、数据库 Schema、核心模块实现、圆形适配推导、异常处理策略）。

## 📄 License

MIT License
