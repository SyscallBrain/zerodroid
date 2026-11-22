package pt.tiago.zerodroid.data.canvas

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CanvasArchiveTest {
    @get:Rule val tmp = TemporaryFolder()

    private var tick = 0L
    private fun TestScope.archive() = CanvasArchive(tmp.root, backgroundScope, clock = { ++tick })

    private fun CanvasArchive.render(callId: String, canvasId: String, content: String = callId, session: String = "s") =
        onCapture(session, "main", CanvasCapture.Render(callId, CanvasRender(canvasId, "html", content)))

    @Test
    fun recordsAndLists() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.render("a2", "c")
        a.render("a3", "d")
        a.awaitIdle()
        assertEquals(listOf("d", "c"), a.canvases.value.map { it.canvasId })
        assertEquals(2, a.canvases.value.single { it.canvasId == "c" }.frameCount)
        assertEquals(listOf("a1", "a2"), a.frames("c").map { it.content })
    }

    @Test
    fun dedupsSameCall() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.render("a1", "c")
        a.awaitIdle()
        assertEquals(1, a.canvases.value.single().frameCount)
    }

    @Test
    fun failedRemovesFrameAndEmptyCanvas() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.onCapture("s", "main", CanvasCapture.Failed("a1"))
        a.awaitIdle()
        assertTrue(a.canvases.value.isEmpty())
        assertTrue(a.frames("c").isEmpty())
    }

    @Test
    fun keepsFiftyNewest() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        (1..51).forEach { a.render("a$it", "c", "$it") }
        a.awaitIdle()
        assertEquals(50, a.canvases.value.single().frameCount)
        assertEquals("2", a.frames("c").first().content)
    }

    @Test
    fun survivesReload() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.awaitIdle()
        a.rename("c", "Sales")
        val b = archive().apply { bind("ca:A") }
        assertEquals(a.canvases.value, b.canvases.value)
        assertEquals("Sales", b.canvases.value.single().name)
    }

    @Test
    fun daemonsDoNotMix() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "default")
        a.awaitIdle()
        a.bind("ca:B")
        assertTrue(a.canvases.value.isEmpty())
        a.bind("ca:A")
        assertEquals(1, a.canvases.value.size)
    }

    @Test
    fun skipsCorruptFile() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.awaitIdle()
        val dir = tmp.root.listFiles()!!.single { it.isDirectory }
        java.io.File(dir, "broken.json").writeText("{not json")
        val b = archive().apply { bind("ca:A") }
        assertEquals(listOf("c"), b.canvases.value.map { it.canvasId })
    }

    @Test
    fun locateFindsFrame() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.awaitIdle()
        assertEquals("c" to "s/a1", a.locate("s", "a1"))
        a.delete("c")
        assertNull(a.locate("s", "a1"))
        assertTrue(a.canvases.value.isEmpty())
    }

    @Test
    fun deletedCanvasStaysDeletedAfterRecapture() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.awaitIdle()
        a.delete("c")
        a.render("a1", "c")
        a.awaitIdle()
        assertTrue(a.canvases.value.isEmpty())
        val b = archive().apply { bind("ca:A") }
        b.render("a1", "c")
        b.awaitIdle()
        assertTrue(b.canvases.value.isEmpty())
    }

    @Test
    fun evictedFramesAreNotReAdded() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        (1..51).forEach { a.render("a$it", "c", "$it") }
        a.awaitIdle()
        a.render("a1", "c", "1")
        a.awaitIdle()
        val frames = a.frames("c")
        assertEquals(50, frames.size)
        assertEquals("51", frames.last().content)
    }

    @Test
    fun failedRenderIsNotReAddedOnReload() = runTest(UnconfinedTestDispatcher()) {
        val a = archive().apply { bind("ca:A") }
        a.render("a1", "c")
        a.onCapture("s", "main", CanvasCapture.Failed("a1"))
        a.render("a1", "c")
        a.awaitIdle()
        assertTrue(a.canvases.value.isEmpty())
    }

    @Test
    fun writeErrorDoesNotStopTheArchive() = runTest(UnconfinedTestDispatcher()) {
        val root = tmp.newFile("not-a-dir")
        val a = CanvasArchive(root, backgroundScope, clock = { ++tick }).apply { bind("ca:A") }
        a.render("a1", "c")
        a.awaitIdle()
        a.rename("c", "x")
        a.delete("c")
        assertTrue(a.canvases.value.isEmpty())
    }
}
