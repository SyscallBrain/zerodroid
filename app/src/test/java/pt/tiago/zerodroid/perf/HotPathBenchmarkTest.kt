package pt.tiago.zerodroid.perf

import org.junit.Test
import pt.tiago.zerodroid.data.ChatState
import pt.tiago.zerodroid.data.localPrompt
import pt.tiago.zerodroid.data.reduce
import pt.tiago.zerodroid.rpc.proto.SessionUpdate
import pt.tiago.zerodroid.ui.kit.inlineMarkdown
import pt.tiago.zerodroid.ui.kit.parseMarkdown

/**
 * JVM micro-benchmarks of code that runs on the UI path. Host numbers are a floor: a mid-range
 * phone is several times slower, so anything above ~1 ms here is worth a look on a 120 Hz screen
 * (8.3 ms per frame for everything).
 */
class HotPathBenchmarkTest {
    private inline fun bench(name: String, iterations: Int, block: () -> Unit) {
        repeat(iterations / 2 + 5) { block() } // warm-up for the JIT
        val samples = DoubleArray(iterations)
        for (i in 0 until iterations) {
            val t = System.nanoTime()
            block()
            samples[i] = (System.nanoTime() - t) / 1e6
        }
        samples.sort()
        println(
            "BENCH %-48s p50 %8.3f ms  p90 %8.3f ms  max %8.3f ms".format(
                name, samples[iterations / 2], samples[(iterations * 9) / 10], samples.last(),
            ),
        )
    }

    private val paragraph = "This is **bold** and *italic* with `code` and a [link](https://x.y). " +
        "Some more words to make the line longer, like a real agent answer would be. "

    private fun reply(chars: Int): String = buildString {
        var i = 0
        while (length < chars) {
            when (i % 7) {
                0 -> append("## Heading $i\n\n")
                3 -> append("- item one with `code`\n- item two **bold**\n  - nested *it*\n\n")
                5 -> append("```kotlin\nval x = listOf(1, 2, 3).map { it * 2 }\nprintln(x)\n```\n\n")
                else -> append(paragraph.repeat(3)).append("\n\n")
            }
            i++
        }
    }

    @Test
    fun markdownParse() {
        for (size in listOf(500, 4_000, 20_000)) {
            val md = reply(size)
            bench("parseMarkdown ${size}B", 200) { parseMarkdown(md) }
            bench("parse+inline (all blocks) ${size}B", 200) {
                parseMarkdown(md).forEach { b ->
                    when (b) {
                        is pt.tiago.zerodroid.ui.kit.MdBlock.Paragraph -> inlineMarkdown(b.text)
                        is pt.tiago.zerodroid.ui.kit.MdBlock.Bullet -> inlineMarkdown(b.text)
                        is pt.tiago.zerodroid.ui.kit.MdBlock.Heading -> inlineMarkdown(b.text)
                        else -> Unit
                    }
                }
            }
        }
    }

    /** A streamed reply re-parses the whole text on every chunk: total cost over the stream. */
    @Test
    fun streamingReparseCost() {
        val full = reply(8_000)
        val chunks = full.chunked(12) // ~12 chars per token chunk
        bench("stream 8KB in ${chunks.size} chunks, reparse each", 10) {
            val sb = StringBuilder()
            for (c in chunks) {
                sb.append(c)
                parseMarkdown(sb.toString())
            }
        }
    }

    @Test
    fun reducerStreamingIntoLongSession() {
        var base = localPrompt(ChatState(), "hi")
        // A long session: 400 earlier items.
        repeat(200) {
            base = reduce(base, SessionUpdate.AgentMessageChunk("s", paragraph), 0)
            base = localPrompt(base, "next")
        }
        val chunks = reply(8_000).chunked(12).map { SessionUpdate.AgentMessageChunk("s", it) }
        bench("reduce ${chunks.size} chunks into ${base.items.size}-item session", 20) {
            var s = base
            for (c in chunks) s = reduce(s, c, 0)
        }
        bench("reduce 1 chunk into ${base.items.size}-item session", 2_000) {
            reduce(base, chunks[0], 0)
        }
    }
}
