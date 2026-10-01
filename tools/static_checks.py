#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
WearMail 静态检查脚本
=====================

不依赖 Gradle / Android SDK，可在源码交付前快速发现以下几类问题：

1. XML 结构错误（清单、资源文件）；
2. 资源引用悬空（@drawable/xxx、R.string.xxx 指向不存在的资源）；
3. 契约不一致（AppContainer / 导航引用的类与函数必须真实存在）；
4. 安全红线（密码等敏感信息进入日志、绕过统一日志门面、明文打印）；
5. 粗粒度的 Kotlin 语法体检（括号/花括号配平）。

用法：
    python tools/static_checks.py [--root D:\\wm]

退出码：0 = 全部通过；1 = 存在失败项。
"""

import argparse
import os
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

# --------------------------------------------------------------------------
# 检查结果收集
# --------------------------------------------------------------------------
class Report:
    def __init__(self) -> None:
        self.failures: list[str] = []
        self.warnings: list[str] = []
        self.checks: list[tuple[str, bool, str]] = []

    def check(self, name: str, ok: bool, detail: str = "") -> None:
        self.checks.append((name, ok, detail))
        if not ok:
            self.failures.append(f"{name}: {detail}")

    def warn(self, message: str) -> None:
        self.warnings.append(message)

    def print(self) -> None:
        print("=" * 74)
        print("WearMail 静态检查报告")
        print("=" * 74)
        for name, ok, detail in self.checks:
            mark = "PASS" if ok else "FAIL"
            line = f"[{mark}] {name}"
            if detail:
                line += f" — {detail}"
            print(line)
        if self.warnings:
            print("-" * 74)
            print(f"警告 {len(self.warnings)} 项：")
            for w in self.warnings:
                print(f"  ! {w}")
        print("-" * 74)
        if self.failures:
            print(f"结果：失败 {len(self.failures)} 项")
            for f in self.failures:
                print(f"  x {f}")
        else:
            print("结果：全部通过")


# --------------------------------------------------------------------------
# 1. XML 结构
# --------------------------------------------------------------------------
ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

# 圆屏几何：466px 表盘在 density 2.0 下高 233dp（与 ui/kit/CircularMetrics.kt 一致）
SCREEN_HEIGHT_DP = 233.0
DENSITY = 2.0

# 信息流干净可视带下限：约 2.5 行邮件（一行列表项约 40dp）
MIN_CLEAN_BAND_DP = 100.0

# 需求要求的最小触控热区（px）
MIN_TOUCH_TARGET_PX = 48.0


def check_xml(report: Report, root: Path) -> None:
    xml_files = sorted(root.glob("app/src/main/**/*.xml"))
    xml_files.append(root / "app/src/main/AndroidManifest.xml")
    bad: list[str] = []
    for path in sorted(set(xml_files)):
        if not path.exists():
            continue
        try:
            ET.parse(path)
        except ET.ParseError as exc:
            bad.append(f"{path.relative_to(root)}: {exc}")
    report.check("XML 结构合法", not bad, "; ".join(bad) if bad else f"{len(set(xml_files))} 个文件")


# --------------------------------------------------------------------------
# 2. 资源引用
# --------------------------------------------------------------------------
def collect_defined_resources(root: Path) -> dict[str, set[str]]:
    defined: dict[str, set[str]] = {
        "drawable": set(),
        "mipmap": set(),
        "string": set(),
        "color": set(),
        "style": set(),
        "xml": set(),
    }
    res_dir = root / "app/src/main/res"
    if not res_dir.exists():
        return defined

    for path in res_dir.rglob("*"):
        if not path.is_file():
            continue
        parent = path.parent.name
        # 目录型资源：drawable-xxhdpi/ic_x.png -> drawable/ic_x
        if parent.startswith("drawable"):
            defined["drawable"].add(path.stem)
        elif parent.startswith("mipmap"):
            defined["mipmap"].add(path.stem)
        elif parent.startswith("xml"):
            defined["xml"].add(path.stem)
        elif parent == "values":
            try:
                tree = ET.parse(path)
            except ET.ParseError:
                continue
            for child in tree.getroot():
                name = child.get("name")
                if not name:
                    continue
                if child.tag == "string":
                    defined["string"].add(name)
                elif child.tag == "color":
                    defined["color"].add(name)
                elif child.tag == "style":
                    defined["style"].add(name)
    return defined


RES_ATTR_RE = re.compile(r"@(?!android:)(drawable|mipmap|string|color|style|xml)/([A-Za-z0-9_.]+)")
R_CLASS_RE = re.compile(r"\bR\.(drawable|mipmap|string|color|style|xml)\.([A-Za-z0-9_]+)")


def check_resources(report: Report, root: Path) -> None:
    defined = collect_defined_resources(root)
    missing: list[str] = []

    targets: list[Path] = []

    def add(path: Path) -> None:
        if path.exists() and path.is_file():
            targets.append(path)

    # 所有源码 / 资源 / 清单
    for pattern in (
        "app/src/main/**/*.kt",
        "app/src/main/**/*.xml",
    ):
        for path in root.glob(pattern):
            add(path)

    for path in sorted(set(targets)):
        text = path.read_text(encoding="utf-8", errors="ignore")
        rel = path.relative_to(root)
        for kind, name in RES_ATTR_RE.findall(text):
            # 排除 android: 平台资源（正则已排除 android: 前缀的情况）
            if name not in defined[kind]:
                missing.append(f"{rel}: @{kind}/{name}")
        for kind, name in R_CLASS_RE.findall(text):
            if name not in defined[kind]:
                missing.append(f"{rel}: R.{kind}.{name}")

    report.check(
        "资源引用可解析",
        not missing,
        "; ".join(sorted(set(missing))[:12]) if missing else f"{len(defined['drawable'])} drawable / {len(defined['mipmap'])} mipmap / {len(defined['string'])} string",
    )


# --------------------------------------------------------------------------
# 3. 契约一致性
# --------------------------------------------------------------------------
CONTAINER_CONTRACT = {
    # AppContainer 中引用的实现类 -> 期望文件（相对 app/src/main/java）
    "com.wm.wearmail.data.crypto.AndroidKeystoreKeyProvider": "com/wm/wearmail/data/crypto/AndroidKeystoreKeyProvider.kt",
    "com.wm.wearmail.data.crypto.CryptoManagerImpl": "com/wm/wearmail/data/crypto/CryptoManagerImpl.kt",
    "com.wm.wearmail.data.prefs.SettingsStoreImpl": "com/wm/wearmail/data/prefs/SettingsStoreImpl.kt",
    "com.wm.wearmail.data.db.MailDatabase": "com/wm/wearmail/data/db/MailDatabase.kt",
    "com.wm.wearmail.data.repo.AccountRepositoryImpl": "com/wm/wearmail/data/repo/AccountRepositoryImpl.kt",
    "com.wm.wearmail.data.repo.EmailRepositoryImpl": "com/wm/wearmail/data/repo/EmailRepositoryImpl.kt",
    "com.wm.wearmail.data.repo.DraftRepositoryImpl": "com/wm/wearmail/data/repo/DraftRepositoryImpl.kt",
    "com.wm.wearmail.data.repo.ContactRepositoryImpl": "com/wm/wearmail/data/repo/ContactRepositoryImpl.kt",
    "com.wm.wearmail.mail.ImapClientImpl": "com/wm/wearmail/mail/ImapClientImpl.kt",
    "com.wm.wearmail.mail.SmtpClientImpl": "com/wm/wearmail/mail/SmtpClientImpl.kt",
    "com.wm.wearmail.mail.ServerProbeImpl": "com/wm/wearmail/mail/ServerProbeImpl.kt",
    "com.wm.wearmail.notify.NotificationCenter": "com/wm/wearmail/notify/NotificationCenter.kt",
    "com.wm.wearmail.sync.SyncEngine": "com/wm/wearmail/sync/SyncEngine.kt",
    "com.wm.wearmail.pairing.PairingController": "com/wm/wearmail/pairing/PairingController.kt",
}

NAV_CONTRACT = {
    # 导航层调用的屏幕函数 -> 期望文件
    "com.wm.wearmail.ui.screen.inbox.InboxScreen": ("com/wm/wearmail/ui/screen/inbox/InboxScreen.kt", "InboxScreen"),
    "com.wm.wearmail.ui.screen.detail.EmailDetailScreen": ("com/wm/wearmail/ui/screen/detail/EmailDetailScreen.kt", "EmailDetailScreen"),
    "com.wm.wearmail.ui.screen.compose.ComposeMailScreen": ("com/wm/wearmail/ui/screen/compose/ComposeMailScreen.kt", "ComposeMailScreen"),
    "com.wm.wearmail.ui.screen.accounts.AccountListScreen": ("com/wm/wearmail/ui/screen/accounts/AccountListScreen.kt", "AccountListScreen"),
    "com.wm.wearmail.ui.screen.accounts.AddAccountScreen": ("com/wm/wearmail/ui/screen/accounts/AddAccountScreen.kt", "AddAccountScreen"),
    "com.wm.wearmail.ui.screen.settings.SettingsScreen": ("com/wm/wearmail/ui/screen/settings/SettingsScreen.kt", "SettingsScreen"),
}


def check_contracts(report: Report, root: Path) -> None:
    java_root = root / "app/src/main/java"
    problems: list[str] = []

    for fqcn, rel in CONTAINER_CONTRACT.items():
        simple = fqcn.rsplit(".", 1)[1]
        path = java_root / rel
        if not path.exists():
            problems.append(f"缺少实现文件 {rel}")
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if not re.search(rf"\bclass\s+{re.escape(simple)}\b", text):
            problems.append(f"{rel} 中未找到 class {simple}")

    for fqcn, (rel, func) in NAV_CONTRACT.items():
        path = java_root / rel
        if not path.exists():
            problems.append(f"缺少屏幕文件 {rel}")
            continue
        text = path.read_text(encoding="utf-8", errors="ignore")
        if not re.search(rf"\bfun\s+{re.escape(func)}\s*\(", text):
            problems.append(f"{rel} 中未找到 fun {func}(")

    report.check(
        "契约类/函数齐备",
        not problems,
        "; ".join(problems[:12]) if problems else f"{len(CONTAINER_CONTRACT) + len(NAV_CONTRACT)} 个契约点",
    )


# --------------------------------------------------------------------------
# 4. 安全与规范红线
# --------------------------------------------------------------------------
SECRET_WORDS = ("password", "passwd", "pwd", "token", "secret", "credential", "secrets")

# 敏感标识符：出现即代表"值可能流出"
SENSITIVE_ID_RE = re.compile(r"\b(password|passwd|pwd|token|secrets?|credentials?)\b", re.IGNORECASE)

# 只做存在性判断、并不输出值的安全表达式（例如 `secrets == null`、`token.isEmpty()`），
# 在检测前先剔除，避免把 `"凭据${if (secrets == null) "未改动" else "已更新"}"` 误判为泄漏。
NULLCHECK_RE = re.compile(
    r"\b\w+\s*(?:==|!=|===|!==)\s*null\b"
    r"|\b\w+\.(?:isEmpty|isNotEmpty|isBlank|isNotBlank)\s*\(\s*\)"
)

BRACED_INTERP_RE = re.compile(r"\$\{([^}]*)\}")
SIMPLE_INTERP_RE = re.compile(r"\$([A-Za-z_][A-Za-z0-9_]*)")
CONCAT_RE = re.compile(r"\+\s*([A-Za-z_][A-Za-z0-9_]*)")


def find_secret_interpolations(args: str) -> list[str]:
    """找出日志参数中"真正把敏感值写进输出"的片段。"""
    hits: list[str] = []
    for match in BRACED_INTERP_RE.finditer(args):
        expr = NULLCHECK_RE.sub("", match.group(1))
        if SENSITIVE_ID_RE.search(expr):
            hits.append(match.group(0))
    for match in SIMPLE_INTERP_RE.finditer(args):
        if SENSITIVE_ID_RE.fullmatch(match.group(1)):
            hits.append(match.group(0))
    for match in CONCAT_RE.finditer(args):
        if SENSITIVE_ID_RE.fullmatch(match.group(1)):
            hits.append(match.group(0))
    return hits


def strip_comments(text: str) -> str:
    """去掉注释但保留字符串字面量（用于"只检查真实代码"的场景）。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if ch == '"' and text[i : i + 3] == '"""':
            out.append(text[i : i + 3])
            i += 3
            while i + 2 < n and text[i : i + 3] != '"""':
                out.append(text[i])
                i += 1
            out.append(text[i : i + 3])
            i += 3
            continue
        if ch in ('"', "'"):
            quote = ch
            out.append(ch)
            i += 1
            while i < n:
                if text[i] == "\\":
                    out.append(text[i : i + 2])
                    i += 2
                    continue
                out.append(text[i])
                if text[i] == quote:
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_security(report: Report, root: Path) -> None:
    main_kt = sorted(root.glob("app/src/main/**/*.kt"))
    leaks: list[str] = []
    raw_log: list[str] = []
    base64_android: list[str] = []
    prints: list[str] = []

    log_call_re = re.compile(r"Logs\.(d|i|w|e)\s*\(([^;]*?)\)\s*$", re.S | re.M)

    for path in main_kt:
        raw_text = path.read_text(encoding="utf-8", errors="ignore")
        # 先剥离注释：注释里提到 android.util.Base64 / android.util.Log 属于说明性文字
        text = strip_comments(raw_text)
        rel = path.relative_to(root)
        is_log_facade = rel.name == "Logs.kt"

        # 4.1 敏感信息进日志（只查插值/拼接，不查文案措辞）
        for match in log_call_re.finditer(text):
            args = match.group(2)
            hits = find_secret_interpolations(args)
            if hits:
                leaks.append(f"{rel}: 日志插值了敏感字段 {hits}")

        # 4.2 绕过统一日志门面（允许 ConfigWebServer 这类"纯 JVM 可测"的类，
        #     它们刻意不依赖任何 android.* 以便在无设备环境运行单元测试）
        if (
            not is_log_facade
            and "pairing/ConfigWebServer.kt" not in str(rel).replace("\\", "/")
            and re.search(r"\bandroid\.util\.Log\b|\bLog\.[dwiev]\s*\(", text)
        ):
            raw_log.append(str(rel))

        # 4.3 明文打印
        if "println(" in text or "System.out.print" in text:
            prints.append(str(rel))

        # 4.4 android.util.Base64（会破坏纯 JVM 单元测试）
        if "android.util.Base64" in text:
            base64_android.append(str(rel))

    report.check("日志不泄漏敏感值", not leaks, "; ".join(sorted(set(leaks))[:8]))
    report.check("统一使用 Logs 门面", not raw_log, "; ".join(sorted(set(raw_log))[:8]))
    report.check("无 System.out 打印", not prints, "; ".join(sorted(set(prints))[:8]))
    report.check("加解密不依赖 android.util.Base64", not base64_android, "; ".join(sorted(set(base64_android))[:8]))


