package pt.tiago.zerodroid.rpc

import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.TlsVersion
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Close code to echo back. 1005/1006/1015 are reserved (never sent on the wire), and
 * OkHttp throws if asked to send them; a peer that closes without a code gets 1000.
 */
fun replyCloseCode(received: Int): Int = if (received in setOf(1005, 1006, 1015)) 1000 else received

class OkHttpWsTransport(
    private val sslContext: SSLContext,
    private val trustManager: X509TrustManager,
    private val onFailureDetail: ((String) -> Unit)? = null,
) : WsTransport {
    private val client = OkHttpClient.Builder()
        .sslSocketFactory(sslContext.socketFactory, trustManager)
        .connectionSpecs(listOf(ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS).tlsVersions(TlsVersion.TLS_1_3).build()))
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    override fun open(url: String, listener: WsListener): WsHandle {
        var socket: WebSocket? = null
        val request = Request.Builder().url(url).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = listener.onOpen()
            override fun onMessage(webSocket: WebSocket, text: String) = listener.onText(text)
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                runCatching { webSocket.close(replyCloseCode(code), null) }
                listener.onClosed(code, reason)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val msg = buildString {
                    append("url=$url\n")
                    append("error=${t.javaClass.name}: ${t.message}\n")
                    append(t.stackTraceToString().take(2000))
                    var c = t.cause
                    while (c != null) { append("\ncause=${c.javaClass.name}: ${c.message}"); c = c.cause }
                }
                onFailureDetail?.invoke(msg)
                listener.onFailure(t)
            }
        })
        return object : WsHandle {
            override fun send(text: String): Boolean = socket.send(text)
            override fun close(code: Int, reason: String) { socket.close(code, reason) }
        }
    }
}
