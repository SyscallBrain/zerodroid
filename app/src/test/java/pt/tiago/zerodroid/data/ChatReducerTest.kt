package pt.tiago.zerodroid.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.MessageEntry
import pt.tiago.zerodroid.rpc.proto.SessionUpdate as U

class ChatReducerTest {
    private fun run(vararg events: U, start: ChatState = ChatState(), now: Long = 1_000) =
        events.fold(start) { s, e -> reduce(s, e, now) }

    private fun chunk(t: String) = U.AgentMessageChunk("s", t)
    private fun call(id: String, name: String = "shell") = U.ToolCall("s", id, name, JsonObject(mapOf("cmd" to JsonPrimitive("ls"))))

    @Test
    fun chunksMergeIntoOneStreamingAgentItem() {
        val s = run(chunk("Hel"), chunk("lo"), chunk("!"), start = localPrompt(ChatState(), "hi"))
        val agents = s.items.filterIsInstance<ChatItem.Agent>()
        assertEquals(1, agents.size)
        assertEquals("Hello!", agents[0].text)
        assertTrue(agents[0].streaming)
        val done = reduce(s, U.TurnComplete("s", "completed", "Hello!"), 2_000)
        assertFalse(done.items.filterIsInstance<ChatItem.Agent>().single().streaming)
        assertFalse(done.turnRunning)
    }

    @Test
    fun everyItemKeyIsUnique() {
        var s = localPrompt(ChatState(), "one")
        s = run(chunk("a"), call("c1"), U.ToolResult("s", "c1", "shell", "ok"), chunk("b"), U.TurnComplete("s", "completed", ""), start = s)
        s = localPrompt(s, "two")
        s = run(chunk("c"), U.TurnComplete("s", "completed", ""), start = s)
        assertEquals(s.items.size, s.items.map { it.key }.toSet().size)
        assertEquals(3, s.items.count { it is ChatItem.Agent })
    }

    @Test
    fun toolCallSplitsAgentText() {
        val s = run(chunk("before"), call("c1"), chunk("after"), start = localPrompt(ChatState(), "go"))
        assertEquals(listOf("before", "after"), s.items.filterIsInstance<ChatItem.Agent>().map { it.text })
        assertTrue(s.items[2] is ChatItem.Tool)
    }

    @Test
    fun toolResultClosesMatchingCallById() {
        val s0 = run(call("c1", "a"), call("c2", "b"), now = 1_000)
        val s1 = reduce(s0, U.ToolResult("s", "c2", "b", "two"), 1_500)
        val s2 = reduce(s1, U.ToolResult("s", "c1", "a", "one"), 3_000)
        val tools = s2.items.filterIsInstance<ChatItem.Tool>()
        assertEquals(listOf("one", "two"), tools.map { it.output })
        assertEquals(listOf(2_000L, 500L), tools.map { it.durationMs })
        assertTrue(tools.all { it.status == ToolStatus.Ok })
        assertEquals("ls", tools[0].args)
    }

    @Test
    fun approvalRequestAddsPendingWithDeadline() {
        val s = run(U.ApprovalRequest("s", "r1", "shell", "rm x", 120), now = 5_000)
        val a = s.items.single() as ChatItem.Approval
        assertEquals(5_000 + 120_000L, a.deadlineAt)
        assertEquals(ApprovalStatus.Pending, a.status)
    }

    private fun question(id: String = "r1") = PendingQuestion(
        requestId = id, id = JsonPrimitive(id), sessionId = "s", message = "Continue?",
        property = "choice", multi = false,
        options = listOf(
            pt.tiago.zerodroid.rpc.proto.ElicitationOption("choice-0", "Yes"),
            pt.tiago.zerodroid.rpc.proto.ElicitationOption("choice-1", "No"),
        ),
        deadlineAt = 1_000 + 300_000, status = QuestionStatus.Pending,
    )

    @Test
    fun questionReceivedAppendsCardAndResolutionUpdatesIt() {
        var s = questionReceived(ChatState(), question())
        val q = s.items.filterIsInstance<ChatItem.Question>().single()
        assertEquals("Continue?", q.message)
        assertEquals(listOf("Yes", "No"), q.options)
        assertEquals(listOf("choice-0", "choice-1"), q.consts)
        assertEquals(QuestionStatus.Pending, q.status)
        assertTrue(s.turnRunning)
        // Same request again does not duplicate the card.
        s = questionReceived(s, question())
        assertEquals(1, s.items.count { it is ChatItem.Question })
        s = setQuestionStatus(s, "r1", QuestionStatus.Answered, listOf("No"), 2_000)
        val done = s.items.filterIsInstance<ChatItem.Question>().single()
        assertEquals(QuestionStatus.Answered, done.status)
        assertEquals(listOf("No"), done.answer)
        assertEquals(2_000L, done.answeredAt)
    }

