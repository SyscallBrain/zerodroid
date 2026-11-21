package pt.tiago.zerodroid.security

import java.security.MessageDigest

fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return buildString(64) { digest.forEach { append("%02x".format(it.toInt() and 0xff)) } }
}

fun caFingerprint(caChainPem: String): String {
    val body = caChainPem
        .substringAfter("-----BEGIN CERTIFICATE-----", "")
        .substringBefore("-----END CERTIFICATE-----", "")
        .filter { !it.isWhitespace() }
    require(body.isNotEmpty()) { "ca_chain_pem has no certificate" }
    return sha256Hex(java.util.Base64.getMimeDecoder().decode(body))
}

fun enrollmentSas(pairingCode: String, caFingerprintHex: String): String {
    val material = "zeroclaw-enroll-sas-v1\u0000" + pairingCode.trim() + "\u0000" + caFingerprintHex.trim().lowercase()
    val hex = sha256Hex(material.toByteArray(Charsets.UTF_8)).take(8).uppercase()
    return hex.substring(0, 4) + "-" + hex.substring(4, 8)
}