# --------------------------------------------------------------------------
# 5. Kotlin 粗粒度体检
# --------------------------------------------------------------------------
def strip_strings_and_comments(text: str) -> str:
    """去掉字符串字面量与注释，避免括号统计被内容干扰。"""
    out = []
    i = 0
    n = len(text)
    while i < n:
        ch = text[i]
        nxt = text[i + 1] if i + 1 < n else ""
        if ch == "/" and nxt == "/":
            while i < n and text[i] != "\n":
                i += 1
            continue
        if ch == "/" and nxt == "*":
            i += 2
            while i + 1 < n and not (text[i] == "*" and text[i + 1] == "/"):
                i += 1
            i += 2
            continue
        if ch == '"' and text[i : i + 3] == '"""':
            i += 3
            while i + 2 < n and text[i : i + 3] != '"""':
                i += 1
            i += 3
            continue
        if ch == '"':
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == '"':
                    i += 1
                    break
                i += 1
            continue
        if ch == "'":
            i += 1
            while i < n:
                if text[i] == "\\":
                    i += 2
                    continue
                if text[i] == "'":
                    i += 1
                    break
                i += 1
            continue
        out.append(ch)
        i += 1
    return "".join(out)


def check_kotlin_balance(report: Report, root: Path) -> None:
    problems: list[str] = []
    files = sorted(root.glob("app/src/**/*.kt"))
    for path in files:
        text = strip_strings_and_comments(path.read_text(encoding="utf-8", errors="ignore"))
        for open_ch, close_ch, label in (("{", "}", "花括号"), ("(", ")", "圆括号"), ("[", "]", "方括号")):
            if text.count(open_ch) != text.count(close_ch):
                problems.append(
                    f"{path.relative_to(root)}: {label}不配平 ({text.count(open_ch)} vs {text.count(close_ch)})"
                )
    report.check("Kotlin 括号配平", not problems, "; ".join(problems[:8]) if problems else f"{len(files)} 个文件")


