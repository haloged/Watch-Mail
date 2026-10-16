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


def check_secret_write_semantics(report: Report, root: Path) -> None:
    """
    账户密文列（password_enc / oauth_token_enc）必须用 SecretWrite 的显式意图写入。

    真实缺陷：`AccountDao.update` 把「不改动凭据」与「清空凭据」都用 null 表达，
    而 `ContentValues` 里的 null 就是写成 SQL NULL —— 重命名账户（只改别名）会把两列
    密文一起清空，用户表现为「改完名字就收不到信，提示重新配置账户」。
    DAO 依赖 `android.database.*`、纯 JVM 单测覆盖不到，因此用静态规则守住：
    - SecretWrite 类型（Keep / Set）存在，且仓储层用 `SecretWrite.of` 推导意图；
    - `update` 签名必须接收 SecretWrite（不做可空 String 的隐含约定）；
    - `update` 函数体只允许通过 `putSecret` 写密文列，**不得**直接 `putNullable`/`putNull`；
    - 令牌刷新走 `updateOAuthTokens` 专用路径（而不是整行覆写）。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    secret_write = java_root / "data/db/SecretWrite.kt"
    if not secret_write.exists():
        problems.append("缺少 SecretWrite.kt（密文列写入意图类型）")
    else:
        text = strip_comments(secret_write.read_text(encoding="utf-8", errors="ignore"))
        for token in ("Keep", "Set", "fun of("):
            if token not in text:
                problems.append(f"SecretWrite 缺少 {token}")

    dao = java_root / "data/db/AccountDao.kt"
    if not dao.exists():
        problems.append("缺少 AccountDao.kt")
    else:
        text = strip_comments(dao.read_text(encoding="utf-8", errors="ignore"))
        match = re.search(r"fun update\(account: Account.*?\n    \}", text, re.S)
        if not match:
            problems.append("未找到 AccountDao.update(account, password: SecretWrite, token: SecretWrite)")
        else:
            body = match.group(0)
            if "SecretWrite" not in body:
                problems.append("update 未使用 SecretWrite（null 又会退化为隐式清空）")
            if "putNullable(" in body or "putNull(" in body:
                problems.append("update 直接对密文列 putNullable/putNull（会把 null 写成 SQL NULL，静默清空凭据）")
            if "putSecret(" not in body:
                problems.append("update 未通过 putSecret 写入密文列")

    impl = java_root / "data/repo/AccountRepositoryImpl.kt"
    if not impl.exists():
        problems.append("缺少 AccountRepositoryImpl.kt")
    else:
        text = strip_comments(impl.read_text(encoding="utf-8", errors="ignore"))
        if "SecretWrite.of(" not in text:
            problems.append("AccountRepositoryImpl 未用 SecretWrite.of 推导写入意图")

    repo = java_root / "data/repo/AccountRepository.kt"
    if not repo.exists():
        problems.append("缺少 AccountRepository.kt")
    else:
        text = strip_comments(repo.read_text(encoding="utf-8", errors="ignore"))
        if "updateOAuthTokens" not in text:
            problems.append("AccountRepository 缺少 updateOAuthTokens（令牌刷新会整行覆写密码列）")

    report.check("账户密文列写入语义（SecretWrite）", not problems, "; ".join(problems[:8]))


def check_html_cache_tristate(report: Report, root: Path) -> None:
    """
    `email_bodies.body_html` 的三态语义（UNKNOWN / NONE / AVAILABLE）不得被压回两态。

    真实缺陷：NULL 兼当「没问过服务器」与「问过但没有」，导致纯文本邮件被反复重下、
    离线打不开。修复后由 HtmlCacheState 统一承载三态，本规则守住关键锚点：
    - HtmlCacheState 三态 + toColumnValue / fromColumnValue 存在；
    - BodyDao 写列走 `HtmlCacheState.toColumnValue`，并提供 `queryHtmlState`；
    - 仓储层提供 `cachedHtmlState`；SyncEngine 与 EmailDetailViewModel 用它判断完整性。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    state = java_root / "data/db/HtmlCacheState.kt"
    if not state.exists():
        problems.append("缺少 HtmlCacheState.kt（三态定义）")
    else:
        text = strip_comments(state.read_text(encoding="utf-8", errors="ignore"))
        for token in ("UNKNOWN", "NONE", "AVAILABLE", "fun toColumnValue(", "fun fromColumnValue("):
            if token not in text:
                problems.append(f"HtmlCacheState 缺少 {token}")

    dao = java_root / "data/db/BodyDao.kt"
    if not dao.exists():
        problems.append("缺少 BodyDao.kt")
    else:
        text = strip_comments(dao.read_text(encoding="utf-8", errors="ignore"))
        if "HtmlCacheState.toColumnValue(" not in text:
            problems.append("BodyDao 写 body_html 未走 HtmlCacheState.toColumnValue（三态会退化为两态）")
        if "queryHtmlState" not in text:
            problems.append("BodyDao 缺少 queryHtmlState（无法判断是否需要为 HTML 联网）")

    repo = java_root / "data/repo/EmailRepository.kt"
    if not repo.exists():
        problems.append("缺少 EmailRepository.kt")
    else:
        text = strip_comments(repo.read_text(encoding="utf-8", errors="ignore"))
        if "cachedHtmlState" not in text:
            problems.append("EmailRepository 缺少 cachedHtmlState")

    for rel, label in (
        ("sync/SyncEngine.kt", "SyncEngine"),
        ("ui/screen/detail/EmailDetailViewModel.kt", "EmailDetailViewModel"),
    ):
        path = java_root / rel
        if not path.exists():
            problems.append(f"缺少 {rel}")
            continue
        text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))
        if "cachedHtmlState" not in text:
            problems.append(f"{label} 未用 cachedHtmlState 判断缓存完整性（纯文本邮件会被反复重下）")

    report.check("正文 HTML 缓存三态语义", not problems, "; ".join(problems[:8]))


