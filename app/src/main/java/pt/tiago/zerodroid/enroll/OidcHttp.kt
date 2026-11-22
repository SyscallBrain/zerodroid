package pt.tiago.zerodroid.enroll

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import pt.tiago.zerodroid.security.sha256Hex
import java.net.URI
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/** A gateway origin (with any path prefix) and, if the user trusted an untrusted certificate, its leaf pin. */
data class GatewaySpec(val url: String, val pinnedLeafSha256: String?)

sealed interface GatewayProbe {
    /** TLS verified; [providers] are the OIDC aliases the daemon offers. */
    data class Trusted(val providers: List<String>) : GatewayProbe
    /** TLS did not verify (or the pin did not match): ask the user about this exact leaf. */
    data class Untrusted(val host: String, val leafSha256: String) : GatewayProbe
}

@Serializable
data class DeviceStart(
    @SerialName("device_code") val deviceCode: String,
    @SerialName("user_code") val userCode: String,
    @SerialName("verification_uri") val verificationUri: String,
    @SerialName("verification_uri_complete") val verificationUriComplete: String? = null,
    @SerialName("expires_in") val expiresIn: Long,
    val interval: Long = 5,
) {
    override fun toString() =
        "DeviceStart(deviceCode=***, userCode=$userCode, verificationUri=$verificationUri, expiresIn=$expiresIn, interval=$interval)"
}

sealed interface PollOutcome {
    data object Pending : PollOutcome
    data object SlowDown : PollOutcome
    data class Granted(val accessToken: String, val expiresIn: Long?) : PollOutcome {
        override fun toString() = "Granted(accessToken=***, expiresIn=$expiresIn)"
    }
    /** The identity provider refused (403): final. */
    data class Denied(val message: String) : PollOutcome
    /** The gateway could not reach the identity provider (502): retry while the grant lives. */
    data class Retry(val message: String) : PollOutcome
    data class RateLimited(val retryAfter: Long?) : PollOutcome
}

interface OidcHttp {
    suspend fun probe(gw: GatewaySpec): GatewayProbe
    suspend fun deviceStart(gw: GatewaySpec, alias: String, codeChallenge: String): DeviceStart
    suspend fun devicePoll(gw: GatewaySpec, alias: String, deviceCode: String, codeVerifier: String): PollOutcome
    suspend fun certificate(gw: GatewaySpec, alias: String, accessToken: String, csrPem: String): EnrollResponse
}

private val oidcJson = Json { ignoreUnknownKeys = true }

private fun errorField(body: String): String? = runCatching {
    oidcJson.parseToJsonElement(body).jsonObject["error"]?.jsonPrimitive?.content
}.getOrNull()?.takeIf { it.isNotBlank() && it.length < 300 }

internal fun parseDeviceStart(body: String): DeviceStart = oidcJson.decodeFromString(DeviceStart.serializer(), body)

internal fun parsePollResponse(status: Int, body: String, retryAfter: Long?): PollOutcome = when (status) {
    200 -> {
        val o = oidcJson.parseToJsonElement(body).jsonObject
        when (o["status"]?.jsonPrimitive?.content) {
            "pending" -> PollOutcome.Pending
            "slow_down" -> PollOutcome.SlowDown
            "granted" -> {
                val token = o["token"]?.jsonObject
                val at = token?.get("access_token")?.jsonPrimitive?.content
                    ?: throw EnrollHttpException(status, "the gateway granted the sign-in but sent no access token")
                PollOutcome.Granted(at, token["expires_in"]?.jsonPrimitive?.longOrNull)
            }
            else -> throw EnrollHttpException(status, "unexpected device poll status")
        }
    }
    403 -> PollOutcome.Denied(errorField(body) ?: "The sign-in was refused.")
    502 -> PollOutcome.Retry(errorField(body) ?: "The gateway could not reach the identity provider.")
    // The gateway's contract: 429 and 503 (with Retry-After) are retryable while the code lives.
    429, 503 -> PollOutcome.RateLimited(retryAfter)
    else -> throw EnrollHttpException(status, errorField(body) ?: "gateway HTTP $status")
}

/**
 * Whether a failed TLS handshake is a question for the user. Only a certificate Android does not
 * trust (and that is not the one already pinned) is; a reset or protocol error is just an error.
 */
internal fun tlsFailureNeedsTrust(leafSha256: String?, systemTrustsChain: Boolean, pin: String?): Boolean =
    leafSha256 != null && !systemTrustsChain && !leafSha256.equals(pin, ignoreCase = true)