def check_package_declared(report: Report, root: Path) -> None:
    problems: list[str] = []
    for path in sorted(root.glob("app/src/**/*.kt")):
        text = path.read_text(encoding="utf-8", errors="ignore")
        if not re.search(r"^package\s+com\.wm\.wearmail", text, re.M):
            problems.append(str(path.relative_to(root)))
    report.check("每个 Kotlin 文件声明包名", not problems, "; ".join(problems[:8]))


def check_circular_vertical_budget(report: Report, root: Path) -> None:
    """
    圆屏纵向预算：信息流干净可视带不得被固定条挤小，迷你按钮不得突破触控下限。

    真实反馈：用户两次提出「中间显示信息流的地方还是太小了」。根因都是同一条公式 ——
    干净带 = 233dp − 顶部固定占用 − 底部固定占用。这里把它固化成规则，
    避免以后新增固定条时又悄悄吃掉信息流（初版只有 9dp，连一行邮件都放不下）。

    同时校验迷你按钮的合规性：24dp × 密度 2.0 = 48px 恰好是需求要求的触控下限，
    任何进一步缩小都必须让这条规则失败。
    """
    problems: list[str] = []

    def dp_value(text: str, name: str) -> float | None:
        found = re.search(rf"private val {name} = (\d+(?:\.\d+)?)\.dp", text)
        return float(found.group(1)) if found else None

    inbox = root / "app/src/main/java/com/wm/wearmail/ui/screen/inbox/InboxScreen.kt"
    if not inbox.exists():
        report.check("圆屏纵向预算（信息流不被固定条挤掉）", False, "缺少 InboxScreen.kt")
        return

    inbox_text = strip_comments(inbox.read_text(encoding="utf-8", errors="ignore"))
    top = dp_value(inbox_text, "TOP_SCRIM_HEIGHT")
    bottom = dp_value(inbox_text, "BOTTOM_SCRIM_HEIGHT")
    if top is None or bottom is None:
        problems.append("未找到 TOP_SCRIM_HEIGHT / BOTTOM_SCRIM_HEIGHT")
    else:
        band = SCREEN_HEIGHT_DP - top - bottom
        if band < MIN_CLEAN_BAND_DP:
            problems.append(
                f"信息流干净可视带仅 {band:.0f}dp（要求 ≥{MIN_CLEAN_BAND_DP:.0f}dp；"
                f"顶部 {top:g}dp + 底部 {bottom:g}dp）"
            )

    components = root / "app/src/main/java/com/wm/wearmail/ui/kit/Components.kt"
    if components.exists():
        mini = dp_value(
            strip_comments(components.read_text(encoding="utf-8", errors="ignore")),
            "MINI_BUTTON_HEIGHT",
        )
        if mini is None:
            problems.append("未找到 MINI_BUTTON_HEIGHT")
        elif mini * DENSITY < MIN_TOUCH_TARGET_PX:
            problems.append(
                f"迷你按钮 {mini:g}dp × 密度 {DENSITY:g} = {mini * DENSITY:.0f}px，"
                f"低于 {MIN_TOUCH_TARGET_PX:.0f}px 触控下限"
            )

    report.check("圆屏纵向预算（信息流不被固定条挤掉）", not problems, "; ".join(problems))


