package pt.tiago.zerodroid.ui.canvas

/**
 * The page a WebView shows for a canvas frame, or null when the frame is drawn natively
 * (`markdown`, `text`). A full HTML document is shown as the agent wrote it; a fragment or an
 * SVG gets a page sized for the phone, on [background] (a CSS colour).
 */
fun frameDocument(contentType: String, content: String, background: String): String? = when (contentType) {
    "html" -> if (isFullDocument(content)) content else page(content, background, "")
    "svg" -> page("<div style=\"display:flex;justify-content:center\">$content</div>", background, "svg{max-width:100%;height:auto}")
    else -> null
}

/** True when [content] starts as a whole HTML document (doctype or `<html>`), not a fragment. */
fun isFullDocument(content: String): Boolean {
    val head = content.trimStart().take(15).lowercase()
    return head.startsWith("<!doctype") || head.startsWith("<html")
}

private fun page(body: String, background: String, extraCss: String) =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
        "<style>html,body{margin:0;background:$background;color:#e6e6e6}$extraCss</style>" +
        "</head><body>$body</body></html>"
