package pt.tiago.zerodroid.ui.canvas

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import pt.tiago.zerodroid.ui.theme.ZcTokens

/**
 * Shows one agent-written page. The page is untrusted: scripts and the network work (charts load
 * their libraries from CDNs), but it gets no file or content access, no bridge into the app, and
 * cannot navigate away. A link the user taps opens in the browser instead.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CanvasWebView(document: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                setBackgroundColor(ZcTokens.background.toArgb())
                // Seen frame by frame on the A26 (Android 16): the first time a WebView draws, the page's
                // background covers the whole window, top bar and status bar included, for a frame: a
                // flash on every open. Drawing into its own hardware layer keeps that draw inside the
                // view's bounds. It also stays hidden until its content is ready to paint.
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
                visibility = android.view.View.INVISIBLE
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    allowFileAccess = false
                    allowContentAccess = false
                    @Suppress("DEPRECATION")
                    allowFileAccessFromFileURLs = false
                    @Suppress("DEPRECATION")
                    allowUniversalAccessFromFileURLs = false
                    javaScriptCanOpenWindowsAutomatically = false
                    setSupportMultipleWindows(false)
                    setGeolocationEnabled(false)
                    // Pinch to zoom, without the old +/- buttons.
                    setSupportZoom(true)
                    builtInZoomControls = true
                    displayZoomControls = false
                }
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        val url = request.url
                        if (request.hasGesture() && request.isForMainFrame && (url.scheme == "https" || url.scheme == "http")) {
                            runCatching {
                                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url.toString())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }
                        return true
                    }

                    // POST navigations (form.submit()) never reach shouldOverrideUrlLoading: stop any
                    // page other than the frame itself and put the frame back.
                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        // Show once the content (not just the page background) is ready to draw.
                        view.postVisualStateCallback(0, object : WebView.VisualStateCallback() {
                            override fun onComplete(requestId: Long) {
                                reveal(view)
                            }
                        })
                    }

                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        if (url != null && url != "about:blank") {
                            view.stopLoading()
                            (view.tag as? String)?.let { view.loadDataWithBaseURL(null, it, "text/html", "utf-8", null) }
                        }
                    }
                }
                tag = document
                loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            if (view.tag != document) {
                view.tag = document
                view.animate().cancel()
                view.visibility = android.view.View.INVISIBLE
                view.loadDataWithBaseURL(null, document, "text/html", "utf-8", null)
            }
        },
        onRelease = {
            it.destroy()
            // Frames must not leave data for the next one: localStorage and the like go with the view.
            android.webkit.WebStorage.getInstance().deleteAllData()
        },
    )
}


/**
 * Fades the page in. Even once the content is ready, its first frame or two can be the page's
 * background alone or half-drawn tiles; a short fade turns that into a smooth entrance.
 */
private fun reveal(view: WebView) {
    view.alpha = 0f
    view.visibility = android.view.View.VISIBLE
    view.animate().alpha(1f).setStartDelay(REVEAL_DELAY_MS).setDuration(REVEAL_MS).start()
}

private const val REVEAL_DELAY_MS = 40L
private const val REVEAL_MS = 160L
