package pt.tiago.zerodroid.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test
import pt.tiago.zerodroid.data.ChatItem
import pt.tiago.zerodroid.data.ChatState
import pt.tiago.zerodroid.data.NoticeKind

class FinishedTurnTest {
    private fun state(vararg items: ChatItem) = ChatState(items = items.toList())
    private fun user(k: String, steer: Boolean = false) = ChatItem.User(k, "q", steer = steer)

    @Test
    fun completedTurnShowsLastReply() {
        val f = finishedTurn("coder", state(user("u1"), ChatItem.Agent("a1", "first"), ChatItem.Agent("a2", " done! ")))!!
        assertEquals("coder finished", f.title)
        assertEquals("done!", f.text)
        assertFalse(f.failed)
    }

    @Test
    fun replyFromAnEarlierTurnIsIgnored() {
        val f = finishedTurn("coder", state(ChatItem.Agent("a0", "old"), user("u1")))!!
        assertEquals("The turn is done", f.text)
    }

    @Test
    fun steerDoesNotStartANewTurn() {
        val f = finishedTurn("coder", state(user("u1"), ChatItem.Agent("a1", "answer"), user("u2", steer = true)))!!
        assertEquals("answer", f.text)
    }

    @Test
    fun failedTurn() {
        val f = finishedTurn("coder", state(user("u1"), ChatItem.Notice("n1", NoticeKind.TurnFailed, "Turn failed: boom")))!!
        assertEquals("coder failed", f.title)
        assertTrue(f.failed)
    }

    @Test
    fun connectionLostIsReported() {
        val f = finishedTurn("coder", state(user("u1"), ChatItem.Notice("n1", NoticeKind.TurnCancelled, "Turn cancelled: connection lost")))!!
        assertEquals("coder stopped", f.title)
    }

    @Test
    fun userStopIsSilent() =
        assertNull(finishedTurn("coder", state(user("u1"), ChatItem.Notice("n1", NoticeKind.TurnCancelled, "Turn stopped"))))

    @Test
    fun previewStripsMarkdown() {
        assertEquals("Title\nSome bold and code\nitem\nsee docs",
            plainPreview("# Title\n\nSome **bold** and `code`\n- item\n\nsee [docs](https://x.y)"))
    }
}
