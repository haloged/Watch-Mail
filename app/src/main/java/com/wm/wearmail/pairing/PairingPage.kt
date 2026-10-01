package com.wm.wearmail.pairing

import com.wm.wearmail.model.AuthType
import com.wm.wearmail.model.MailSecurity
import com.wm.wearmail.model.ProviderPreset

/**
 * 手机端配对页面（HTML 模板）。
 *
 * 设计目标：
 * - **移动端友好**：深色主题、大号输入框（≥14px 内边距、17px 字号），
 *   避免 iOS 聚焦时自动放大、避免误触；
 * - **尽量少打字**：选择服务商预设后，内联 JS 自动填充 IMAP/SMTP 主机、端口与加密方式；
 *   输入邮箱后按域名自动匹配预设（例如 `@qq.com` → imap.qq.com:993 / smtp.qq.com:465）；
 * - **无外部依赖**：不引用任何 CDN 资源，手表热点/内网环境也能正常渲染。
 *
 * 安全注意：
 * - [token] 与所有预设文本都做 HTML 转义，防止注入；
 * - 写入 `<script>` 的数据用 JS 字符串转义（并转义 `<`、`>`、`&`、U+2028/2029），
 *   保证脚本里不会出现可被解析为标签的原始字符串。
 */
object PairingPage {

