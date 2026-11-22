package pt.tiago.zerodroid.enroll

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** RFC 7636 S256 PKCE. The verifier lives only in memory for one device grant. */
object Pkce {
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    fun newVerifier(random: SecureRandom = SecureRandom()): String =
        b64.encodeToString(ByteArray(32).also(random::nextBytes))

    fun challenge(verifier: String): String =
        b64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
}