def check_circular_item_offset_coordinate(report: Report, root: Path) -> None:
    """
    列表项坐标换算（内容坐标 → 屏幕坐标）。

    真实缺陷（实机反馈）：控件滚到表盘中心时没有放大到最宽，最宽位置偏下。
    根因是 `LazyListItemInfo.offset` 的原点在**内容区起点**（顶部 contentPadding 内侧），
    而圆形几何模型以屏幕左上角为原点 —— 直接把 offset 当屏幕 Y 算弦长，
    等于把「最宽处」误判在屏幕中心偏下 beforeContentPadding 的位置。
    """
    problems: list[str] = []
    path = root / "app/src/main/java/com/wm/wearmail/ui/kit/CircularSafeLazyColumn.kt"
    if not path.exists():
        report.check("列表项坐标换算（内容坐标→屏幕坐标）", False, "缺少 CircularSafeLazyColumn.kt")
        return
    text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))
    if "horizontalPaddingForItem(" not in text:
        problems.append("CircularItem 未通过 horizontalPaddingForItem 换算坐标系")
    if "offset.toFloat()" in text:
        problems.append("发现把 offset 直接当作屏幕坐标的旧写法（offset.toFloat()）")
    report.check("列表项坐标换算（内容坐标→屏幕坐标）", not problems, "; ".join(problems))