    @Test
    fun connectionLostMarksPendingQuestion() {
        val s = connectionLost(questionReceived(ChatState(), question()))
        assertEquals(QuestionStatus.ConnectionLost, s.items.filterIsInstance<ChatItem.Question>().single().status)
    }

    @Test
    fun unknownEventIsIgnored() {
        val s = localPrompt(ChatState(), "hi")
        assertEquals(s, reduce(s, U.Unknown("brand_new_thing", JsonObject(emptyMap())), 1))
    }

    @Test
    fun connectionLostCancelsRunningTurn() {
        val s0 = run(chunk("x"), U.ApprovalRequest("s", "r1", "shell", "rm", 60), start = localPrompt(ChatState(), "go"))
        val s = connectionLost(s0)
        assertFalse(s.turnRunning)
        val notice = s.items.last() as ChatItem.Notice
        assertEquals(NoticeKind.TurnCancelled, notice.kind)
        assertEquals("Turn cancelled: connection lost", notice.text)
        assertTrue(notice.retry)
        assertEquals(ApprovalStatus.ConnectionLost, s.items.filterIsInstance<ChatItem.Approval>().single().status)
    }

    @Test
    fun connectionLostWhileIdleAddsNothing() {
        val s0 = run(chunk("x"), U.TurnComplete("s", "completed", "x"), start = localPrompt(ChatState(), "go"))
        assertEquals(s0.items.size, connectionLost(s0).items.size)
    }

    @Test
    fun hugeToolOutputIsTruncatedForDisplay() {
        val big = "x".repeat(2 * 1024 * 1024)
        val s = run(call("c1"), U.ToolResult("s", "c1", "shell", big))
        val out = s.items.filterIsInstance<ChatItem.Tool>().single().output!!
        assertTrue(out.length <= 64 * 1024)
        assertTrue(out.endsWith("… (truncated)"))
    }

    @Test
    fun turnCompleteWithoutStreamingShowsContent() {
        val s = run(U.TurnComplete("s", "completed", "pong"), start = localPrompt(ChatState(), "ping"))
        assertEquals("pong", s.items.filterIsInstance<ChatItem.Agent>().single().text)
    }

    @Test
    fun cancelledOutcomeAddsRetryNotice() {
        val s = run(chunk("par"), U.TurnComplete("s", "cancelled", ""), start = localPrompt(ChatState(), "go"))
        assertTrue((s.items.last() as ChatItem.Notice).retry)
    }

    @Test
    fun steerKeepsLastPrompt() {
        val s = localPrompt(localPrompt(ChatState(), "first"), "faster please")
        assertEquals("first", s.lastUserPrompt)
        assertTrue((s.items.last() as ChatItem.User).steer)
    }

    @Test
    fun historyRebuildsUserAgentAndTools() {
        val s = fromHistory(
            listOf(
                MessageEntry("user", "hi"),
                MessageEntry("assistant", "", kind = "tool_call", toolCallId = "c1", toolName = "shell", toolInput = JsonObject(mapOf("cmd" to JsonPrimitive("ls")))),
                MessageEntry("tool", "", kind = "tool_result", toolCallId = "c1", toolOutput = "a b"),
                MessageEntry("assistant", "hello"),
                MessageEntry("system", "secret prompt"),
            ),
            start = 4, hasOlder = true,
        )
        assertEquals(3, s.items.size)
        assertEquals("hi", (s.items[0] as ChatItem.User).text)
        assertEquals("a b", (s.items[1] as ChatItem.Tool).output)
        assertEquals("hello", (s.items[2] as ChatItem.Agent).text)
        assertEquals("hi", s.lastUserPrompt)
        assertTrue(s.hasOlder)
        assertEquals(4, s.historyStart)
    }

    @Test
    fun prependKeepsKeysUnique() {
        val cur = fromHistory(listOf(MessageEntry("user", "b")))
        val older = fromHistory(listOf(MessageEntry("user", "a")))
        val s = prependHistory(cur, older)
        assertEquals(listOf("a", "b"), s.items.map { (it as ChatItem.User).text })
        assertEquals(2, s.items.map { it.key }.toSet().size)
    }

    @Test
    fun historyUserImagesComeFromMarkers() {
        val sha = "c".repeat(64)
        val s = fromHistory(listOf(MessageEntry("user", "what is this? [IMAGE:/ws/attachments/$sha.jpg]")))
        val u = s.items.single() as ChatItem.User
        assertEquals("what is this?", u.text)
        assertEquals(listOf(sha), u.images)
        assertEquals("image prompts are not retried as bare text", null, s.lastUserPrompt)
    }
}
