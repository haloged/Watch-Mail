package com.haloged.watchmail.data.remote.pairing

/**
 * 手机端配对配置页（H5）
 *
 * 由手表本地 Web 服务返回给手机浏览器渲染。
 *
 * ⚠ 重要：手表服务跑在局域网 `http://IP:8765`，**不是安全上下文**，
 * 浏览器会禁用 `crypto.subtle`（WebCrypto）。因此这里用纯 JS 自实现加密，
 * 不依赖任何浏览器加密 API，在 HTTP 下同样可用。
 *
 * 加密方案（encrypt-then-MAC，两端严格一致）：
 *   master = SHA-256(utf8(配对码))
 *   encKey = SHA-256(master || "enc")
 *   macKey = SHA-256(master || "mac")
 *   iv     = 16 字节随机
 *   ks[i]  = SHA-256(encKey || iv || u32be(i))      // 32 字节/块，i 从 0 起
 *   ct     = pt XOR ks
 *   tag    = HMAC-SHA256(macKey, iv || ct) 的前 16 字节
 *   提交   = { iv: b64, ct: b64, tag: b64 }
 *
 * 配对码放在 URL fragment（#k=…），fragment 不会随 HTTP 请求发出，
 * 因此局域网抓包看不到配对码，也就无法解密或伪造提交。
 *
 * 注意：JS 中不使用模板字符串与 `$`，避免 Kotlin 字符串插值冲突。
 */
object PairingWebPage {