def check_outlook_oauth_only(report: Report, root: Path) -> None:
    """
    Outlook 预设下「添加账户」页的登录方式仅允许 OAuth2（微软已停用基本认证）。

    策略集中在 `ProviderPresets.authTypesFor`，并由 `selectPreset` 自动锁定；
    若 UI 重新直接枚举 `AuthType.entries`，用户会选到注定失败的密码方式。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    screen = java_root / "ui/screen/accounts/AddAccountScreen.kt"
    if not screen.exists():
        report.check("Outlook 认证方式仅 OAuth2", False, "缺少 AddAccountScreen.kt")
        return
    text = strip_comments(screen.read_text(encoding="utf-8", errors="ignore"))
    if "ProviderPresets.authTypesFor" not in text:
        problems.append("AddAccountScreen 未按 authTypesFor 收窄登录方式选项")
    if "AuthType.entries" in text:
        problems.append("AddAccountScreen 仍直接枚举 AuthType.entries（Outlook 会露出密码选项）")

    vm = java_root / "ui/screen/accounts/AddAccountViewModel.kt"
    if vm.exists():
        text = strip_comments(vm.read_text(encoding="utf-8", errors="ignore"))
        if "ProviderPresets.authTypesFor" not in text:
            problems.append("selectPreset 未按 authTypesFor 锁定登录方式（Outlook 预设可被绕过）")
    else:
        problems.append("缺少 AddAccountViewModel.kt")

    report.check("Outlook 认证方式仅 OAuth2", not problems, "; ".join(problems))


def check_r8_release_optimization(report: Report, root: Path) -> None:
    """
    release 构建必须启用 R8 优化（收缩 + 混淆 + 优化）。

    任何一处被改都会让 R8 优化静默关闭：
    - 默认规则必须使用 `proguard-android-optimize.txt`（而不是不含优化的 `proguard-android.txt`）；
    - `isMinifyEnabled` / `isShrinkResources` 必须为 true；
    - 规则文件中不得出现 `-dontoptimize` / `-dontobfuscate`。
    """
    problems: list[str] = []
    gradle = root / "app/build.gradle.kts"
    if not gradle.exists():
        report.check("release 启用 R8 优化", False, "缺少 app/build.gradle.kts")
        return
    text = strip_comments(gradle.read_text(encoding="utf-8", errors="ignore"))
    if "proguard-android-optimize.txt" not in text:
        problems.append("默认规则不是 proguard-android-optimize.txt（R8 优化会被关闭）")
    if "isMinifyEnabled = true" not in text:
        problems.append("release 未启用 isMinifyEnabled = true（R8 不会运行）")
    if "isShrinkResources = true" not in text:
        problems.append("release 未启用 isShrinkResources = true（资源不会压缩）")

    rules = root / "app/proguard-rules.pro"
    if rules.exists():
        # 注意：proguard 用 `#` 注释（不是 Kotlin 的 // 或 /* */），strip_comments
        # 不处理它 —— 必须逐行跳过注释行，只看真正的指令行（行首的 -dontoptimize）。
        body = rules.read_text(encoding="utf-8", errors="ignore")
        for line in body.splitlines():
            stripped = line.strip()
            if stripped.startswith("#"):
                continue
            if "-dontoptimize" in stripped or "-dontobfuscate" in stripped:
                problems.append("proguard 规则包含 -dontoptimize / -dontobfuscate（优化/混淆被关闭）")
                break

    report.check("release 启用 R8 优化", not problems, "; ".join(problems[:6]))


def check_update_checker_hygiene(report: Report, root: Path) -> None:
    """
    检查更新链路的防回归约束。

    - GitHub API **要求 User-Agent**：缺失会返回 403，真机上只表现为「检查失败」；
    - 版本比较必须走 `AppVersion.isNewer`（字符串比较会把 1.10.0 判小于 1.9.0）；
    - `UpdateCenter` 必须注册在 AppContainer，启动检查必须由 UI 层（WearMailNav）触发
      并接入弹窗 —— Application 冷启动路径不允许发起网络请求（见 WearMailApp 注释）。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    checker = java_root / "update/GitHubUpdateChecker.kt"
    if not checker.exists():
        problems.append("缺少 update/GitHubUpdateChecker.kt")
    else:
        text = strip_comments(checker.read_text(encoding="utf-8", errors="ignore"))
        if "User-Agent" not in text:
            problems.append("GitHubUpdateChecker 未设置 User-Agent（GitHub API 会返回 403）")

    center = java_root / "update/UpdateCenter.kt"
    if not center.exists():
        problems.append("缺少 update/UpdateCenter.kt")
    else:
        text = strip_comments(center.read_text(encoding="utf-8", errors="ignore"))
        if "AppVersion.isNewer(" not in text:
            problems.append("UpdateCenter 未用 AppVersion.isNewer 做版本比较")

    container = java_root / "core/AppContainer.kt"
    if not container.exists():
        problems.append("缺少 AppContainer.kt")
    else:
        text = strip_comments(container.read_text(encoding="utf-8", errors="ignore"))
        if "UpdateCenter(" not in text:
            problems.append("AppContainer 未注册 UpdateCenter")

    nav = java_root / "ui/nav/WearMailNav.kt"
    if not nav.exists():
        problems.append("缺少 WearMailNav.kt")
    else:
        text = strip_comments(nav.read_text(encoding="utf-8", errors="ignore"))
        if "checkOnStartup" not in text:
            problems.append("WearMailNav 未触发启动检查（checkOnStartup）")
        if "UpdateAvailableDialog" not in text:
            problems.append("WearMailNav 未接入「发现新版本」弹窗")

    app = java_root / "core/WearMailApp.kt"
    if app.exists():
        text = strip_comments(app.read_text(encoding="utf-8", errors="ignore"))
        if "container.updates" in text or "UpdateCenter" in text:
            problems.append("WearMailApp 冷启动路径不应发起检查更新（网络请求须延后到首帧后）")

    settings = java_root / "ui/screen/settings/SettingsScreen.kt"
    if not settings.exists():
        problems.append("缺少 SettingsScreen.kt")
    else:
        text = strip_comments(settings.read_text(encoding="utf-8", errors="ignore"))
        if "checkForUpdates" not in text:
            problems.append("设置页缺少「检查更新」入口")

    report.check("检查更新链路约束", not problems, "; ".join(problems[:8]))


