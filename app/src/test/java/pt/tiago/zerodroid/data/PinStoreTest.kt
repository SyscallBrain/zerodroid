package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinStoreTest {
    private class Fake(var saved: List<String> = emptyList()) : PinPersistence {
        override suspend fun load() = saved
        override suspend fun save(p: List<String>) { saved = p }
    }

    @Test
    fun toggleAddsAndRemoves() = runTest {
        val f = Fake()
        val s = PinStore(f)
        s.toggle("coder")
        assertTrue(s.isPinned("coder"))
        assertEquals(listOf("coder"), f.saved)
        s.toggle("coder")
        assertFalse(s.isPinned("coder"))
        assertEquals(emptyList<String>(), f.saved)
    }

    @Test
    fun orderPinnedPutsPinnedFirstInPinOrder() {
        assertEquals(listOf("c", "a", "b", "d"), orderPinned(listOf("a", "b", "c", "d"), listOf("c", "zzz", "a")) { it })
    }

    @Test
    fun pinsPersistThroughLoad() = runTest {
        val s = PinStore(Fake(listOf("news", "coder")))
        s.load()
        assertEquals(listOf("news", "coder"), s.pins.value)
    }
}
