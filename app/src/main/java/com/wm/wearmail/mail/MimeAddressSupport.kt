package com.wm.wearmail.mail

import com.wm.wearmail.core.Logs
import com.wm.wearmail.model.MailAddress
import java.nio.charset.Charset
import java.util.Base64

private const val TAG = "MimeAddress"

/**
 * 邮件地址列表的解析与序列化（纯 Kotlin，不依赖 `javax.mail`）。
 *
 * 使用场景：
 * - 协议层把 `From` / `To` / `Cc` 头部（或 `javax.mail.Address`）转成 [MailAddress]；
 * - 数据层仓储把 `EmailMeta.to` / `EmailMeta.cc` 序列化成**一个字符串**存库，
 *   读回时再反序列化（签名由仓储契约冻结，不可更改）。
 *
 * 为什么不直接用 `InternetAddress.parse`：
 * 1. 它对畸形头部非常严格（真实邮件里到处是缺引号、多逗号、中文显示名），
 *    抛 `AddressException` 会让整封邮件的收发件人全部丢失；
 * 2. 仓储序列化需要「能被我方解析器稳定还原」的确定性格式。
 * 因此这里实现了一个宽容的 RFC 5322 子集解析器：解析失败只跳过该段，绝不抛异常。
 */
object MimeAddressSupport {

    /** 需要加引号的显示名字符（RFC 5322 specials） */
    private const val SPECIALS = "()<>@,;:\\\".[]"

    private val WHITESPACE = Regex("\\s+")

    /** RFC 2047 编码字：`=?charset?B|Q?payload?=` */
    private val ENCODED_WORD = Regex("=\\?([^?\\s]+)\\?([BbQq])\\?([^?\\s]*)\\?=")

    /** 两个相邻编码字之间用于折行的空白必须删除（RFC 2047 第 2 节） */
    private val FOLDED_BETWEEN_WORDS = Regex("(=\\?[^?\\s]+\\?[BbQq]\\?[^?\\s]*\\?=)\\s+(?==\\?)")

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /**
     * 解析地址列表，例如 `"张三" <a@b.com>, c@d.com`。
     *
     * 支持：带引号 / 不带引号的显示名、尖括号地址、裸地址、
     * 组语法（`组名: a@b.com, c@d.com;`）、注释形式的显示名（`a@b.com (张三)`）、
     * RFC 2047 编码的显示名。
     * 非法片段（无 `@`、无域名等）会被静默跳过，不抛异常。
     */
    fun parseAddressList(raw: String?): List<MailAddress> {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return emptyList()

        val result = ArrayList<MailAddress>()
        for (token in splitTopLevel(text)) {
            val cleaned = stripGroupSyntax(token)
            if (cleaned.isEmpty()) continue
            val parsed = parseToken(cleaned)
            if (parsed == null) {
                // 真实邮件里常见畸形地址；只记 debug 日志（不含敏感正文），便于排障
                Logs.d(TAG, "跳过无法解析的地址片段（长度=${cleaned.length}）")
                continue
            }
            result += parsed
        }
        return result
    }

    /** 解析第一个地址；解析失败返回 [MailAddress.UNKNOWN]（UI 层无需处理 null） */
    fun firstAddress(raw: String?): MailAddress =
        parseAddressList(raw).firstOrNull() ?: MailAddress.UNKNOWN

    /**
     * 解析单个地址（用于 `from` 这类单值字段）。
     *
     * 输入含多个地址时取第一个；解析失败返回 [MailAddress.UNKNOWN]。
     */
    fun parseSingle(address: String?): MailAddress =
        parseAddressList(address).firstOrNull() ?: MailAddress.UNKNOWN

    // ------------------------------------------------------------------
    // 序列化
    // ------------------------------------------------------------------

    /**
     * 序列化为 `显示名 <地址>, 地址` 形式，供数据库单列存储。
     *
     * 与 [parseAddressList] 严格互逆（空地址项被跳过）：
     * 显示名含逗号等特殊字符时会加引号，保证解析时不会被误拆成两个地址。
     */
    fun formatAddressList(addresses: List<MailAddress>): String {
        val builder = StringBuilder()
        for (item in addresses) {
            val address = item.address.trim()
            if (address.isEmpty()) continue
            if (builder.isNotEmpty()) builder.append(", ")
            builder.append(formatOne(address, item.name))
        }
        return builder.toString()
    }