def check_keystore_crypto(report: Report, root: Path) -> None:
    """
    AES-GCM 必须兼容 Android Keystore 的「随机化加密」要求。

    真实缺陷：Android Keystore 生成的密钥默认 `setRandomizedEncryptionRequired(true)`，
    此时**不允许调用方自带 IV** —— `Cipher.init(ENCRYPT_MODE, key, GCMParameterSpec(...))`
    会抛 `InvalidAlgorithmParameterException`，真机上表现为「保存账户失败」。
    正确写法：加密不传 IV，改从 `cipher.iv` 读回 Keystore 生成的 IV；
    解密方向必须由调用方提供 IV（该限制只作用于加密）。

    注意：这条错误**纯 JVM 单测覆盖不到**（单测用的是内存密钥，普通 JCE 允许自带 IV），
    因此用静态规则守住。
    """
    problems: list[str] = []
    path = root / "app/src/main/java/com/wm/wearmail/data/crypto/CryptoManagerImpl.kt"
    if not path.exists():
        report.check("AES-GCM 与 Android Keystore 兼容", False, "缺少 CryptoManagerImpl.kt")
        return

    text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))

    encrypt = re.search(r"override fun encrypt\(.*?\n    \}", text, re.S)
    if not encrypt:
        problems.append("未找到 CryptoManagerImpl.encrypt")
    else:
        body = encrypt.group(0)
        if "GCMParameterSpec" in body:
            problems.append(
                "encrypt 向 Cipher.init 传入了调用方 IV（Keystore 会抛 InvalidAlgorithmParameterException）"
            )
        if "cipher.iv" not in body:
            problems.append("encrypt 未从 cipher.iv 读回 Keystore 生成的 IV")

    # 解密方向必须传入 IV：具体实现通常在私有辅助函数里（decryptParts / decryptInternal），
    # 因此先找辅助函数，找不到再退回 public decrypt 本身。
    decrypt_helper = re.search(r"private fun decrypt\w*\(.*?\n    \}", text, re.S)
    if decrypt_helper:
        decrypt_body = decrypt_helper.group(0)
    else:
        decrypt = re.search(r"override fun decrypt\(.*?\n    \}", text, re.S)
        decrypt_body = decrypt.group(0) if decrypt else ""
        if not decrypt_body:
            problems.append("未找到 CryptoManagerImpl.decrypt")

    if decrypt_body and "GCMParameterSpec" not in decrypt_body:
        problems.append("解密路径未传入 IV（解密必须由调用方提供 IV）")

    report.check("AES-GCM 与 Android Keystore 兼容", not problems, "; ".join(problems))