    /** HTML 页面模板 */
    const val HTML: String = """<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<meta name="theme-color" content="#0d1117">
<title>WatchMail · 邮箱配置</title>
<style>
  :root {
    --bg: #0d1117; --card: #161b22; --line: #30363d;
    --fg: #e6edf3; --sub: #8b949e; --accent: #4a90e2; --err: #e53935; --ok: #4caf50;
  }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0; padding: 20px 16px 40px; background: var(--bg); color: var(--fg);
    font-family: -apple-system, BlinkMacSystemFont, "PingFang SC", "Microsoft YaHei", sans-serif;
    font-size: 16px; line-height: 1.5;
  }
  .wrap { max-width: 520px; margin: 0 auto; }
  h1 { font-size: 22px; margin: 0 0 4px; }
  .sub { color: var(--sub); font-size: 14px; margin: 0 0 20px; }
  .badge {
    display: inline-flex; align-items: center; gap: 6px; padding: 4px 10px; border-radius: 999px;
    background: rgba(74,144,226,.15); color: var(--accent); font-size: 12px; margin-bottom: 16px;
  }
  .badge.ok  { background: rgba(76,175,80,.15);  color: var(--ok); }
  .badge.err { background: rgba(229,57,53,.15); color: var(--err); }
  form { background: var(--card); border: 1px solid var(--line); border-radius: 14px; padding: 16px; }
  label { display: block; font-size: 13px; color: var(--sub); margin: 14px 0 6px; }
  label:first-of-type { margin-top: 0; }
  input, select {
    width: 100%; padding: 12px 14px; border-radius: 10px; border: 1px solid var(--line);
    background: #0d1117; color: var(--fg); font-size: 16px; outline: none;
  }
  input:focus, select:focus { border-color: var(--accent); }
  input::placeholder { color: #484f58; }
  details { margin-top: 16px; border: 1px solid var(--line); border-radius: 10px; padding: 12px 14px; }
  summary { cursor: pointer; color: var(--accent); font-size: 14px; list-style: none; }
  summary::-webkit-details-marker { display: none; }
  .hint { font-size: 12px; color: var(--sub); margin: 6px 0 0; }
  .grid { display: grid; grid-template-columns: 1fr 110px; gap: 10px; }
  button {
    width: 100%; margin-top: 22px; padding: 14px; border: 0; border-radius: 12px;
    background: var(--accent); color: #fff; font-size: 17px; font-weight: 600; cursor: pointer;
  }
  button:disabled { opacity: .5; cursor: not-allowed; }
  button.loading { position: relative; color: transparent; }
  button.loading::after {
    content: ""; position: absolute; inset: 0; margin: auto; width: 20px; height: 20px;
    border: 2px solid rgba(255,255,255,.35); border-top-color: #fff; border-radius: 50%;
    animation: spin .8s linear infinite;
  }
  @keyframes spin { to { transform: rotate(360deg); } }
  .msg { margin-top: 14px; font-size: 14px; display: none; padding: 10px 12px; border-radius: 8px; }
  .msg.err { display: block; background: rgba(229,57,53,.12); color: var(--err); }
  .msg.ok  { display: block; background: rgba(76,175,80,.12); color: var(--ok); }
  .done { text-align: center; padding: 30px 10px; }
  .done .big { font-size: 48px; margin-bottom: 10px; }
  .done p { color: var(--sub); }
</style>
</head>
<body>
<div class="wrap">
  <h1>&#8986; WatchMail</h1>
  <p class="sub">填写邮箱账户，保存到你的手表</p>
  <div id="badge" class="badge">&#8226; 本地加密通道（不依赖 WebCrypto）</div>

  <form id="form" autocomplete="off">
    <label>配对码 <span id="pinState"></span></label>
    <input id="pin" inputmode="latin" maxlength="32" placeholder="扫码自动填入；否则看手表屏幕输入">

    <label>邮箱地址</label>
    <input id="email" type="email" required placeholder="you@example.com">

    <label>密码 / 应用专用密码 / 授权码</label>
    <input id="password" type="password" required placeholder="Gmail 用应用专用密码，QQ/163 用授权码">
    <p class="hint">密码仅用于登录邮箱，提交前在浏览器内加密，手表上再加密存储。</p>

    <label>别名（可选）</label>
    <input id="alias" placeholder="如：工作 / 个人">

    <details id="adv">
      <summary>&#9656; 高级：手动指定服务器（留空则自动探测）</summary>

      <label>IMAP 服务器</label>
      <div class="grid">
        <input id="imapHost" placeholder="imap.example.com">
        <input id="imapPort" inputmode="numeric" placeholder="993">
      </div>
      <label>IMAP 加密</label>
      <select id="imapEnc">
        <option value="">自动</option>
        <option value="SSL">SSL/TLS</option>
        <option value="STARTTLS">STARTTLS</option>
        <option value="NONE">不加密</option>
      </select>

      <label>SMTP 服务器</label>
      <div class="grid">
        <input id="smtpHost" placeholder="smtp.example.com">
        <input id="smtpPort" inputmode="numeric" placeholder="587">
      </div>
      <label>SMTP 加密</label>
      <select id="smtpEnc">
        <option value="">自动</option>
        <option value="SSL">SSL/TLS</option>
        <option value="STARTTLS">STARTTLS</option>
        <option value="NONE">不加密</option>
      </select>
      <p class="hint">留空服务器地址时，手表会按邮箱域名自动探测 Gmail / Outlook / QQ / 163 等配置。</p>
    </details>

    <div id="msg" class="msg"></div>
    <button id="submitBtn" type="submit">保存到手表</button>
  </form>

  <div id="done" class="done" style="display:none">
    <div class="big">&#10003;</div>
    <h2>配置已发送</h2>
    <p>手表已收到账户信息，可关闭此页面。</p>
  </div>
</div>

<script>
(function () {
  "use strict";

  /* ================================================================
   *  1. 纯 JS SHA-256（不依赖任何浏览器加密 API）
   * ================================================================ */
  var K256 = new Uint32Array([
    0x428a2f98,0x71374491,0xb5c0fbcf,0xe9b5dba5,0x3956c25b,0x59f111f1,0x923f82a4,0xab1c5ed5,
    0xd807aa98,0x12835b01,0x243185be,0x550c7dc3,0x72be5d74,0x80deb1fe,0x9bdc06a7,0xc19bf174,
    0xe49b69c1,0xefbe4786,0x0fc19dc6,0x240ca1cc,0x2de92c6f,0x4a7484aa,0x5cb0a9dc,0x76f988da,
    0x983e5152,0xa831c66d,0xb00327c8,0xbf597fc7,0xc6e00bf3,0xd5a79147,0x06ca6351,0x14292967,
    0x27b70a85,0x2e1b2138,0x4d2c6dfc,0x53380d13,0x650a7354,0x766a0abb,0x81c2c92e,0x92722c85,
    0xa2bfe8a1,0xa81a664b,0xc24b8b70,0xc76c51a3,0xd192e819,0xd6990624,0xf40e3585,0x106aa070,
    0x19a4c116,0x1e376c08,0x2748774c,0x34b0bcb5,0x391c0cb3,0x4ed8aa4a,0x5b9cca4f,0x682e6ff3,
    0x748f82ee,0x78a5636f,0x84c87814,0x8cc70208,0x90befffa,0xa4506ceb,0xbef9a3f7,0xc67178f2
  ]);

  function rotr(x, n) { return (x >>> n) | (x << (32 - n)); }

  /** SHA-256(Uint8Array) -> Uint8Array(32) */
  function sha256(m) {
    var len = m.length;
    var tlen = (((len + 9 + 63) >> 6) << 6);   // 最小的 >= len+9 的 64 倍数
    var buf = new Uint8Array(tlen);
    buf.set(m);
    buf[len] = 0x80;
    var hi = Math.floor(len / 536870912);       // len*8 / 2^32
    var lo = (len * 8) >>> 0;
    buf[tlen - 8] = (hi >>> 24) & 255;
    buf[tlen - 7] = (hi >>> 16) & 255;
    buf[tlen - 6] = (hi >>> 8)  & 255;
    buf[tlen - 5] =  hi         & 255;
    buf[tlen - 4] = (lo >>> 24) & 255;
    buf[tlen - 3] = (lo >>> 16) & 255;
    buf[tlen - 2] = (lo >>> 8)  & 255;
    buf[tlen - 1] =  lo         & 255;

    var H = new Uint32Array([
      0x6a09e667,0xbb67ae85,0x3c6ef372,0xa54ff53a,0x510e527f,0x9b05688c,0x1f83d9ab,0x5be0cd19
    ]);
    var w = new Uint32Array(64);
    var off, i, a, b, c, d, e, f, g, h, s0, s1, S0, S1, ch, maj, t1, t2;

    for (off = 0; off < tlen; off += 64) {
      for (i = 0; i < 16; i++) {
        w[i] = ((buf[off + i*4] << 24) | (buf[off + i*4 + 1] << 16) |
                (buf[off + i*4 + 2] << 8) | buf[off + i*4 + 3]) >>> 0;
      }
      for (i = 16; i < 64; i++) {
        s0 = (rotr(w[i-15], 7)  ^ rotr(w[i-15], 18) ^ (w[i-15] >>> 3))  >>> 0;
        s1 = (rotr(w[i-2], 17)  ^ rotr(w[i-2], 19)  ^ (w[i-2]  >>> 10)) >>> 0;
        w[i] = (w[i-16] + s0 + w[i-7] + s1) >>> 0;
      }

      a = H[0]; b = H[1]; c = H[2]; d = H[3]; e = H[4]; f = H[5]; g = H[6]; h = H[7];
      for (i = 0; i < 64; i++) {
        S1 = (rotr(e, 6) ^ rotr(e, 11) ^ rotr(e, 25)) >>> 0;
        ch = ((e & f) ^ ((~e) & g)) >>> 0;
        t1 = (h + S1 + ch + K256[i] + w[i]) >>> 0;
        S0 = (rotr(a, 2) ^ rotr(a, 13) ^ rotr(a, 22)) >>> 0;
        maj = ((a & b) ^ (a & c) ^ (b & c)) >>> 0;
        t2 = (S0 + maj) >>> 0;
        h = g; g = f; f = e; e = (d + t1) >>> 0;
        d = c; c = b; b = a; a = (t1 + t2) >>> 0;
      }
      H[0] = (H[0] + a) >>> 0; H[1] = (H[1] + b) >>> 0;
      H[2] = (H[2] + c) >>> 0; H[3] = (H[3] + d) >>> 0;
      H[4] = (H[4] + e) >>> 0; H[5] = (H[5] + f) >>> 0;
      H[6] = (H[6] + g) >>> 0; H[7] = (H[7] + h) >>> 0;
    }

    var out = new Uint8Array(32);
    for (i = 0; i < 8; i++) {
      out[i*4]     = (H[i] >>> 24) & 255;
      out[i*4 + 1] = (H[i] >>> 16) & 255;
      out[i*4 + 2] = (H[i] >>> 8)  & 255;
      out[i*4 + 3] =  H[i]         & 255;
    }
    return out;
  }

  /** HMAC-SHA256(key, msg) -> Uint8Array(32) */
  function hmacSha256(key, msg) {
    if (key.length > 64) key = sha256(key);
    var padIn  = new Uint8Array(64 + msg.length);
    var padOut = new Uint8Array(64 + 32);
    var i;
    for (i = 0; i < 64; i++) {
      var kb = (i < key.length) ? key[i] : 0;
      padIn[i]  = kb ^ 0x36;
      padOut[i] = kb ^ 0x5c;
    }
    padIn.set(msg, 64);
    padOut.set(sha256(padIn), 64);
    return sha256(padOut);
  }

  /* ================================================================
   *  2. 字节/编码/随机 工具
   * ================================================================ */
  function utf8(str) { return new TextEncoder().encode(str); }

  function concat() {
    var total = 0, i, j;
    for (i = 0; i < arguments.length; i++) total += arguments[i].length;
    var out = new Uint8Array(total);
    var off = 0;
    for (i = 0; i < arguments.length; i++) {
      out.set(arguments[i], off);
      off += arguments[i].length;
    }
    return out;
  }

  function u32be(n) {
    return new Uint8Array([(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]);
  }

  function b64(u8) {
    var s = "";
    for (var i = 0; i < u8.length; i++) s += String.fromCharCode(u8[i]);
    return btoa(s);
  }

  function randomBytes(n) {
    var out = new Uint8Array(n);
    // crypto.getRandomValues 在非安全上下文下依然可用（只有 crypto.subtle 受限）
    if (window.crypto && window.crypto.getRandomValues) {
      window.crypto.getRandomValues(out);
    } else {
      for (var i = 0; i < n; i++) out[i] = Math.floor(Math.random() * 256);
    }
    return out;
  }

  /* ================================================================
   *  3. 加密：SHA-256-CTR + HMAC-SHA256（encrypt-then-MAC）
   *     与手表端 QrPairingServer 完全一致
   * ================================================================ */
  function deriveKeys(pin) {
    var master = sha256(utf8(pin));
    return {
      enc: sha256(concat(master, utf8("enc"))),
      mac: sha256(concat(master, utf8("mac")))
    };
  }

  function encryptPayload(obj, pin) {
    var keys = deriveKeys(pin);
    var iv = randomBytes(16);
    var pt = utf8(JSON.stringify(obj));
    var ct = new Uint8Array(pt.length);
    var counter = 0, off, j, ks;

    for (off = 0; off < pt.length; off += 32) {
      ks = sha256(concat(keys.enc, iv, u32be(counter)));
      for (j = 0; j < 32 && (off + j) < pt.length; j++) {
        ct[off + j] = pt[off + j] ^ ks[j];
      }
      counter++;
    }

    var tag = hmacSha256(keys.mac, concat(iv, ct));
    return {
      iv:  b64(iv),
      ct:  b64(ct),
      tag: b64(tag.subarray(0, 16))
    };
  }

  /* ================================================================
   *  4. 表单逻辑
   * ================================================================ */
  function readPinFromHash() {
    var h = window.location.hash || "";
    var m = /[#&]k=([A-Za-z0-9_-]+)/.exec(h);
    return m ? m[1] : "";
  }

  var autoPin = readPinFromHash();
  var pinInput = document.getElementById("pin");
  var pinState = document.getElementById("pinState");
  if (autoPin) {
    pinInput.value = autoPin;
    pinInput.readOnly = true;
    pinState.textContent = "(已自动填入)";
    pinState.style.color = "#4caf50";
  }

  function val(id) { return (document.getElementById(id).value || "").trim(); }

  function collect() {
    return {
      email: val("email"),
      password: document.getElementById("password").value,
      alias: val("alias"),
      imapHost: val("imapHost"),
      imapPort: parseInt(val("imapPort"), 10) || 0,
      imapEncryption: val("imapEnc"),
      smtpHost: val("smtpHost"),
      smtpPort: parseInt(val("smtpPort"), 10) || 0,
      smtpEncryption: val("smtpEnc")
    };
  }

  var msg = document.getElementById("msg");
  var btn = document.getElementById("submitBtn");

  function show(text, kind) {
    msg.textContent = text;
    msg.className = "msg " + kind;
  }
  function clearMsg() { msg.className = "msg"; msg.textContent = ""; }

  document.getElementById("form").addEventListener("submit", function (e) {
    e.preventDefault();
    clearMsg();

    var pin = pinInput.value.trim();
    if (!pin) { show("请填写手表屏幕上显示的配对码", "err"); return; }

    var payload = collect();
    if (!payload.email || !payload.password) {
      show("请填写邮箱地址和密码", "err");
      return;
    }

    btn.disabled = true;
    btn.classList.add("loading");

    var enc;
    try {
      enc = encryptPayload(payload, pin);
    } catch (ex) {
      btn.disabled = false;
      btn.classList.remove("loading");
      show("加密失败：" + ex.message, "err");
      return;
    }

    fetch("/submit", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(enc)
    }).then(function (resp) {
      return resp.json().then(function (j) {
        return { ok: resp.ok, body: j };
      });
    }).then(function (r) {
      btn.disabled = false;
      btn.classList.remove("loading");
      if (r.ok && r.body && r.body.ok) {
        document.getElementById("form").style.display = "none";
        document.getElementById("done").style.display = "block";
        document.getElementById("badge").textContent = "\u2022 已完成";
        document.getElementById("badge").className = "badge ok";
      } else {
        var reason = (r.body && r.body.error) ? r.body.error : "保存失败";
        show(reason + "（请确认配对码与手表屏幕一致，且仍在 5 分钟有效期内）", "err");
      }
    }).catch(function (err) {
      btn.disabled = false;
      btn.classList.remove("loading");
      show("网络错误：" + err.message, "err");
    });
  });
})();
</script>
</body>
</html>
"""
}
