package pt.tiago.zerodroid.data

import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

class ChatSessionTest {
    private val history = """{"session_id":"s","messages":[{"role":"user","content":"old"},{"role":"assistant","content":"reply"}],"start":0,"total":2}"""

    private fun TestScope.session(rpc: FakeCaller, center: ApprovalCenter = ApprovalCenter(rpc)) =
        ChatSession("s", "main", rpc, center, backgroundScope)

    @Test
    fun sendWhileIdleCallsPromptWithoutTimeout() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        session(rpc).send("hello")
        val p = rpc.sent.single()
        assertEquals("session/prompt", p.method)
        assertEquals("""{"session_id":"s","prompt":"hello"}""", p.json)
        assertEquals(0L, p.timeoutMs)
    }

    @Test
    fun sendWhileRunningCallsSteer() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        s.onUpdate(SessionUpdate.AgentMessageChunk("s", "working"))
        s.send("faster")
        assertEquals("session/steer", rpc.sent.single().method)
        assertEquals("""{"session_id":"s","content":"faster"}""", rpc.sent.single().json)
    }

    @Test
    fun stopCallsCancel() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        session(rpc).stop()
        assertEquals("session/cancel", rpc.sent.single().method)
    }

    @Test
    fun reconnectReattachesSessionBeforeReload() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply {
            on("session/new", """{"session_id":"s","agent_alias":"main","message_count":0}""")
            on("session/messages", history)
            on("session/state", """{"session_id":"s","state":"idle"}""")
        }
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        chats.get("s", "main")
        chats.onReconnected()
        // session/new (re-attach → daemon rebinds the rpc tool channel) must precede the reads.
        assertEquals(listOf("session/new", "session/messages", "session/state"), rpc.methods())
        assertEquals("""{"agent_alias":"main","session_id":"s","keep_siblings":true}""", rpc.sent.first().json)
    }

    @Test
    fun reconnectRebuildsFromHistory() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply {
            on("session/messages", history)
            on("session/state", """{"session_id":"s","state":"idle"}""")
        }
        val s = session(rpc)
        s.onUpdate(SessionUpdate.AgentMessageChunk("s", "partial"))
        s.onConnectionLost()
        s.reload()
        assertEquals(listOf("session/messages", "session/state"), rpc.methods())
        val texts = s.state.value.items.map { it.key to it }.map { it.second }
        assertEquals("old", (texts[0] as ChatItem.User).text)
        assertEquals("reply", (texts[1] as ChatItem.Agent).text)
        assertTrue(texts.last() is ChatItem.Notice)
        assertFalse(s.state.value.turnRunning)
    }

    @Test
    fun retryLastResendsLastUserPrompt() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        s.send("ping")
        s.onUpdate(SessionUpdate.TurnComplete("s", "failed", "boom"))
        rpc.sent.clear()
        s.retryLast()
        assertEquals("""{"session_id":"s","prompt":"ping"}""", rpc.sent.single().json)
    }

    @Test
    fun sessionBusyErrorShowsNotice() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply { fail("session/prompt", RpcCodes.SESSION_BUSY, "busy") }
        val s = session(rpc)
        s.send("hi")
        val n = s.state.value.items.last() as ChatItem.Notice
        assertEquals("This session is busy with another turn", n.text)
        assertFalse(s.state.value.turnRunning)
    }

    @Test
    fun notOwnedReattachesAndRetriesOnce() = runTest(UnconfinedTestDispatcher()) {
        var first = true
        val rpc = FakeCaller()
        rpc.handlers["session/prompt"] = {
            if (first) { first = false; throw pt.tiago.zerodroid.rpc.RpcException(pt.tiago.zerodroid.rpc.RpcError(RpcCodes.SESSION_NOT_OWNED, "x")) }
            JsonObject(emptyMap())
        }
        session(rpc).send("hi")
        assertEquals(listOf("session/prompt", "session/new", "session/prompt"), rpc.methods())
        assertEquals("""{"agent_alias":"main","session_id":"s","keep_siblings":true}""", rpc.sent[1].json)
    }

    @Test
    fun approvalEventReachesCenterAndResolutionUpdatesChat() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val center = ApprovalCenter(rpc)
        val chats = ChatSessions(rpc, center, backgroundScope)
        val s = chats.get("s", "main")
        chats.dispatch(pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement(
            """{"type":"approval_request","session_id":"s","request_id":"r1","tool_name":"shell","arguments_summary":"ls","timeout_secs":60}""",
        ))
        assertEquals(1, center.pendingCount.value)
        center.decide("r1", pt.tiago.zerodroid.rpc.proto.ApprovalDecision.APPROVE)
        assertEquals(ApprovalStatus.Approved, s.state.value.items.filterIsInstance<ChatItem.Approval>().single().status)
    }

    @Test
    fun runningMapTracksTurns() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        chats.get("s", "researcher").onUpdate(SessionUpdate.AgentMessageChunk("s", "x"))
        assertEquals(mapOf("s" to "researcher"), chats.running.value)
        chats.existing("s")!!.onUpdate(SessionUpdate.TurnComplete("s", "completed", "x"))
        assertTrue(chats.running.value.isEmpty())
    }

    @Test
    fun reloadAttachesADormantSessionBeforeReadingHistory() = runTest(UnconfinedTestDispatcher()) {
        var attached = false
        val rpc = FakeCaller()
        rpc.handlers["session/messages"] = {
            if (!attached) throw pt.tiago.zerodroid.rpc.RpcException(pt.tiago.zerodroid.rpc.RpcError(RpcCodes.SESSION_NOT_OWNED, "dormant"))
            pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement(history)
        }
        rpc.handlers["session/new"] = { attached = true; pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement("""{"session_id":"s","agent_alias":"main","message_count":2,"workspace_dir":"/w"}""") }
        rpc.on("session/state", """{"session_id":"s","state":"idle"}""")
        val s = session(rpc)
        s.reload()
        assertEquals(listOf("session/messages", "session/new", "session/messages", "session/state"), rpc.methods())
        assertEquals("old", (s.state.value.items[0] as ChatItem.User).text)
    }

    @Test
    fun configureSendsOnlySetFields() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply { on("session/configure", """{"session_id":"s","overrides":{"model_provider":"ollama.local"}}""") }
        val s = session(rpc)
        assertTrue(s.configureModel("ollama.local", null).isSuccess)
        assertEquals("""{"session_id":"s","overrides":{"model_provider":"ollama.local"}}""", rpc.sent.single().json)
        assertEquals("ollama.local", s.overrides.value?.modelProvider)
    }

    @Test
    fun configureRefusedWhileRunning() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        s.onUpdate(SessionUpdate.AgentMessageChunk("s", "x"))
        val r = s.configureModel("ollama.local", "qwen3")
        assertEquals("Wait for the current turn to finish", r.exceptionOrNull()?.message)
        assertTrue(rpc.sent.isEmpty())
    }

    @Test
    fun emptyConfigureReadsOverrides() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply { on("session/configure", """{"session_id":"s","overrides":{"model":"qwen3"}}""") }
        val s = session(rpc)
        s.refreshOverrides()
        assertEquals("""{"session_id":"s","overrides":{}}""", rpc.sent.single().json)
        assertEquals("qwen3", s.overrides.value?.model)
    }

    @Test
    fun promptCarriesInlineAttachments() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        val sha = "d".repeat(64)
        s.send("what is this?", listOf(OutgoingImage(sha, byteArrayOf(1, 2, 3))))
        val p = rpc.sent.single()
        assertEquals("session/prompt", p.method)
        assertEquals(
            """{"session_id":"s","prompt":"what is this?","attachments":[{"filename":"dddddddddddd.jpg","data_b64":"AQID","mime_type":"image/jpeg"}]}""",
            p.json,
        )
        assertEquals(listOf(sha), (s.state.value.items.last() as ChatItem.User).images)
    }

    @Test
    fun imageOnlyMessageSends() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        session(rpc).send("  ", listOf(OutgoingImage("e".repeat(64), byteArrayOf(1))))
        assertTrue(rpc.sent.single().json.contains("\"prompt\":\"(image)\""))
    }

    @Test
    fun overridesAreNotReadWhileATurnRuns() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        s.onUpdate(SessionUpdate.AgentMessageChunk("s", "busy"))
        s.refreshOverrides()
        assertTrue("no session/configure while a turn runs", rpc.methods().none { it == "session/configure" })
    }

    @Test
    fun openReadsOverridesAfterHistoryOnlyWhenIdle() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply {
            on("session/messages", history)
            on("session/state", """{"session_id":"s","state":"running"}""")
        }
        session(rpc).open()
        assertEquals(listOf("session/messages", "session/state"), rpc.methods())
        val idle = FakeCaller().apply {
            on("session/messages", history)
            on("session/state", """{"session_id":"s","state":"idle"}""")
            on("session/configure", """{"session_id":"s","overrides":{"model":"qwen3"}}""")
        }
        val s2 = session(idle)
        s2.open()
        assertEquals(listOf("session/messages", "session/state", "session/configure"), idle.methods())
        assertEquals("qwen3", s2.overrides.value?.model)
    }

    @Test
    fun imagePromptIsNeverRetriedAsBareText() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply { fail("session/prompt", RpcCodes.SESSION_BUSY, "busy") }
        val s = session(rpc)
        s.send("", listOf(OutgoingImage("f".repeat(64), byteArrayOf(1))))
        rpc.sent.clear()
        s.retryLast()
        assertTrue(rpc.sent.isEmpty())
    }

    @Test
    fun draftLivesInTheSessionAndClearsOnSend() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val chats = ChatSessions(rpc, ApprovalCenter(rpc), backgroundScope)
        val img = OutgoingImage("a".repeat(64), byteArrayOf(1))
        chats.get("s", "main").apply {
            draftText.value = "half written"
            draftImages.value = listOf(img)
        }
        // Leaving and reopening the chat gets the same session back, draft included.
        val again = chats.get("s", "main")
        assertEquals("half written", again.draftText.value)
        assertEquals(listOf(img), again.draftImages.value)
        again.send(again.draftText.value, again.draftImages.value)
        assertEquals("", again.draftText.value)
        assertTrue(again.draftImages.value.isEmpty())
    }

    @Test
    fun steerKeepsTheImageDraft() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller()
        val s = session(rpc)
        s.onUpdate(SessionUpdate.AgentMessageChunk("s", "busy"))
        val img = OutgoingImage("b".repeat(64), byteArrayOf(1))
        s.draftImages.value = listOf(img)
        s.send("faster", emptyList())
        assertEquals(listOf(img), s.draftImages.value)
    }

    private class RecordingSink : pt.tiago.zerodroid.data.canvas.CanvasSink {
        val got = mutableListOf<Pair<String, pt.tiago.zerodroid.data.canvas.CanvasCapture>>()
        override fun onCapture(sessionId: String, agentAlias: String, capture: pt.tiago.zerodroid.data.canvas.CanvasCapture) {
            got += sessionId to capture
        }
    }

    private fun TestScope.canvasSession(rpc: FakeCaller, sink: RecordingSink) =
        ChatSession("s", "main", rpc, ApprovalCenter(rpc), backgroundScope, canvasSink = { sink })

    private val renderArgs = pt.tiago.zerodroid.rpc.ZdJson.parseToJsonElement("""{"action":"render","content":"x"}""")

    @Test
    fun liveCanvasRenderIsCaptured() = runTest(UnconfinedTestDispatcher()) {
        val sink = RecordingSink()
        canvasSession(FakeCaller(), sink).onUpdate(SessionUpdate.ToolCall("s", "t1", "canvas", renderArgs))
        assertEquals(
            listOf("s" to pt.tiago.zerodroid.data.canvas.CanvasCapture.Render("t1", pt.tiago.zerodroid.data.canvas.CanvasRender("default", "html", "x"))),
            sink.got,
        )
    }

    @Test
    fun liveFailedRenderIsReported() = runTest(UnconfinedTestDispatcher()) {
        val sink = RecordingSink()
        val s = canvasSession(FakeCaller(), sink)
        s.onUpdate(SessionUpdate.ToolCall("s", "t1", "canvas", renderArgs))
        s.onUpdate(SessionUpdate.ToolResult("s", "t1", "canvas", "error"))
        assertEquals(pt.tiago.zerodroid.data.canvas.CanvasCapture.Failed("t1"), sink.got.last().second)
    }

    @Test
    fun otherToolsAreNotCaptured() = runTest(UnconfinedTestDispatcher()) {
        val sink = RecordingSink()
        canvasSession(FakeCaller(), sink).onUpdate(SessionUpdate.ToolCall("s", "t1", "shell", renderArgs))
        assertTrue(sink.got.isEmpty())
    }

    @Test
    fun historyRendersAreCaptured() = runTest(UnconfinedTestDispatcher()) {
        val rpc = FakeCaller().apply {
            on(
                "session/messages",
                """{"session_id":"s","messages":[{"role":"assistant","content":"","kind":"tool_call","tool_call_id":"t1","tool_name":"canvas","tool_input":{"action":"render","content":"x"}}],"start":0,"total":1}""",
            )
            on("session/state", """{"session_id":"s","state":"idle"}""")
        }
        val sink = RecordingSink()
        val s = canvasSession(rpc, sink)
        s.reload()
        assertEquals(1, sink.got.size)
        s.reload()
        assertEquals(listOf("t1", "t1"), sink.got.map { it.second.toolCallId })
    }
}