    private fun formatOne(address: String, name: String?): String {
        val displayName = name?.trim().orEmpty()
        if (displayName.isEmpty()) return address
        return "${quotePhrase(displayName)} <$address>"
    }

    /** 需要时给显示名加引号并转义（含特殊字符、首尾空白、单字符场景） */
    private fun quotePhrase(name: String): String {
        val needsQuote = name.any { it in SPECIALS } ||
            name.first().isWhitespace() ||
            name.last().isWhitespace()
        if (!needsQuote) return name
        val escaped = name.replace("\\", "\\\\").replace("\"", "\\\"")
        return "\"$escaped\""
    }

    // ------------------------------------------------------------------
    // 内部分词
    // ------------------------------------------------------------------

    /**
     * 按顶层分隔符（`,` 与 `;`）切分，保护引号内、尖括号内与注释内的分隔符。
     *
     * 例：`"张,三" <a@b.com>, c@d.com` → `["张,三" <a@b.com>]`、`[ c@d.com]`
     */
    private fun splitTopLevel(text: String): List<String> {
        val parts = ArrayList<String>()
        val current = StringBuilder()
        var inQuote = false
        var angleDepth = 0
        var commentDepth = 0
        var index = 0

        while (index < text.length) {
            val char = text[index]
            when {
                // 引号内的转义字符连同被转义字符一起保留，避免误判引号结束
                char == '\\' && inQuote -> {
                    current.append(char)
                    if (index + 1 < text.length) current.append(text[index + 1])
                    index += 2
                    continue
                }

                char == '"' -> {
                    inQuote = !inQuote
                    current.append(char)
                }

                char == '<' && !inQuote -> {
                    angleDepth++
                    current.append(char)
                }

                char == '>' && !inQuote -> {
                    if (angleDepth > 0) angleDepth--
                    current.append(char)
                }

                char == '(' && !inQuote && angleDepth == 0 -> {
                    commentDepth++
                    current.append(char)
                }

                char == ')' && !inQuote && angleDepth == 0 -> {
                    if (commentDepth > 0) commentDepth--
                    current.append(char)
                }

                (char == ',' || char == ';') && !inQuote && angleDepth == 0 && commentDepth == 0 -> {
                    parts += current.toString()
                    current.clear()
                }

                else -> current.append(char)
            }
            index++
        }
        parts += current.toString()
        return parts
    }

    /** 去掉组语法前缀（`组名:`）与结尾分号；冒号必须出现在引号外且早于 `<` / `@` 才算组名 */
    private fun stripGroupSyntax(token: String): String {
        var text = token.trim()
        val colon = indexOutsideQuotes(text, ':')
        if (colon >= 0) {
            val angle = indexOutsideQuotes(text, '<')
            val at = indexOutsideQuotes(text, '@')
            val colonIsGroupPrefix = (angle < 0 || colon < angle) && (at < 0 || colon < at)
            if (colonIsGroupPrefix) text = text.substring(colon + 1).trim()
        }
        while (text.endsWith(";")) text = text.dropLast(1).trim()
        return text
    }

    /** 解析单个地址片段；无法解析返回 null */
    private fun parseToken(token: String): MailAddress? {
        val text = token.trim()
        if (text.isEmpty()) return null

        val lt = indexOutsideQuotes(text, '<', last = true)
        val gt = if (lt >= 0) text.indexOf('>', lt) else -1
        if (lt >= 0 && gt > lt) {
            val address = text.substring(lt + 1, gt).trim()
            if (!isPlausibleAddress(address)) return null
            val name = normalizeName(unquotePhrase(text.substring(0, lt)))
            return MailAddress(address = address, name = name.ifBlank { null })
        }

        // 无尖括号：可能是裸地址，或「地址 (显示名)」形式
        val (addressPart, comment) = splitTrailingComment(text)
        val address = addressPart.trim()
        if (!isPlausibleAddress(address)) return null
        val name = comment?.let { normalizeName(it) }.orEmpty()
        return MailAddress(address = address, name = name.ifBlank { null })
    }

