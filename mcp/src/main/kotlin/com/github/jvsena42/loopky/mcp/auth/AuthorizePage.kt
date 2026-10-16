package com.github.jvsena42.loopky.mcp.auth

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI

/**
 * The sign-in page (#486). It names the client and the redirect domain — Ring's own prompt shows
 * the capabilities and not who asked, so this line is the phishing defence — and offers both the
 * QR code and the `pubkyauth://` button, because on a phone the browser and Ring share a screen.
 *
 * Every script and style is nonced: the CSP has no `'unsafe-inline'`, so a stray injection in
 * the client name could at worst render as text.
 */
object AuthorizePage {
    fun contentSecurityPolicy(nonce: String): String =
        "default-src 'none'; script-src 'nonce-$nonce'; style-src 'nonce-$nonce'; connect-src 'self'; " +
            "img-src data:; form-action 'none'; base-uri 'none'; frame-ancestors 'none'"

    fun render(pending: PendingAuthorization, nonce: String): String {
        val client = escape(pending.request.clientName)
        val domain = escape(redirectDomain(pending.request.redirectUri))
        val deeplink = escape(pending.authorizationUrl)
        val txnLiteral = Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(pending.txn))
        return """
            |<!doctype html>
            |<html lang="en">
            |<head>
            |<meta charset="utf-8">
            |<meta name="viewport" content="width=device-width, initial-scale=1">
            |<meta name="referrer" content="no-referrer">
            |<title>Sign in to Loopky</title>
            |<style nonce="$nonce">$STYLE</style>
            |</head>
            |<body>
            |<main>
            |<h1>Sign in with Pubky Ring</h1>
            |<p class="who"><strong>$client</strong> is asking to manage your Loopky decks.
            |After you approve, you will be sent back to <strong>$domain</strong>.</p>
            |<p class="scope">Ring will ask for <code>/pub/loopky/:rw</code> only: your decks and cards. Nothing else on your account.</p>
            |<a class="button" href="$deeplink">Open in Pubky Ring</a>
            |<p class="hint">On your phone? Tap the button, approve in Ring, then come back to this page.</p>
            |<details open>
            |<summary>Or scan with Pubky Ring on another device</summary>
            |<div class="qr">${qrSvg(pending.authorizationUrl)}</div>
            |</details>
            |<p id="status" aria-live="polite">Waiting for your approval…</p>
            |<p class="retry" id="retry" hidden><a href="">Start again</a></p>
            |</main>
            |<script nonce="$nonce">$SCRIPT_PREFIX$txnLiteral$SCRIPT</script>
            |</body>
            |</html>
            |
        """.trimMargin()
    }

    fun redirectDomain(redirectUri: String): String =
        runCatching { URI(redirectUri).host }.getOrNull() ?: redirectUri

    /** The matrix as one SVG path: no raster, no external image, nothing for the CSP to allow. */
    fun qrSvg(content: String): String {
        val hints = mapOf(EncodeHintType.MARGIN to QR_MARGIN, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M)
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints)
        val path = buildString {
            for (y in 0 until matrix.height) {
                for (x in 0 until matrix.width) if (matrix[x, y]) append("M$x ${y}h1v1h-1z")
            }
        }
        return """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${matrix.width} ${matrix.height}" """ +
            """shape-rendering="crispEdges" role="img" aria-label="QR code for Pubky Ring">""" +
            """<rect width="100%" height="100%" fill="#fff"/><path d="$path" fill="#000"/></svg>"""
    }

    fun escape(text: String): String = buildString(text.length) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private const val QR_MARGIN = 2

    private const val STYLE = """
        body{margin:0;font-family:system-ui,sans-serif;background:#fbf7ef;color:#1a1326}
        main{max-width:28rem;margin:0 auto;padding:2rem 1rem}
        h1{font-size:1.5rem}
        .button{display:block;text-align:center;background:#ff6a1f;color:#fff;text-decoration:none;
          padding:.9rem 1rem;border-radius:.75rem;font-weight:600}
        .hint,.scope{color:#5a5266;font-size:.95rem}
        .qr{background:#fff;padding:1rem;border-radius:.75rem;margin-top:.75rem}
        .qr svg{display:block;width:100%;max-width:16rem;margin:0 auto}
        summary{cursor:pointer;margin-top:1.5rem}
        #status{font-weight:600;margin-top:1.5rem}
        code{background:#efe9dd;padding:.1rem .3rem;border-radius:.3rem}
    """

    private const val SCRIPT_PREFIX = "const txn = "

    private const val SCRIPT = """;
        const status = document.getElementById('status');
        const retry = document.getElementById('retry');
        const say = (text, done) => { status.textContent = text; if (done) retry.hidden = false; };
        async function tick() {
          let reply;
          try {
            const response = await fetch('authorize/status', { cache: 'no-store', headers: { 'X-Loopky-Txn': txn } });
            reply = await response.json();
          } catch (e) { setTimeout(tick, 4000); return; }
          if (reply.status === 'approved') { say('Approved. Sending you back…'); location.replace(reply.redirect); }
          else if (reply.status === 'pending') setTimeout(tick, 2000);
          else if (reply.status === 'expired') say('This sign-in timed out.', true);
          else say('Ring did not approve this sign-in.', true);
        }
        tick();
    """
}
