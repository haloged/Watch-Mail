"""把根目录 README.md 转成 docs/TECHNICAL.md（字节级操作，避免编码损坏）。

同时：
- 修正相对链接（从 docs/ 视角）
- 在开头加一段「本文档定位」说明
- 追加「发布前检查（不要上传的文件）」章节
"""

from pathlib import Path

ROOT = Path(r"D:\wm")
readme_bytes = (ROOT / "README.md").read_bytes()

# 1) 链接修正（纯 ASCII，字节级替换，零编码风险）
technical = readme_bytes.replace(b"](dist/", b"](../dist/").replace(b"](docs/", b"](")

# 2) 开头说明
header = (
    "> 本文档是 **WearMail 的技术与验证文档**：完整功能清单、构建与测试方式、目录结构、\n"
    "> 安全与隐私说明、已知限制。面向使用者的项目介绍请看根目录 [README.md](../README.md)。\n"
    "\n"
).encode("utf-8")

# 3) 追加「不要上传」章节
footer = (
    "\n"
    "---\n"
    "\n"
    "## 发布前检查（哪些文件不要上传）\n"
    "\n"
    "`git add` 之前确认下列内容**没有**进入仓库（`.gitignore` 已排除）：\n"
    "\n"
    "| 路径 | 为什么不要上传 |\n"
    "|------|---------------|\n"
    "| `local.properties` | 含本机 Android SDK 绝对路径（`sdk.dir=...`），对他人无用且暴露本机目录结构 |\n"
    "| `keystore/`（`debug.keystore`） | 签名证书。即使是密码公开的调试证书，提交进仓库也是坏习惯（可能被误用于签名发布）；构建脚本已改为**证书缺失也能构建** |\n"
    "| `.gradlehome/`、`.gradle-dist/`、`.gradle/` | Gradle 缓存与本地解压的发行版（数百 MB） |\n"
    "| `.tmp/`、`.refsrc/`、`.androidhome/` | 构建过程临时目录 / SDK 参考源码 / Android 用户目录 |\n"
    "| `build/`、`app/build/` | 构建产物（每次构建都变） |\n"
    "| `dist/*.apk` | APK 二进制（27.8 MB / 3.35 MB）。建议用 GitHub Releases 分发，而不是提交进仓库 |\n"
    "| `dist/reports/unit-tests*/` | 生成的测试报告（HTML/XML） |\n"
    "| `*.iml`、`.idea/`、`.DS_Store` | IDE 与系统文件 |\n"
    "\n"
    "**可以（也应该）上传**：`app/src/**`、`gradle/wrapper/**`（含 `gradle-wrapper.jar`）、\n"
    "`gradle/libs.versions.toml`、`*.gradle.kts`、`gradle.properties`、`tools/**`、`docs/**`、\n"
    "`README.md`、`.gitignore`。\n"
    "\n"
    "**首次公开前建议做的三件事**：\n"
    "\n"
    "1. 选一个开源许可证并放置 `LICENSE` —— 当前仓库**没有**许可证，法律上等同于保留所有权利；\n"
    "2. 自查历史里没有真实凭据：`git log -p | grep -i -E 'clientId|password|token|keystore'`\n"
    "   （客户端 ID 属半公开信息，但截图与日志里可能夹带真实邮箱地址）；\n"
    "3. 在 README 里说明「Outlook OAuth2 的客户端 ID 需使用者自行注册」，避免他人以为开箱即用。\n"
).encode("utf-8")

output = header + technical + footer

# 4) 自检：必须能按 UTF-8 解码，且不应出现替换字符
text = output.decode("utf-8")
assert "\ufffd" not in text, "输出中出现替换字符，说明源文件已损坏"
assert "](docs/" not in text, "仍有未修正的 docs/ 链接"
assert "](dist/" not in text, "仍有未修正的 dist/ 链接"

(ROOT / "docs/TECHNICAL.md").write_bytes(output)

print(f"docs/TECHNICAL.md 已生成：{len(output)} 字节，{text.count(chr(10)) + 1} 行")
print("链接自检通过，无替换字符")
