package pt.tiago.zerodroid.enroll

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.TlsVersion
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.zerodroid.security.TlsFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

@Serializable
data class EnrollResponse(
    @SerialName("cert_pem") val certPem: String,
    @SerialName("ca_chain_pem") val caChainPem: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("not_after") val notAfter: Long,
)

class EnrollHttpException(val status: Int, message: String, val retryAfter: Long? = null) : Exception(message)

interface EnrollHttp {
    suspend fun fetchCa(host: String, port: Int): String
    suspend fun postEnroll(host: String, port: Int, pinnedCaPem: String, pairingCode: String, csrPem: String): EnrollResponse
}

/** Provisional trust-all manager used only for GET /enroll/ca. The CA is then pinned by fingerprint. */
private class ProvisionalTrustManager : X509TrustManager {
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Decode the PEM string from the /enroll/ca JSON body without keeping JSON escapes. */
internal fun parseCaChainPem(body: String): String =
    jsonResponse.parseToJsonElement(body).jsonObject["ca_chain_pem"]?.jsonPrimitive?.content
        ?: throw EnrollHttpException(0, "enroll CA response has no ca_chain_pem")

private val jsonResponse = Json { ignoreUnknownKeys = true }

class OkHttpEnrollHttp : EnrollHttp {
    private val json = Json { ignoreUnknownKeys = true }

    private fun client(tm: X509TrustManager): OkHttpClient {
        val ssl = SSLContext.getInstance("TLSv1.3").apply { init(null, arrayOf(tm), null) }
        return OkHttpClient.Builder()
            .sslSocketFactory(ssl.socketFactory, tm)
            .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
            .build()
    }

    private fun check(response: okhttp3.Response): okhttp3.Response {
        if (!response.isSuccessful) {
            val retry = response.header("Retry-After")?.toLongOrNull()
            val body = response.body?.string().orEmpty()
            val detail = runCatching {
                json.parseToJsonElement(body).jsonObject["error"]?.toString()?.trim('"')
            }.getOrNull()?.takeIf { it.isNotBlank() && it.length < 200 }
            val message = when (response.code) {
                401 -> "invalid or expired pairing code"
                403 -> "pairing is locked out; try again later"
                429 -> "too many attempts${retry?.let { "; retry after ${it}s" } ?: ""}"
                else -> "enrollment HTTP ${response.code}"
            } + detail?.let { ": $it" }.orEmpty()
            throw EnrollHttpException(response.code, message, retry)
        }
        return response
    }

    override suspend fun fetchCa(host: String, port: Int): String = withContext(Dispatchers.IO) {
        val tm = ProvisionalTrustManager()
        val request = Request.Builder().url("https://$host:$port/enroll/ca").get().build()
        client(tm).newCall(request).execute().use { response ->
            val body = check(response).body?.string().orEmpty()
            return@withContext parseCaChainPem(body)
        }
    }

    override suspend fun postEnroll(
        host: String, port: Int, pinnedCaPem: String, pairingCode: String, csrPem: String,
    ): EnrollResponse = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("pairing_code", pairingCode)
            put("csr_pem", csrPem)
        }.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url("https://$host:$port/enroll").post(body).build()
        val tm = TlsFactory.pinnedTrustManager(pinnedCaPem)
        client(tm).newCall(request).execute().use { response ->
            return@withContext json.decodeFromString(EnrollResponse.serializer(), check(response).body?.string().orEmpty())
        }
    }
}
