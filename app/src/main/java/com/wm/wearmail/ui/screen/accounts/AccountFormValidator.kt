package com.wm.wearmail.ui.screen.accounts

/**
 * 「添加账户」表单校验（纯函数，无 Android / Compose 依赖）。
 *
 * 设计目标：
 * 1. **全部返回中文提示**，可直接显示在 466x466 的圆形表盘上（短句、无术语）；
 * 2. **纯函数 + object**，不持有任何状态，便于 JVM 单元测试覆盖边界值；
 * 3. 校验逻辑与 UI 解耦，ViewModel 与屏幕只负责「把错误显示出来」。
 *
 * 关于国际化的取舍（遗留假设）：
 * 本实现只接受 **ASCII 邮箱地址**。中文/非 ASCII 域名（如 `用户@中文.com`）会被
 * 判为不合法并提示用户改用英文地址或自行转换为 punycode（`xn--...`）。
 * 原因是 JavaMail 与多数 IMAP 服务器对未转换的 IDN 支持不一致，
 * 手表端也没有输入法辅助转换，明确拒绝比「保存后连不上」体验更好。
 */
object AccountFormValidator {

    /** 邮箱地址总长度上限（RFC 5321 规定路径不超过 256，这里留出余量取 254） */
    private const val MAX_EMAIL_LENGTH: Int = 254

    /** 端口合法区间（TCP 端口 1..65535，0 保留） */
    private val PORT_RANGE: IntRange = 1..65535

    /** 用户名（@ 前）：允许的 RFC 5322 atom 字符，点号不能出现在首尾或连续出现 */
    private val LOCAL_PART_REGEX =
        Regex("^[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*$")

    /** 域名标签：字母/数字开头结尾，中间可含连字符 */
    private val DOMAIN_LABEL_REGEX = Regex("^[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?$")

    /** 端口必须是纯 ASCII 数字（显式写出 0-9，避免 Character.isDigit 接受阿拉伯-印度数字） */
    private val PORT_REGEX = Regex("^[0-9]+$")

    // ------------------------------------------------------------------
    // 邮箱
    // ------------------------------------------------------------------

    /** 邮箱地址是否合法 */
    fun isValidEmail(email: String): Boolean = validateEmail(email) == null

    /**
     * 校验邮箱地址。
     *
     * @return 中文错误信息；null 表示通过
     */
    fun validateEmail(email: String): String? {
        if (email.isBlank()) return "请输入邮箱地址"
        // 空格是高发错误：扫码/粘贴常带上首尾空格，但地址中间的空格一定是错的
        if (email.any { it.isWhitespace() }) return "邮箱地址不能包含空格"
        if (email.any { it.code > 127 }) return "请使用英文邮箱地址（暂不支持中文或特殊字符）"
        if (email.length > MAX_EMAIL_LENGTH) return "邮箱地址过长（超过 $MAX_EMAIL_LENGTH 个字符）"

        val atCount = email.count { it == '@' }
        if (atCount == 0) return "邮箱地址缺少 @ 符号"
        if (atCount > 1) return "邮箱地址只能包含一个 @ 符号"

        val local = email.substringBefore('@')
        val domain = email.substringAfter('@')

        if (local.isEmpty()) return "邮箱地址缺少 @ 前的用户名"
        if (local.length > 64) return "用户名过长（超过 64 个字符）"
        if (!LOCAL_PART_REGEX.matches(local)) return "用户名格式不正确（点号不能开头/结尾或连续）"

        if (domain.isEmpty()) return "邮箱地址缺少 @ 后的域名"
        if (!domain.contains('.')) return "域名不完整（例如 example.com）"

        val labels = domain.split('.')
        if (labels.any { it.isEmpty() }) return "域名中存在连续的点"
        if (labels.any { !DOMAIN_LABEL_REGEX.matches(it) }) return "域名格式不正确"
        // 顶级域名至少 2 个字母，可拦住 `user@example.c` 这类手滑输入
        val tld = labels.last()
        if (tld.length < 2 || tld.any { !it.isLetter() }) return "顶级域名不正确（例如 .com）"
        return null
    }

    // ------------------------------------------------------------------
    // 端口 / 服务器
    // ------------------------------------------------------------------

    /** 端口是否为 1..65535 的纯数字 */
    fun isValidPort(port: String): Boolean {
        // 允许首尾空格（手表数字键盘与粘贴都可能带上），内部不允许
        val trimmed = port.trim()
        if (trimmed.isEmpty()) return false
        if (!PORT_REGEX.matches(trimmed)) return false
        val value = trimmed.toIntOrNull() ?: return false
        return value in PORT_RANGE
    }

    /**
     * 校验一个服务器端点（主机 + 端口）。
     *
     * @return 中文错误信息；null 表示通过
     */
    fun validateServer(host: String, port: String): String? {
        if (normalizeHost(host).isEmpty()) return "服务器地址不能为空"
        if (!isValidPort(port)) return "端口需为 1~65535 之间的数字"
        return null
    }

    /**
     * 校验完整表单（步骤 3 保存前的最后一道关卡）。
     *
     * 参数命名沿用已冻结的调用契约：`httpHost/httpPort` 实际指 **IMAP** 主机与端口
     * （历史命名，为保持与既有调用处一致而保留），`smtpHost/smtpPort` 指 SMTP。
     *
     * @param requirePassword OAuth2 场景下密码/令牌可留空，传 false 跳过密码非空校验。
     *        默认 true（密码登录与应用专用密码都必须有值）。
     * @return 中文错误信息；null 表示通过
     */
    fun validateForm(
        email: String,
        password: String,
        httpHost: String,
        httpPort: String,
        smtpHost: String,
        smtpPort: String,
        requirePassword: Boolean = true,
    ): String? {
        validateEmail(email)?.let { return it }
        if (requirePassword && password.isEmpty()) return "请输入密码或授权码"
        validateServer(httpHost, httpPort)?.let { return "IMAP：$it" }
        validateServer(smtpHost, smtpPort)?.let { return "SMTP：$it" }
        return null
    }

    /**
     * 规范化主机名：去掉全部空白字符并转为小写。
     *
     * 去掉「内部」空格（而不只是首尾）是刻意的：手表端粘贴 `imap. qq. com`
     * 这类带空格的地址很常见，DNS 解析会直接失败，不如替用户修好。
     */
    fun normalizeHost(host: String): String = host.filterNot { it.isWhitespace() }.lowercase()
}
