package pt.tiago.zerodroid.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModelStripTest {
    @Test fun `drops the provider prefix from a routed model id`() {
        assertEquals("deepseek-v3.2", shortModelName("deepseek/deepseek-v3.2"))
        assertEquals("llama-3.3-70b", shortModelName("openrouter/meta-llama/llama-3.3-70b"))
    }

    @Test fun `keeps plain names and degenerate ids untouched`() {
        assertEquals("claude-opus-4-5", shortModelName("claude-opus-4-5"))
        assertEquals("default model", shortModelName("default model"))
        assertEquals("weird/", shortModelName("weird/"))
    }

    @Test fun `context fraction is clamped and null without data`() {
        assertEquals(0.03f, contextFraction(3_000, 100_000)!!, 1e-6f)
        assertEquals(1f, contextFraction(150, 100)!!, 0f)
        assertEquals(0f, contextFraction(-5, 100)!!, 0f)
        assertNull(contextFraction(null, 100))
        assertNull(contextFraction(10, null))
        assertNull(contextFraction(10, 0))
    }
}