/** Accepts exactly one server certificate: the leaf whose DER SHA-256 the user confirmed. */
internal class LeafPinTrustManager(private val expectedSha256: String) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("server only")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val leaf = chain.firstOrNull() ?: throw CertificateException("empty certificate chain")
        if (!sha256Hex(leaf.encoded).equals(expectedSha256, ignoreCase = true)) {
            throw CertificateException("gateway certificate does not match the trusted fingerprint")
        }
    }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Accepts anything and remembers the leaf: used only to show the user what they would trust. */
private class CapturingTrustManager : X509TrustManager {
    @Volatile var chain: Array<X509Certificate> = emptyArray()
    @Volatile var authType: String = "UNKNOWN"
    val leaf: X509Certificate? get() = chain.firstOrNull()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) { this.chain = chain; this.authType = authType }
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun systemTrustManager(): X509TrustManager =
    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        .apply { init(null as KeyStore?) }
        .trustManagers.filterIsInstance<X509TrustManager>().first()

class OkHttpOidcHttp : OidcHttp {
    private val jsonType = "application/json".toMediaType()

    private fun client(tm: X509TrustManager, anyHost: Boolean): OkHttpClient {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .apply { if (anyHost) hostnameVerifier { _, _ -> true } }
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** With a pin the fingerprint identifies the certificate, so the host name is not checked again. */
    private fun clientFor(gw: GatewaySpec): OkHttpClient =
        gw.pinnedLeafSha256?.let { client(LeafPinTrustManager(it), anyHost = true) }
            ?: client(systemTrustManager(), anyHost = false)

    private fun url(gw: GatewaySpec, path: String) = gw.url.trimEnd('/') + path

    override suspend fun probe(gw: GatewaySpec): GatewayProbe = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url(gw, "/api/oidc/providers")).get().build()
        try {
            clientFor(gw).newCall(request).execute().use { r ->
                val body = r.body.string()
                if (!r.isSuccessful) throw EnrollHttpException(r.code, errorField(body) ?: "gateway HTTP ${r.code}")
                val providers = oidcJson.parseToJsonElement(body).jsonObject["providers"]?.jsonArray.orEmpty()
                    .mapNotNull { it.jsonObject["alias"]?.jsonPrimitive?.content }
                GatewayProbe.Trusted(providers)
            }
        } catch (e: SSLException) {
            // Only the certificate is read on this second connection; no credential is sent.
            val capture = CapturingTrustManager()
            runCatching { client(capture, anyHost = true).newCall(request).execute().close() }
            val leafSha = capture.leaf?.let { sha256Hex(it.encoded) }
            val systemTrusts = capture.chain.isNotEmpty() &&
                runCatching { systemTrustManager().checkServerTrusted(capture.chain, capture.authType) }.isSuccess
            if (!tlsFailureNeedsTrust(leafSha, systemTrusts, gw.pinnedLeafSha256)) throw e
            GatewayProbe.Untrusted(URI(gw.url).host, leafSha!!)
        }
    }

    override suspend fun deviceStart(gw: GatewaySpec, alias: String, codeChallenge: String): DeviceStart =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("code_challenge", codeChallenge) }.toString().toRequestBody(jsonType)
            val request = Request.Builder().url(url(gw, "/api/oidc/$alias/device/start")).post(body).build()
            clientFor(gw).newCall(request).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) throw EnrollHttpException(r.code, errorField(text) ?: "gateway HTTP ${r.code}", r.header("Retry-After")?.toLongOrNull())
                parseDeviceStart(text)
            }
        }

    override suspend fun devicePoll(gw: GatewaySpec, alias: String, deviceCode: String, codeVerifier: String): PollOutcome =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("device_code", deviceCode)
                put("code_verifier", codeVerifier)
            }.toString().toRequestBody(jsonType)
            val request = Request.Builder().url(url(gw, "/api/oidc/$alias/device/poll")).post(body).build()
            clientFor(gw).newCall(request).execute().use { r ->
                parsePollResponse(r.code, r.body.string(), r.header("Retry-After")?.toLongOrNull())
            }
        }

    override suspend fun certificate(gw: GatewaySpec, alias: String, accessToken: String, csrPem: String): EnrollResponse =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject { put("csr_pem", csrPem) }.toString().toRequestBody(jsonType)
            val request = Request.Builder().url(url(gw, "/api/oidc/$alias/certificate"))
                .header("Authorization", "Bearer $accessToken").post(body).build()
            clientFor(gw).newCall(request).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) throw EnrollHttpException(r.code, errorField(text) ?: "gateway HTTP ${r.code}", r.header("Retry-After")?.toLongOrNull())
                oidcJson.decodeFromString(EnrollResponse.serializer(), text)
            }
        }
}
