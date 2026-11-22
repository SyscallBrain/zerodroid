package pt.tiago.zerodroid.enroll

import java.net.URI

/** Where an OIDC sign-in goes: the daemon's WSS endpoint and the gateway that relays the device grant. */
data class OidcTarget(
    val alias: String,
    val provider: String,
    val wssUrl: String,
    val host: String,
    val wssPort: Int,
    /** HTTPS origin plus any path prefix, without a trailing slash. */
    val gatewayUrl: String,
)

class OidcTargetException(val field: Field, message: String) : IllegalArgumentException(message) {
    enum class Field { Host, Alias, WssUrl, GatewayUrl }
}

private const val DEFAULT_WSS_PORT = 9781
private const val DEFAULT_GATEWAY_PORT = 42617
private val LOOPBACK = setOf("localhost", "127.0.0.1", "::1", "[::1]")

private fun fail(field: OidcTargetException.Field, message: String): Nothing = throw OidcTargetException(field, message)

/** Mirrors zerocode's `provider_alias`: `corp` or `oidc.corp`. */
internal fun normalizeAlias(raw: String): String {
    val alias = raw.trim().let { if (it.startsWith("oidc.", ignoreCase = true)) it.substring(5) else it }
    val ok = alias.length in 1..64 && alias[0].isAsciiAlnum() && alias.all { it.isAsciiAlnum() || it == '_' || it == '-' }
    if (!ok) fail(OidcTargetException.Field.Alias, "Use the provider alias from the daemon config, like corp.")
    return alias
}

private fun Char.isAsciiAlnum() = this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9'

/**
 * Mirrors zerocode's `canonical_wss_target` plus its gateway default: a bare host gets
 * port 9781, a `wss://` URL without a port means 443, the gateway defaults to https://host:42617.
 */
fun parseOidcTarget(
    host: String,
    alias: String,
    wssUrl: String? = null,
    gatewayUrl: String? = null,
): Result<OidcTarget> = runCatching {
    val a = normalizeAlias(alias)
    val explicit = wssUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseWss)
    if (explicit != null && host.isNotBlank()) {
        val typed = parseHost(host).host.removeSurrounding("[", "]")
        val inUrl = explicit.host.removeSurrounding("[", "]")
        if (!typed.equals(inUrl, ignoreCase = true)) {
            fail(OidcTargetException.Field.WssUrl, "The WSS URL points to $inUrl, not to $typed.")
        }
    }
    val wss = explicit ?: parseHost(host)
    val wssHost = wss.host
    val gateway = gatewayUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::parseGateway)
        ?: "https://${hostForUrl(wssHost)}:$DEFAULT_GATEWAY_PORT"
    val port = if (wss.port == -1) 443 else wss.port
    OidcTarget(a, "oidc.$a", wss.toString(), wssHost.removeSurrounding("[", "]"), port, gateway)
}

private fun hostForUrl(h: String) = if (h.contains(':') && !h.startsWith("[")) "[$h]" else h

private fun parseHost(raw: String): URI {
    val h = raw.trim()
    if (h.isEmpty() || h.any { it.isWhitespace() || it in "/@?#" }) {
        fail(OidcTargetException.Field.Host, "Enter the daemon's host name or IP address.")
    }
    val uri = runCatching { URI("wss://$h") }.getOrNull()
    if (uri?.host == null) fail(OidcTargetException.Field.Host, "Enter the daemon's host name or IP address.")
    val port = if (uri.port == -1) DEFAULT_WSS_PORT else uri.port
    return URI("wss", null, uri.host, port, "/", null, null)
}

private fun parseWss(raw: String): URI {
    val uri = runCatching { URI(raw) }.getOrNull()
    val valid = uri != null && uri.scheme.equals("wss", ignoreCase = true) && uri.host != null &&
        uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null
    if (!valid) fail(OidcTargetException.Field.WssUrl, "Use a wss:// URL without credentials, query or fragment.")
    val path = uri.rawPath?.takeIf { it.isNotEmpty() } ?: "/"
    return URI("wss", null, uri.host, uri.port, path, null, null)
}

private fun parseGateway(raw: String): String {
    val uri = runCatching { URI(raw) }.getOrNull()
    val scheme = uri?.scheme?.lowercase()
    val valid = uri != null && uri.host != null && uri.rawUserInfo == null && uri.rawQuery == null &&
        uri.rawFragment == null && (scheme == "https" || (scheme == "http" && uri.host.lowercase() in LOOPBACK))
    if (!valid) fail(OidcTargetException.Field.GatewayUrl, "Use an https:// gateway URL (plain http only on this phone's loopback).")
    return raw.trim().trimEnd('/')
}
