package pt.tiago.zerodroid.ui.canvas

/**
 * The page a WebView shows for a canvas frame, or null when the frame is drawn natively
 * (`markdown`, `text`). A full HTML document is shown as the agent wrote it; a fragment or an
 * SVG gets a page sized for the phone, on [background] (a CSS colour). Every page gets
 * [FIT_SCRIPT], which zooms it out when its content is wider than the phone.
 */
fun frameDocument(contentType: String, content: String, background: String): String? = when (contentType) {
    "html" -> if (isFullDocument(content)) withDarkScheme(content) else page(content, background, "")
    "svg" -> page("<div style=\"display:flex;justify-content:center\">$content</div>", background, "svg{max-width:100%;height:auto}")
    else -> null
}

/** True when [content] starts as a whole HTML document (doctype or `<html>`), not a fragment. */
fun isFullDocument(content: String): Boolean {
    val head = content.trimStart().take(15).lowercase()
    return head.startsWith("<!doctype") || head.startsWith("<html")
}

private const val DARK_SCHEME = "<meta name=\"color-scheme\" content=\"dark\">"
private const val VIEWPORT = "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
private val HAS_VIEWPORT = Regex("<meta[^>]+name\\s*=\\s*[\"']?viewport", RegexOption.IGNORE_CASE)

/**
 * Agents write charts for a desktop browser: an SVG or canvas of fixed width (440 px and up) in a
 * centred card, wider than a phone. Centred overflow spills off both sides and the left part
 * cannot even be scrolled to. This measures the page's narrowest possible layout (text wraps,
 * fixed-width things do not) and, when that is wider than the screen, zooms the page out to fit.
 * Runs again on load (late images, scripts drawing charts) and on resize.
 */
const val FIT_SCRIPT = "<script>(function(){" +
    "function fit(){var d=document.documentElement;if(!d)return;d.style.zoom='';" +
    "var w=d.style.width;d.style.width='min-content';var need=Math.ceil(d.getBoundingClientRect().width);d.style.width=w;" +
    "var vw=window.innerWidth;if(vw>0&&need>vw+1)d.style.zoom=String(vw/need);}" +
    "if(document.readyState==='loading')addEventListener('DOMContentLoaded',fit);else fit();" +
    "addEventListener('load',fit);addEventListener('resize',fit);" +
    "})();</script>"
private val HEAD = Regex("<head(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val HTML = Regex("<html(\\s[^>]*)?>", RegexOption.IGNORE_CASE)
private val DOCTYPE = Regex("<!doctype[^>]*>", RegexOption.IGNORE_CASE)

/**
 * A whole document keeps its own styles; the dark colour scheme only changes the browser's
 * defaults, so one written without styles is light text on the dark page, not black on black.
 * Inserted after `<head>` (or `<html>`), so a doctype stays first and the page stays in standards
 * mode; with it a phone viewport when the page has none, and [FIT_SCRIPT].
 */
private fun withDarkScheme(doc: String): String {
    val inject = DARK_SCHEME + (if (HAS_VIEWPORT.containsMatchIn(doc)) "" else VIEWPORT) + FIT_SCRIPT
    val m = HEAD.find(doc) ?: HTML.find(doc) ?: DOCTYPE.find(doc) ?: return inject + doc
    return doc.substring(0, m.range.last + 1) + inject + doc.substring(m.range.last + 1)
}

private fun page(body: String, background: String, extraCss: String) =
    "<!DOCTYPE html><html><head><meta charset=\"utf-8\">" +
        VIEWPORT + FIT_SCRIPT +
        "<style>html,body{margin:0;background:$background;color:#e6e6e6}$extraCss</style>" +
        "</head><body>$body</body></html>"