def check_oauth2_config_page(report: Report, root: Path) -> None:
    """
    OAuth2 客户端 ID 配置必须位于独立页面（OAuth2ConfigScreen），设置页只保留入口。

    背景：客户端 ID / 租户是低频一次性配置，留在设置主列表会让日常设置
    每次都要滚动经过两块输入框。本规则防止把输入框重新挪回设置主页，
    或把独立页的入口/导航拆掉（拆掉后用户将无法再配置 OAuth2）。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    screen = java_root / "ui/screen/settings/OAuth2ConfigScreen.kt"
    if not screen.exists():
        problems.append("缺少 OAuth2ConfigScreen.kt（OAuth2 配置独立页）")
    else:
        text = strip_comments(screen.read_text(encoding="utf-8", errors="ignore"))
        if "setClientId" not in text:
            problems.append("OAuth2ConfigScreen 未包含客户端 ID 写入逻辑")

    settings = java_root / "ui/screen/settings/SettingsScreen.kt"
    if not settings.exists():
        problems.append("缺少 SettingsScreen.kt")
    else:
        text = strip_comments(settings.read_text(encoding="utf-8", errors="ignore"))
        if "WearTextField" in text and "microsoftOAuthClientId" in text:
            problems.append("设置页出现 OAuth2 输入框（应只保留入口按钮，输入框在 OAuth2ConfigScreen）")
        if "onOpenOAuth2Config" not in text:
            problems.append("设置页缺少 OAuth2 配置入口按钮")

    nav = java_root / "ui/nav/WearMailNav.kt"
    if nav.exists():
        text = strip_comments(nav.read_text(encoding="utf-8", errors="ignore"))
        if "OAuth2ConfigScreen" not in text:
            problems.append("导航层未接入 OAuth2ConfigScreen（入口无法打开）")
    else:
        problems.append("缺少 WearMailNav.kt")

    report.check("OAuth2 配置独立页面", not problems, "; ".join(problems[:6]))


def check_microsoft_pairing_blocked(report: Report, root: Path) -> None:
    """
    微软邮箱必须被「手机扫码配置」拒绝（微软已停用 IMAP/SMTP 基本认证）。

    扫码页只能粘贴密码/授权码，配微软邮箱注定失败；正确路径是手表端 OAuth2
    设备码授权。防回归的三个落点：
    - `ProviderPresets.isMicrosoftMailbox`（判定函数，单一数据源）；
    - `PairingController.handleSubmit` 服务端拦截（前端可被绕过，服务端是底线）；
    - `PairingPage` 前端拦截提示（提交前就拦住，并给出替代路径）。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    presets = java_root / "model/ProviderPresets.kt"
    if not presets.exists():
        problems.append("缺少 ProviderPresets.kt")
    else:
        text = strip_comments(presets.read_text(encoding="utf-8", errors="ignore"))
        if "fun isMicrosoftMailbox(" not in text:
            problems.append("ProviderPresets 缺少 isMicrosoftMailbox（微软个人邮箱判定）")

    controller = java_root / "pairing/PairingController.kt"
    if not controller.exists():
        problems.append("缺少 PairingController.kt")
    else:
        text = strip_comments(controller.read_text(encoding="utf-8", errors="ignore"))
        if "isMicrosoftMailbox(" not in text:
            problems.append("PairingController.handleSubmit 未拦截微软邮箱（扫码会保存注定失败的账户）")

    page = java_root / "pairing/PairingPage.kt"
    if not page.exists():
        problems.append("缺少 PairingPage.kt")
    else:
        text = strip_comments(page.read_text(encoding="utf-8", errors="ignore"))
        if "isMicrosoftEmail" not in text:
            problems.append("PairingPage 缺少前端微软邮箱拦截提示")

    report.check("微软邮箱禁止扫码配置", not problems, "; ".join(problems[:6]))