    /** 拆出结尾的 `(注释)`，注释在邮件客户端里常被当作显示名使用 */
    private fun splitTrailingComment(token: String): Pair<String, String?> {
        if (!token.endsWith(")")) return token to null
        val open = indexOutsideQuotes(token, '(', last = true)
        if (open < 0) return token to null
        val inner = token.substring(open + 1, token.length - 1)
        if (inner.contains('(')) return token to null // 嵌套注释不做处理，保持原文
        return token.substring(0, open).trim() to inner.trim().ifBlank { null }
    }

    /** 宽松校验：必须有非空的本地部分与域名部分，且域名不含空白 */
    private fun isPlausibleAddress(address: String): Boolean {
        if (address.isEmpty()) return false
        val at = indexOutsideQuotes(address, '@', last = true)
        if (at <= 0 || at >= address.length - 1) return false
        val local = address.substring(0, at)
        val domain = address.substring(at + 1)
        if (local.isBlank() || domain.isBlank()) return false
        if (domain.any { it.isWhitespace() }) return false
        // 带引号的本地部分（"a b"@x.com）允许含空白
        if (!local.startsWith("\"") && local.any { it.isWhitespace() }) return false
        if (address.any { it == '<' || it == '>' }) return false
        return true
    }

    /** 去掉成对引号并还原转义；同时压缩折行产生的多余空白 */
    private fun unquotePhrase(text: String): String {
        val trimmed = text.trim()
        val unquoted = if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            trimmed.substring(1, trimmed.length - 1)
                .replace("\\\"", "\"")
                .replace("\\\\", "\\")
        } else {
            trimmed
        }
        return unquoted
    }

    private fun normalizeName(name: String): String {
        val decoded = decodeEncodedWords(name)
        return WHITESPACE.replace(decoded, " ").trim()
    }

    /** 返回 [target] 在引号外的第一个（或最后一个）下标，找不到返回 -1 */
    private fun indexOutsideQuotes(text: String, target: Char, last: Boolean = false): Int {
        var inQuote = false
        var found = -1
        var index = 0
        while (index < text.length) {
            val char = text[index]
            when {
                char == '\\' && inQuote -> index++
                char == '"' -> inQuote = !inQuote
                char == target && !inQuote -> {
                    found = index
                    if (!last) return index
                }
            }
            index++
        }
        return found
    }

    // ------------------------------------------------------------------
    // RFC 2047 编码字解码
    // ------------------------------------------------------------------

    /** 解码 `=?UTF-8?B?...?=` / `=?GBK?Q?...?=`；无法解码时保持原文 */
    private fun decodeEncodedWords(text: String): String {
        if (!text.contains("=?")) return text
        val collapsed = FOLDED_BETWEEN_WORDS.replace(text) { it.groupValues[1] }
        return ENCODED_WORD.replace(collapsed) { match ->
            val charsetName = match.groupValues[1]
            val encoding = match.groupValues[2].uppercase()
            val payload = match.groupValues[3]
            runCatching {
                val bytes = if (encoding == "B") {
                    Base64.getMimeDecoder().decode(payload)
                } else {
                    decodeQuotedPrintable(payload)
                }
                String(bytes, charsetOf(charsetName))
            }.getOrElse { match.value }
        }
    }

    private fun charsetOf(name: String): Charset =
        runCatching { Charset.forName(name.trim().trim('"', '\'')) }.getOrDefault(Charsets.UTF_8)

    /** RFC 2047 的 Q 编码：`_` 表示空格，`=XX` 表示十六进制字节 */
    private fun decodeQuotedPrintable(payload: String): ByteArray {
        val out = java.io.ByteArrayOutputStream(payload.length)
        var index = 0
        while (index < payload.length) {
            val char = payload[index]
            when {
                char == '_' -> {
                    out.write(' '.code)
                    index++
                }

                char == '=' && index + 3 <= payload.length -> {
                    val value = payload.substring(index + 1, index + 3).toIntOrNull(16)
                    if (value == null) {
                        out.write(char.code)
                        index++
                    } else {
                        out.write(value)
                        index += 3
                    }
                }

                else -> {
                    out.write(char.code and 0xFF)
                    index++
                }
            }
        }
        return out.toByteArray()
    }
}