    /**
     * 渲染配对页面。
     *
     * @param token 一次性会话 token（写入隐藏域，提交时回传校验）
     * @param presets 服务商预设（用于生成下拉项与 JS 自动填充数据）
     * @param host 手表局域网 IP
     * @param port 配置服务监听端口
     */
    fun render(
        token: String,
        presets: List<ProviderPreset>,
        host: String,
        port: Int,
    ): String {
        val safeToken = escapeHtml(token)
        val safeHost = escapeHtml(host)
        val presetOptions = buildPresetOptions(presets)
        val authOptions = buildAuthOptions()
        val securityOptions = buildSecurityOptions()
        val presetsJson = buildPresetsJson(presets)

        return """
<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, viewport-fit=cover">
<meta name="color-scheme" content="dark">
<meta name="referrer" content="no-referrer">
<title>WearMail 账户配置</title>
<style>
:root { color-scheme: dark; }
* { box-sizing: border-box; }
body {
  margin: 0;
  padding: 20px 16px 56px;
  background: #101114;
  color: #e8eaed;
  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Microsoft YaHei", sans-serif;
  font-size: 17px;
  line-height: 1.5;
  -webkit-text-size-adjust: 100%;
}
h1 { font-size: 22px; margin: 0 0 8px; }
p.lead { margin: 0 0 20px; color: #9aa0a6; font-size: 15px; }
fieldset { border: 1px solid #2a2d31; border-radius: 14px; margin: 0 0 16px; padding: 12px 14px 16px; }
legend { padding: 0 6px; color: #8ab4f8; font-size: 15px; }
label { display: block; margin: 14px 0 6px; color: #bdc1c6; font-size: 15px; }
input, select {
  width: 100%;
  padding: 14px 12px;
  font-size: 17px;
  color: #e8eaed;
  background: #1b1d21;
  border: 1px solid #34383d;
  border-radius: 12px;
  outline: none;
  -webkit-appearance: none;
  appearance: none;
}
input:focus, select:focus { border-color: #8ab4f8; }
.row { display: flex; gap: 10px; align-items: flex-end; }
.col-port { flex: 0 0 34%; }
.col-security { flex: 1 1 auto; }
button {
  width: 100%;
  margin-top: 22px;
  padding: 16px;
  font-size: 18px;
  font-weight: 600;
  color: #101114;
  background: #8ab4f8;
  border: 0;
  border-radius: 999px;
}
button:active { background: #aecbfa; }
.note { margin: 10px 0 0; color: #9aa0a6; font-size: 14px; }
.foot { margin: 18px 0 0; color: #5f6368; font-size: 13px; text-align: center; }
</style>
</head>
<body>
<main>
  <h1>WearMail 账户配置</h1>
  <p class="lead">在手表上输入邮箱很麻烦，请在手机上填写后提交。提交后凭据只在手表端加密保存。</p>

  <form method="post" action="/pair" autocomplete="off">
    <input type="hidden" name="t" value="$safeToken">

    <fieldset>
      <legend>账户</legend>
      <label for="email">邮箱地址</label>
      <input id="email" name="email" type="email" inputmode="email" autocapitalize="off" spellcheck="false" placeholder="you@example.com" required>
      <label for="alias">账户别名（可选）</label>
      <input id="alias" name="alias" type="text" placeholder="工作 / 个人" maxlength="32">
      <label for="password">密码 / 授权码</label>
      <input id="password" name="password" type="password" autocomplete="new-password" placeholder="多数服务商需要「授权码」" required>
      <label for="authType">认证方式</label>
      <select id="authType" name="authType">$authOptions</select>
    </fieldset>

    <fieldset>
      <legend>服务商预设</legend>
      <label for="presetId">选择服务商（自动填充服务器参数）</label>
      <select id="presetId" name="presetId">$presetOptions</select>
      <p class="note" id="authHint">选择服务商后会显示获取授权码的提示。</p>
      <p class="note">IMAP / SMTP 地址<strong>可以留空</strong>：手表会按邮箱域名自动识别（Gmail / Outlook / QQ / 163 等）。
        企业邮箱或自建域名请向管理员索取后填写。</p>
    </fieldset>

    <fieldset>
      <legend>IMAP（收件）</legend>
      <label for="imapHost">服务器地址</label>
      <input id="imapHost" name="imapHost" type="text" autocapitalize="off" spellcheck="false" placeholder="留空自动识别，如 imap.qq.com">
      <div class="row">
        <div class="col-port">
          <label for="imapPort">端口</label>
          <input id="imapPort" name="imapPort" type="number" inputmode="numeric" min="1" max="65535" value="993" required>
        </div>
        <div class="col-security">
          <label for="imapSecurity">加密方式</label>
          <select id="imapSecurity" name="imapSecurity">$securityOptions</select>
        </div>
      </div>
    </fieldset>

    <fieldset>
      <legend>SMTP（发件）</legend>
      <label for="smtpHost">服务器地址</label>
      <input id="smtpHost" name="smtpHost" type="text" autocapitalize="off" spellcheck="false" placeholder="留空自动识别，如 smtp.qq.com">
      <div class="row">
        <div class="col-port">
          <label for="smtpPort">端口</label>
          <input id="smtpPort" name="smtpPort" type="number" inputmode="numeric" min="1" max="65535" value="465" required>
        </div>
        <div class="col-security">
          <label for="smtpSecurity">加密方式</label>
          <select id="smtpSecurity" name="smtpSecurity">$securityOptions</select>
        </div>
      </div>
    </fieldset>

    <button type="submit">提交到手表</button>
    <p class="foot">手机与手表需处于同一 Wi-Fi。本页数据仅提交到 $safeHost:$port。</p>
  </form>
</main>

<script>
(function () {
  var PRESETS = $presetsJson;

  function byId(id) { return document.getElementById(id); }

  function presetById(id) {
    for (var i = 0; i < PRESETS.length; i++) {
      if (PRESETS[i].id === id) { return PRESETS[i]; }
    }
    return null;
  }

  function fillFromPreset(preset) {
    if (!preset) { return; }
    if (preset.imapHost) { byId('imapHost').value = preset.imapHost; }
    if (preset.imapPort) { byId('imapPort').value = preset.imapPort; }
    if (preset.imapSecurity) { byId('imapSecurity').value = preset.imapSecurity; }
    if (preset.smtpHost) { byId('smtpHost').value = preset.smtpHost; }
    if (preset.smtpPort) { byId('smtpPort').value = preset.smtpPort; }
    if (preset.smtpSecurity) { byId('smtpSecurity').value = preset.smtpSecurity; }
    byId('authHint').textContent = preset.authHint || '';
  }

  function detectPreset(email) {
    var at = email.lastIndexOf('@');
    if (at < 0) { return null; }
    var domain = email.slice(at + 1).toLowerCase();
    if (!domain) { return null; }
    for (var i = 0; i < PRESETS.length; i++) {
      var keys = PRESETS[i].keywords || [];
      for (var k = 0; k < keys.length; k++) {
        var key = keys[k];
        if (domain === key || domain.slice(-(key.length + 1)) === '.' + key) {
          return PRESETS[i];
        }
      }
    }
    return null;
  }

  // 用户是否手动改过下拉框：手动选择优先，不再被域名识别覆盖
  var presetTouched = false;

  byId('presetId').addEventListener('change', function () {
    presetTouched = true;
    fillFromPreset(presetById(this.value));
  });

  // 边输入边按域名识别服务商（不再只在失焦时识别 —— 手机上更容易生效）。
  // 即使这段脚本完全没执行，服务端也会按邮箱域名兜底识别，
  // 因此主机留空也不会导致提交失败。
  byId('email').addEventListener('input', function () {
    if (presetTouched) { return; }
    var found = detectPreset(this.value.trim());
    if (found) {
      byId('presetId').value = found.id;
      fillFromPreset(found);
    }
  });
})();
</script>
</body>
</html>
""".trimIndent()
    }