def check_overlay_background(report: Report, root: Path) -> None:
    """
    覆盖层必须「不透明背景 + 拦截指针事件」。

    这条规则来自一个真实缺陷：详情/撰写/添加账户是画在分页**之上**的覆盖层，
    早期版本的根节点（CircularScreen）没有绘制背景，空白处会直接透出后面的收件箱；
    同理，空白处的点击/滑动会穿透到分页，导致误切换页面或误开邮件。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    components = java_root / "ui/kit/Components.kt"
    if components.exists():
        text = strip_comments(components.read_text(encoding="utf-8", errors="ignore"))
        match = re.search(r"fun CircularScreen\(.*?\n\}", text, re.S)
        body = match.group(0) if match else ""
        if not body:
            problems.append("未找到 CircularScreen 定义")
        elif ".background(" not in body:
            problems.append("CircularScreen 根节点未绘制不透明背景（覆盖层会透出下层页面）")

    nav = java_root / "ui/nav/WearMailNav.kt"
    if nav.exists():
        text = strip_comments(nav.read_text(encoding="utf-8", errors="ignore"))
        if "pointerInput(" not in text:
            problems.append("导航层缺少覆盖层指针事件拦截（空白处会误触下层分页）")
        if ".background(" not in text:
            problems.append("导航层缺少覆盖层不透明遮罩")

    # 约定：所有 *Screen.kt 都必须通过 CircularScreen 布局，不允许自建透明根节点
    screen_dir = java_root / "ui/screen"
    if screen_dir.exists():
        for path in sorted(screen_dir.rglob("*Screen.kt")):
            if "CircularScreen" not in path.read_text(encoding="utf-8", errors="ignore"):
                problems.append(f"{path.name} 未使用 CircularScreen（存在透明根节点风险）")

    report.check("覆盖层不透明且拦截指针", not problems, "; ".join(problems[:6]))


def main() -> int:
    parser = argparse.ArgumentParser(description="WearMail 静态检查")
    parser.add_argument("--root", default=str(Path(__file__).resolve().parent.parent))
    args = parser.parse_args()

    root = Path(args.root).resolve()
    report = Report()

    check_xml(report, root)
    check_resources(report, root)
    check_contracts(report, root)
    check_security(report, root)
    check_keystore_crypto(report, root)
    check_circular_vertical_budget(report, root)
    check_overlay_background(report, root)
    check_kotlin_balance(report, root)
    check_package_declared(report, root)

    report.print()
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