def check_pairing_wifi_precheck(report: Report, root: Path) -> None:
    """
    「手机扫码配置」打开前必须检查 Wi-Fi（手机与手表需同一 Wi-Fi 才能扫码）。

    防回归：早期实现只在服务启动失败后才提示「未连接 Wi-Fi」，用户会先看到一个
    无法出码的空对话框。现在要求在 AddAccountViewModel.startPairing 入口处
    先用 PairingService.isWifiConnected 检查并直接提示；判定必须用
    ConnectivityManager 的 TRANSPORT_WIFI（蜂窝网络下也可能有 IP，不能用它近似）。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    service = java_root / "pairing/PairingService.kt"
    if not service.exists():
        problems.append("缺少 PairingService.kt")
    else:
        text = strip_comments(service.read_text(encoding="utf-8", errors="ignore"))
        if "isWifiConnected" not in text:
            problems.append("PairingService 缺少 isWifiConnected（Wi-Fi 前置检查契约）")

    controller = java_root / "pairing/PairingController.kt"
    if not controller.exists():
        problems.append("缺少 PairingController.kt")
    else:
        text = strip_comments(controller.read_text(encoding="utf-8", errors="ignore"))
        if "TRANSPORT_WIFI" not in text:
            problems.append("PairingController 未用 NetworkCapabilities.TRANSPORT_WIFI 判定 Wi-Fi")

    vm = java_root / "ui/screen/accounts/AddAccountViewModel.kt"
    if not vm.exists():
        problems.append("缺少 AddAccountViewModel.kt")
    else:
        text = strip_comments(vm.read_text(encoding="utf-8", errors="ignore"))
        if "isWifiConnected" not in text:
            problems.append("AddAccountViewModel.startPairing 未做 Wi-Fi 前置检查（会先弹出空对话框）")

    report.check("扫码配置 Wi-Fi 前置检查", not problems, "; ".join(problems[:6]))


def check_toast_feedback(report: Report, root: Path) -> None:
    """
    交互反馈必须以浮层（ToastHost）呈现，不得渲染在列表内容里。

    真实缺陷（真机反馈 ×2，同一模式）：
    - 添加账户页「用手机扫码配置」入口在列表**顶部**，提示文本却渲染在列表**底部**
      —— 无 Wi-Fi 时用户点击后"没有任何提示"；
    - 设置页「检查更新」按钮在列表**底部**，提示渲染在**标题下方**——同样看不到。

    共同解法是把反馈放进浮层（`ui/kit/ToastHost`），与滚动位置无关。
    本规则防止把提示重新挪回列表内（或删掉浮层组件）导致旧问题回归。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    toast = java_root / "ui/kit/Toast.kt"
    if not toast.exists():
        problems.append("缺少 ui/kit/Toast.kt（浮层提示组件）")
    else:
        text = strip_comments(toast.read_text(encoding="utf-8", errors="ignore"))
        if "fun ToastHost(" not in text:
            problems.append("Toast.kt 缺少 ToastHost 组件")

    for rel, label in (
        ("ui/screen/accounts/AddAccountScreen.kt", "添加账户页"),
        ("ui/screen/settings/SettingsScreen.kt", "设置页"),
    ):
        path = java_root / rel
        if not path.exists():
            problems.append(f"缺少 {rel}")
            continue
        text = strip_comments(path.read_text(encoding="utf-8", errors="ignore"))
        if "ToastHost(" not in text:
            problems.append(f"{label}未使用 ToastHost（交互反馈会跟随滚动位置而不可见）")

    report.check("交互反馈使用浮层提示", not problems, "; ".join(problems[:6]))


