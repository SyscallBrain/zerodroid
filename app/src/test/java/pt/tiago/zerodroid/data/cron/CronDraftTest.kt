package pt.tiago.zerodroid.data.cron

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.CronDelivery
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.CronPatchParams
import pt.tiago.zerodroid.rpc.proto.CronSchedule

class CronDraftTest {
    private val agentJob = CronJob(
        id = "j1", expression = "0 9 * * *", schedule = CronSchedule("cron", "0 9 * * *", "Europe/Lisbon"),
        name = "Keryx", prompt = "write", jobType = "agent", sessionTarget = "main", model = "m",
        agentAlias = "keryx", delivery = CronDelivery("announce", "telegram.main", "42", false),
        allowedTools = listOf("shell", "web"), usesMemory = false,
    )
    private val shellJob = CronJob(id = "s1", expression = "0 6 * * *", schedule = CronSchedule("cron", "0 6 * * *"), command = "pkg up", jobType = "shell", agentAlias = "sysadmin")

    private fun keys(p: CronPatchParams) = ZdJson.encodeToJsonElement(CronPatchParams.serializer(), p).jsonObject.keys

    @Test
    fun shellAddOmitsAgentFields() {
        val p = CronDraft(agent = "a", expr = "0 * * * *", command = " echo hi ", model = "ignored").toAdd()
        assertEquals("echo hi", p.command)
        assertNull(p.jobType); assertNull(p.prompt); assertNull(p.model); assertNull(p.sessionTarget)
        assertNull(p.allowedTools); assertNull(p.usesMemory); assertNull(p.delivery); assertNull(p.tz); assertNull(p.name)
    }

    @Test
    fun agentAddSendsEverything() {
        val p = CronDraft(
            name = "n", agent = "a", agentJob = true, expr = "0 9 * * *", tz = "Europe/Lisbon", prompt = "go", model = "m",
            sessionMain = true, allowedTools = "shell", usesMemory = false, announce = true, channel = "telegram.main", to = "42", bestEffort = false,
        ).toAdd()
        assertEquals("agent", p.jobType); assertEquals("go", p.prompt); assertEquals("m", p.model)
        assertEquals("main", p.sessionTarget); assertEquals(listOf("shell"), p.allowedTools); assertEquals(false, p.usesMemory)
        assertEquals(CronDelivery("announce", "telegram.main", "42", false), p.delivery)
        assertEquals("Europe/Lisbon", p.tz); assertEquals("n", p.name); assertNull(p.command)
    }

    @Test
    fun toolsSplitAndTrim() {
        assertEquals(listOf("shell", "web"), CronDraft(agent = "a", agentJob = true, expr = "x", prompt = "p", allowedTools = " shell, , web ").toAdd().allowedTools)
    }

    @Test
    fun patchOnlyChangedFields() {
        val orig = CronDraft.from(agentJob)
        val p = orig.copy(name = "Keryx 2").toPatch("j1", orig)!!
        assertEquals(setOf("id", "agent", "name"), keys(p))
        assertEquals("keryx", p.agent)
    }

    @Test
    fun clearingModelSendsClearFlag() {
        val orig = CronDraft.from(agentJob)
        val p = orig.copy(model = " ").toPatch("j1", orig)!!
        assertEquals(setOf("id", "agent", "clear_model"), keys(p))
        assertEquals(true, p.clearModel)
    }

    @Test
    fun clearingTzSendsClearTz() {
        val orig = CronDraft.from(agentJob)
        val p = orig.copy(tz = "").toPatch("j1", orig)!!
        assertEquals(true, p.clearTz)
        assertEquals("0 9 * * *", p.schedule)
    }

    @Test
    fun clearingToolsSendsClearFlag() {
        val orig = CronDraft.from(agentJob)
        val p = orig.copy(allowedTools = "").toPatch("j1", orig)!!
        assertEquals(setOf("id", "agent", "clear_allowed_tools"), keys(p))
    }

    @Test
    fun announceOffSendsModeNone() {
        val orig = CronDraft.from(agentJob)
        val p = orig.copy(announce = false).toPatch("j1", orig)!!
        assertEquals("none", p.delivery!!.mode)
    }

    @Test
    fun shellPatchNeverSendsAgentFields() {
        val orig = CronDraft.from(shellJob)
        val p = orig.copy(command = "pkg upgrade", model = "m", sessionMain = true, allowedTools = "x", usesMemory = false).toPatch("s1", orig)!!
        assertEquals(setOf("id", "agent", "command"), keys(p))
    }

    @Test
    fun unchangedPatchIsNull() {
        val orig = CronDraft.from(agentJob)
        assertNull(orig.toPatch("j1", orig))
    }

    @Test
    fun problemsForEmptyAgentDraft() {
        assertEquals(listOf("Pick an agent", "Enter a schedule", "Enter a prompt"), CronDraft(agentJob = true).problems())
    }

    @Test
    fun problemsForAnnounceWithoutTarget() {
        assertEquals(
            listOf("Enter a command", "Pick a channel", "Enter who to send it to"),
            CronDraft(agent = "a", expr = "* * * * *", announce = true).problems(),
        )
    }

    @Test
    fun fromJobRoundTrips() {
        val d = CronDraft.from(agentJob)
        assertEquals("shell, web", d.allowedTools)
        assertTrue(d.sessionMain); assertTrue(d.announce); assertFalse(d.bestEffort); assertEquals("Europe/Lisbon", d.tz)
        assertNull(d.toPatch("j1", d))
    }

    @Test
    fun dirtyAfterEdit() {
        val orig = CronDraft.from(shellJob)
        assertFalse(orig.dirty(orig))
        assertTrue(orig.copy(name = "x").dirty(orig))
    }

    @Test
    fun atJobSavesWithoutSchedule() {
        val at = CronJob(id = "o1", expression = "", schedule = CronSchedule("at", at = "2026-11-23T09:00:00Z"), command = "echo", jobType = "shell", agentAlias = "a")
        val d = CronDraft.from(at)
        val edited = d.copy(name = "x")
        assertEquals(emptyList<String>(), edited.problems())
        assertEquals(setOf("id", "agent", "name"), keys(edited.toPatch("o1", d)!!))
    }

    @Test
    fun deliveryEditKeepsThreadId() {
        val job = agentJob.copy(delivery = CronDelivery("announce", "telegram.main", "42", false, threadId = "t9"))
        val d = CronDraft.from(job)
        val p = d.copy(to = "43").toPatch("j1", d)!!
        assertEquals("t9", p.delivery!!.threadId)
        assertEquals("t9", d.copy(agent = "a").toAdd().delivery!!.threadId)
    }
}