    // ------------------------------------------------------------------
    // 片段生成
    // ------------------------------------------------------------------

    /** 预设下拉项；空值项表示「自行填写」 */
    private fun buildPresetOptions(presets: List<ProviderPreset>): String {
        val options = StringBuilder()
        options.append("<option value=\"\">自定义 / 不确定</option>")
        for (preset in presets) {
            options.append("<option value=\"")
                .append(escapeHtml(preset.id))
                .append("\">")
                .append(escapeHtml(preset.label))
                .append("</option>")
        }
        return options.toString()
    }

    /** 认证方式下拉项；默认选中「应用专用密码」（第三方客户端最常见） */
    private fun buildAuthOptions(): String {
        val options = StringBuilder()
        for (type in AuthType.entries) {
            options.append("<option value=\"")
                .append(escapeHtml(type.name))
                .append('"')
            if (type == AuthType.APP_PASSWORD) options.append(" selected")
            options.append('>')
                .append(escapeHtml(type.label))
                .append("</option>")
        }
        return options.toString()
    }

    /** 加密方式下拉项；默认选中 SSL/TLS */
    private fun buildSecurityOptions(): String {
        val options = StringBuilder()
        for (security in MailSecurity.entries) {
            options.append("<option value=\"")
                .append(escapeHtml(security.name))
                .append('"')
            if (security == MailSecurity.SSL_TLS) options.append(" selected")
            options.append('>')
                .append(escapeHtml(security.label))
                .append("</option>")
        }
        return options.toString()
    }

    /** 生成内联脚本用的预设数据（JSON 数组，字符串均为 JS 字面量转义后的结果） */
    private fun buildPresetsJson(presets: List<ProviderPreset>): String {
        val builder = StringBuilder()
        builder.append('[')
        presets.forEachIndexed { index, preset ->
            if (index > 0) builder.append(',')
            builder.append('{')
            builder.append("\"id\":").append(jsString(preset.id)).append(',')
            builder.append("\"label\":").append(jsString(preset.label)).append(',')
            builder.append("\"authHint\":").append(jsString(preset.authHint)).append(',')
            builder.append("\"keywords\":[")
            preset.domainKeywords.forEachIndexed { keyIndex, keyword ->
                if (keyIndex > 0) builder.append(',')
                builder.append(jsString(keyword))
            }
            builder.append("],")
            builder.append("\"imapHost\":").append(jsString(preset.config.imapHost)).append(',')
            builder.append("\"imapPort\":").append(preset.config.imapPort).append(',')
            builder.append("\"imapSecurity\":").append(jsString(preset.config.imapSecurity.name)).append(',')
            builder.append("\"smtpHost\":").append(jsString(preset.config.smtpHost)).append(',')
            builder.append("\"smtpPort\":").append(preset.config.smtpPort).append(',')
            builder.append("\"smtpSecurity\":").append(jsString(preset.config.smtpSecurity.name))
            builder.append('}')
        }
        builder.append(']')
        return builder.toString()
    }

    /** HTML 文本/属性转义 */
    private fun escapeHtml(value: String): String {
        val builder = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '&' -> builder.append("&amp;")
                '<' -> builder.append("&lt;")
                '>' -> builder.append("&gt;")
                '"' -> builder.append("&quot;")
                '\'' -> builder.append("&#39;")
                else -> builder.append(ch)
            }
        }
        return builder.toString()
    }

    /** JS 字符串字面量转义（含 `</script>` 与 U+2028/U+2029 防护） */
    private fun jsString(value: String): String {
        val builder = StringBuilder(value.length + 8)
        builder.append('"')
        for (ch in value) {
            when (ch) {
                '"' -> builder.append("\\\"")
                '\\' -> builder.append("\\\\")
                '\n' -> builder.append("\\n")
                '\r' -> builder.append("\\r")
                '\t' -> builder.append("\\t")
                '<' -> builder.append("\\u003C")
                '>' -> builder.append("\\u003E")
                '&' -> builder.append("\\u0026")
                '\u2028' -> builder.append("\\u2028")
                '\u2029' -> builder.append("\\u2029")
                else -> if (ch.code < 0x20) {
                    builder.append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    builder.append(ch)
                }
            }
        }
        builder.append('"')
        return builder.toString()
    }
}
