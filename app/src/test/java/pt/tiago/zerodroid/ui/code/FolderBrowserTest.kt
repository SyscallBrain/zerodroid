package pt.tiago.zerodroid.ui.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.FsEntry
import pt.tiago.zerodroid.rpc.proto.FsListDirResult
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FolderBrowserTest {

    /** Each request waits until the test answers it. */
    private class FakeLister {
        val pending = mutableMapOf<Pair<String, Boolean>, CompletableDeferred<FsListDirResult>>()
        val calls = mutableListOf<Pair<String, Boolean>>()

        suspend fun list(path: String, hidden: Boolean): FsListDirResult {
            calls += path to hidden
            return pending.getOrPut(path to hidden) { CompletableDeferred() }.await()
        }

        fun answer(path: String, vararg names: String, hidden: Boolean = false, cwd: String = path) {
            val base = cwd.trimEnd('/')
            val entries = names.map { FsEntry(it, "$base/$it", isDir = true) }
            pending.getOrPut(path to hidden) { CompletableDeferred() }.complete(FsListDirResult(entries, cwd))
        }

        fun fail(path: String, hidden: Boolean = false) {
            pending.getOrPut(path to hidden) { CompletableDeferred() }.completeExceptionally(RuntimeException("denied"))
        }

        /** Lets the next request for this key wait again (for refreshes). */
        fun reset(path: String, hidden: Boolean = false) { pending.remove(path to hidden) }
    }

    private fun TestScope.browser(lister: FakeLister) =
        FolderBrowser(backgroundScope, lister::list, errorText = { it.message ?: "error" })

    private fun names(b: FolderBrowse?) = b!!.entries.map { it.name }

    @Test fun openShowsSkeletonAtOnce() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/")
        val s = fb.state.value!!
        assertTrue(s.loading); assertTrue(s.showSkeleton); assertTrue(s.entries.isEmpty())
    }

    @Test fun browseKeepsPreviousListing() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); runCurrent()
        val s = fb.state.value!!
        assertEquals("/", s.path); assertEquals(listOf("x"), names(s))
        assertTrue(s.loading); assertFalse(s.showSkeleton); assertFalse(s.canStart)
    }

    @Test fun startButtonStaysLitDuringQuickLoad() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); runCurrent()
        assertTrue(fb.state.value!!.startLooksEnabled)
        assertFalse(fb.state.value!!.canStart)
        advanceTimeBy(251); runCurrent()
        assertFalse(fb.state.value!!.startLooksEnabled)
        l.fail("/x"); runCurrent()
        assertFalse(fb.state.value!!.startLooksEnabled)
    }

    @Test fun skeletonAfterDelay() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); advanceTimeBy(249); runCurrent()
        assertFalse(fb.state.value!!.showSkeleton)
        advanceTimeBy(2); runCurrent()
        assertTrue(fb.state.value!!.showSkeleton)
    }

    @Test fun answerSwapsPathAndEntries() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); l.answer("/x", "y"); runCurrent()
        val s = fb.state.value!!
        assertEquals("/x", s.path); assertEquals(listOf("y"), names(s))
        assertFalse(s.loading); assertFalse(s.showSkeleton); assertTrue(s.canStart)
    }

    @Test fun showsDaemonCwd() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/x/"); l.answer("/x/", "y", cwd = "/x"); runCurrent()
        assertEquals("/x", fb.state.value!!.path)
    }

    @Test fun cachedFolderShowsAtOnce() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); l.answer("/x", "y"); runCurrent()
        l.reset("/")
        fb.browseTo("/"); runCurrent()
        val s = fb.state.value!!
        assertEquals("/", s.path); assertEquals(listOf("x"), names(s)); assertTrue(s.loading)
        assertFalse(s.showSkeleton)
        l.answer("/", "x", "z"); runCurrent()
        assertEquals(listOf("x", "z"), names(fb.state.value)); assertFalse(fb.state.value!!.loading)
    }

    @Test fun staleAnswerIsIgnored() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "a", "b"); runCurrent()
        fb.browseTo("/a"); runCurrent()
        fb.browseTo("/b"); runCurrent()
        l.answer("/b", "inB"); runCurrent()
        l.answer("/a", "inA"); runCurrent()
        val s = fb.state.value!!
        assertEquals("/b", s.path); assertEquals(listOf("inB"), names(s))
    }

    @Test fun hiddenHasOwnCache() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.toggleHidden(); runCurrent()
        val s = fb.state.value!!
        assertTrue(s.showHidden); assertTrue(s.loading); assertEquals(listOf("x"), names(s))
        l.answer("/", ".git", "x", hidden = true); runCurrent()
        assertEquals(listOf(".git", "x"), names(fb.state.value)); assertFalse(fb.state.value!!.loading)
    }

    @Test fun errorClearsEntries() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); l.fail("/x"); runCurrent()
        val s = fb.state.value!!
        assertNotNull(s.error); assertTrue(s.entries.isEmpty()); assertEquals("/x", s.path)
        assertFalse(s.canStart); assertFalse(s.loading); assertFalse(s.showSkeleton)
    }

    @Test fun errorOnCachedFolderClearsEntries() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.browseTo("/x"); l.answer("/x", "y"); runCurrent()
        fb.up(); runCurrent()
        l.reset("/x")
        fb.browseTo("/x"); runCurrent()
        assertEquals(listOf("y"), names(fb.state.value)) // cached listing shown at once
        l.fail("/x"); runCurrent()
        val s = fb.state.value!!
        assertNotNull(s.error); assertTrue(s.entries.isEmpty()); assertFalse(s.canStart)
    }

    @Test fun upGoesToParent() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/x"); l.answer("/x", "y"); runCurrent()
        fb.up(); l.answer("/", "x"); runCurrent()
        assertEquals("/", fb.state.value!!.path)
    }

    @Test fun closeDropsCache() = runTest {
        val l = FakeLister(); val fb = browser(l)
        fb.open("a", "/"); l.answer("/", "x"); runCurrent()
        fb.close()
        assertNull(fb.state.value)
        l.reset("/")
        fb.open("a", "/"); runCurrent()
        assertTrue(fb.state.value!!.showSkeleton); assertTrue(fb.state.value!!.entries.isEmpty())
    }
}
