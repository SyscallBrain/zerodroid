package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pt.tiago.zerodroid.ui.theme.ZcMonoFamily
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Markdown blocks the chat renders. Unknown syntax falls through as plain text. */
sealed interface MdBlock {
    data class Paragraph(val text: String) : MdBlock
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Code(val lang: String, val code: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val depth: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data object Rule : MdBlock
}

private val bulletRe = Regex("""^(\s*)([-*+]|\d{1,3}[.)])\s+(.*)$""")
private val headingRe = Regex("""^(#{1,6})\s+(.*)$""")

/** Line-based parser; an unclosed fence (mid-stream) renders as code so far. */
fun parseMarkdown(src: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
        para.clear()
    }
    val lines = src.replace("\r\n", "\n").split('\n')
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val trimmed = line.trimStart()
        when {
            trimmed.startsWith("```") -> {
                flush()
                val lang = trimmed.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.append(lines[i]).append('\n')
                    i++
                }
                out += MdBlock.Code(lang, code.toString().trimEnd('\n'))
            }
            trimmed.isEmpty() -> flush()
            headingRe.matches(trimmed) -> {
                flush()
                val m = headingRe.find(trimmed)!!
                out += MdBlock.Heading(m.groupValues[1].length, m.groupValues[2])
            }
            trimmed == "---" || trimmed == "***" -> { flush(); out += MdBlock.Rule }
            trimmed.startsWith(">") -> { flush(); out += MdBlock.Quote(trimmed.removePrefix(">").trim()) }
            bulletRe.matches(line) -> {
                flush()
                val m = bulletRe.find(line)!!
                val marker = m.groupValues[2].let { if (it.first().isDigit()) it else "•" }
                out += MdBlock.Bullet(marker, m.groupValues[3], m.groupValues[1].length / 2)
            }
            else -> {
                if (para.isNotEmpty()) para.append('\n')
                para.append(line)
            }
        }
        i++
    }
    flush()
    return out
}

private val inlineRe = Regex("""(`[^`]+`)|(\*\*[^*]+\*\*)|(__[^_]+__)|(\*[^*\s][^*]*\*)|(_[^_\s][^_]*_)|(\[[^\]]+\]\([^)]+\))""")

/** Inline spans: `code`, **bold**, *italic*, [links](url). */
private val InlineCodeBg = androidx.compose.ui.graphics.Color.White.copy(alpha = 0.07f)

fun inlineMarkdown(text: String): AnnotatedString = buildAnnotatedString {
    var last = 0
    for (m in inlineRe.findAll(text)) {
        append(text.substring(last, m.range.first))
        val t = m.value
        when {
            // Translucent, not ZcTokens.raised: a span background is painted over the selection
            // highlight, so an opaque one hid any selection across inline code. White at 7 % on the
            // chat background looks the same as `raised`.
            t.startsWith("`") -> withStyle(SpanStyle(fontFamily = ZcMonoFamily, fontSize = 13.5.sp, background = InlineCodeBg, color = ZcTokens.accentStart)) {
                append(" ${t.trim('`')} ")
            }
            t.startsWith("**") || t.startsWith("__") -> withStyle(SpanStyle(fontWeight = FontWeight(650))) { append(t.substring(2, t.length - 2)) }
            t.startsWith("[") -> {
                val label = t.substring(1, t.indexOf(']'))
                withStyle(SpanStyle(color = ZcTokens.accentStart, textDecoration = TextDecoration.None)) { append(label) }
            }
            else -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(t.substring(1, t.length - 1)) }
        }
        last = m.range.last + 1
    }
    append(text.substring(last))
}

@Composable
fun MarkdownText(markdown: String, modifier: Modifier = Modifier, style: TextStyle = ZcType.body) {
    pt.tiago.zerodroid.RecomposeProbe("MarkdownText")
    val blocks = remember(markdown) { pt.tiago.zerodroid.Perf.span("md.parse") { parseMarkdown(markdown) } }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { b ->
                when (b) {
                    is MdBlock.Paragraph -> Text(inlineMarkdown(b.text), style = style)
                    is MdBlock.Heading -> Text(
                        inlineMarkdown(b.text),
                        style = (if (b.level <= 2) ZcType.title else ZcType.heading).copy(fontSize = if (b.level == 1) 19.sp else if (b.level == 2) 17.sp else 15.sp),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    is MdBlock.Bullet -> Row(Modifier.padding(start = (b.depth * 14).dp)) {
                        Text(b.marker, style = style.copy(color = ZcTokens.muted), modifier = Modifier.widthIn(min = 16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(inlineMarkdown(b.text), style = style)
                    }
                    is MdBlock.Quote -> Row(Modifier.height(IntrinsicSize.Min)) {
                        Box(Modifier.width(3.dp).fillMaxHeight().background(ZcTokens.hairlineStrong, RoundedCornerShape(2.dp)))
                        Spacer(Modifier.width(10.dp))
                        Text(inlineMarkdown(b.text), style = style.copy(color = ZcTokens.muted))
                    }
                    is MdBlock.Code -> CodeBlock(b.code)
                    MdBlock.Rule -> Box(Modifier.padding(vertical = 4.dp).fillMaxWidth().height(1.dp).background(ZcTokens.hairlineStrong))
                }
            }
        }
    }
}

/** Monospace block on `background`, horizontally scrollable, hairline border. */
@Composable
fun CodeBlock(code: String, modifier: Modifier = Modifier, maxLines: Int = Int.MAX_VALUE) {
    val shape = RoundedCornerShape(ZcTokens.chipRadius)
    Box(
        modifier
            .fillMaxWidth()
            .background(ZcTokens.background, shape)
            .border(1.dp, ZcTokens.hairline, shape)
            .horizontalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(code, style = ZcType.mono.copy(fontSize = 12.5.sp), maxLines = maxLines, softWrap = false)
    }
}