def check_links_open_toggle(report: Report, root: Path) -> None:
    """
    邮件内链接必须由「打开邮件内链接」开关控制（默认关闭、仅 http/https、外部浏览器）。

    背景：WebView 渲染的邮件正文里链接一律不可自导航 —— 点击行为由设置页开关决定：
    - 关闭（默认）：链接不可点击（手表上跳出应用是重操作，且链接指向站外内容）；
    - 开启：http/https 交给系统默认浏览器；没有可用浏览器时详情页浮层提示，
      而不是静默失败。
    防回归落点：设置项（openLinksInBrowser）、设置页入口、详情页 WebView 的
    shouldOverrideUrlLoading 消费该开关并通过 ACTION_VIEW 外跳。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    store = java_root / "data/prefs/SettingsStore.kt"
    if not store.exists():
        problems.append("缺少 SettingsStore.kt")
    else:
        text = strip_comments(store.read_text(encoding="utf-8", errors="ignore"))
        if "openLinksInBrowser" not in text:
            problems.append("AppSettings 缺少 openLinksInBrowser（链接外部打开开关）")

    impl = java_root / "data/prefs/SettingsStoreImpl.kt"
    if not impl.exists():
        problems.append("缺少 SettingsStoreImpl.kt")
    else:
        text = strip_comments(impl.read_text(encoding="utf-8", errors="ignore"))
        if "openLinksInBrowser" not in text:
            problems.append("SettingsStoreImpl 未持久化 openLinksInBrowser")

    settings = java_root / "ui/screen/settings/SettingsScreen.kt"
    if not settings.exists():
        problems.append("缺少 SettingsScreen.kt")
    else:
        text = strip_comments(settings.read_text(encoding="utf-8", errors="ignore"))
        if "setOpenLinksInBrowser" not in text:
            problems.append("设置页缺少「打开邮件内链接」开关入口")

    detail = java_root / "ui/screen/detail/EmailDetailScreen.kt"
    if not detail.exists():
        problems.append("缺少 EmailDetailScreen.kt")
    else:
        text = strip_comments(detail.read_text(encoding="utf-8", errors="ignore"))
        if "openLinksInBrowser" not in text:
            problems.append("详情页未消费 openLinksInBrowser（开关会失效）")
        if "shouldOverrideUrlLoading" not in text:
            problems.append("详情页缺少 WebView 链接拦截回调（内容会脱离沙箱导航）")
        if "ACTION_VIEW" not in text:
            problems.append("详情页未通过 ACTION_VIEW 交给外部浏览器")

    report.check("邮件链接开关与外部打开", not problems, "; ".join(problems[:6]))


def check_inapp_update_download(report: Report, root: Path) -> None:
    """
    应用内更新链路：解析 APK 资产 → 应用内下载（慢/失败切 gh-proxy 加速）→ 系统安装器。

    需求背景：发现新版本后**不再跳 Release 网页**，弹窗内直接下载安装包；
    直连过慢或失败时自动切换 `https://gh-proxy.com/` 加速链接。防回归锚点：
    - UpdateJson 解析 assets 的 `browser_download_url`（否则永远拿不到安装包）；
    - ApkDownloader 存在、含 gh-proxy 加速前缀与速度判定（isTooSlow）；
    - 更新弹窗消费下载器，并由 ApkInstaller 拉起系统安装器；
    - Manifest 声明 REQUEST_INSTALL_PACKAGES + FileProvider，file_paths 白名单存在。
    """
    problems: list[str] = []
    java_root = root / "app/src/main/java/com/wm/wearmail"

    info = java_root / "update/UpdateInfo.kt"
    if not info.exists():
        problems.append("缺少 update/UpdateInfo.kt")
    else:
        text = strip_comments(info.read_text(encoding="utf-8", errors="ignore"))
        if "apkUrl" not in text or "browser_download_url" not in text:
            problems.append("UpdateJson 未解析 APK 资产（apkUrl / browser_download_url）")

    downloader = java_root / "update/ApkDownloader.kt"
    if not downloader.exists():
        problems.append("缺少 update/ApkDownloader.kt（应用内下载器）")
    else:
        text = strip_comments(downloader.read_text(encoding="utf-8", errors="ignore"))
        if "gh-proxy.com" not in text:
            problems.append("下载器缺少 gh-proxy.com 加速前缀（过慢/失败时的回退消失）")
        if "isTooSlow" not in text:
            problems.append("下载器缺少速度过慢判定（慢速直连不会切换加速）")

    installer = java_root / "update/ApkInstaller.kt"
    if not installer.exists():
        problems.append("缺少 update/ApkInstaller.kt（系统安装器拉起）")

    dialog = java_root / "ui/kit/UpdateDialog.kt"
    if not dialog.exists():
        problems.append("缺少 ui/kit/UpdateDialog.kt")
    else:
        text = strip_comments(dialog.read_text(encoding="utf-8", errors="ignore"))
        if "ApkInstaller" not in text:
            problems.append("更新弹窗未调用 ApkInstaller（下载后无法安装）")
        if "downloader" not in text:
            problems.append("更新弹窗未接入应用内下载器")

    manifest = root / "app/src/main/AndroidManifest.xml"
    if not manifest.exists():
        problems.append("缺少 AndroidManifest.xml")
    else:
        text = manifest.read_text(encoding="utf-8", errors="ignore")
        if "REQUEST_INSTALL_PACKAGES" not in text:
            problems.append("Manifest 缺少 REQUEST_INSTALL_PACKAGES（Android 8+ 无法安装 APK）")
        if "FileProvider" not in text:
            problems.append("Manifest 缺少 FileProvider（安装器不接受 file:// 路径）")

    paths = root / "app/src/main/res/xml/file_paths.xml"
    if not paths.exists():
        problems.append("缺少 res/xml/file_paths.xml（FileProvider 路径白名单）")

    report.check("应用内更新下载链路", not problems, "; ".join(problems[:6]))


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
    check_secret_write_semantics(report, root)
    check_html_cache_tristate(report, root)
    check_circular_item_offset_coordinate(report, root)
    check_outlook_oauth_only(report, root)
    check_r8_release_optimization(report, root)
    check_update_checker_hygiene(report, root)
    check_oauth2_config_page(report, root)
    check_microsoft_pairing_blocked(report, root)
    check_pairing_wifi_precheck(report, root)
    check_toast_feedback(report, root)
    check_links_open_toggle(report, root)
    check_inapp_update_download(report, root)
    check_kotlin_balance(report, root)
    check_package_declared(report, root)

    report.print()
    return 1 if report.failures else 0


if __name__ == "__main__":
    sys.exit(main())
