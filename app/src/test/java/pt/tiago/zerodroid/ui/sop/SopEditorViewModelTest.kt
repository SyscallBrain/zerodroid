package pt.tiago.zerodroid.ui.sop

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.ZdJson

@OptIn(ExperimentalCoroutinesApi::class)
class SopEditorViewModelTest {
    private val loaded = """{"name":"weather","description":"d","x_future":{"keep":true},"triggers":[{"type":"manual"}],
        "steps":[{"number":1,"title":"a","pos":{"x":1,"y":2}},{"number":2,"title":"b"},{"number":3,"title":"c"},{"number":4,"title":"d"}]}"""
    private val graph = """{"nodes":[{"step":1,"title":"a"}],"wires":[],"diagnostics":[],"layout":{"positions":[{"step":1,"col":1,"row":0}]}}"""

    private fun rpc() = FakeCaller().apply {
        on("sops/get", loaded)
        on("sops/graph", graph)
        on("sops/graph-draft", graph)
        on("sops/trigger-sources", """{"sources":["manual"]}""")
        on("sops/list", """{"sops":[{"name":"weather"}]}""")
        on("sops/validate", """{"ok":true,"blocking":[],"warnings":[]}""")
        on("agents/list", """{"agents":[{"alias":"skydroid","enabled":true,"channels":[]},{"alias":"coder","enabled":false,"channels":[]}]}""")
    }

    private fun TestScope.vm(c: FakeCaller, name: String? = "weather") =
        SopEditorViewModel(name, SopRepository(c), chatTools = { setOf("browser") }, scopeOverride = backgroundScope).also { runCurrent() }

    private fun obj(s: String) = ZdJson.parseToJsonElement(s).jsonObject

    @Test
    fun loadsDraftGraphAndSuggestions() = runTest {
        val v = vm(rpc())
        val s = v.state.value
        assertFalse(s.loading)
        assertEquals(obj(loaded), s.draft)
        assertEquals(1, s.graph!!.nodes.size)
        assertFalse(s.dirty)
        assertTrue("browser" in s.toolSuggestions)
    }

    @Test
    fun staleWireResponseIsDropped() = runTest {
        val gate = CompletableDeferred<Unit>()
        val c = rpc().apply {
            handlers["sops/wire-draft"] = {
                runBlocking { gate.await() }
                ZdJson.parseToJsonElement("""{"sop":{"name":"weather","wired":true},"graph":$graph}""")
            }
        }
        val v = vm(c)
        val before = v.state.value.draft
        v.startConnect(1, PortRole.OnFailure)
        gate.complete(Unit) // the fake answers synchronously; ordering is enforced by the version check below
        v.connectTo(3)
        v.undo()
        runCurrent()
        assertEquals(before, v.state.value.draft)
    }

    /** A fake wire-draft that marks the draft it was given with "w<from>_<to>": true. */
    private fun FakeCaller.markingWires() {
        handlers["sops/wire-draft"] = { p ->
            val o = p!!.jsonObject
            val e = o["edit"]!!.jsonObject
            val sop = JsonObject(o["sop"]!!.jsonObject + ("w${e["from"]}_${e["to"]}" to JsonPrimitive(true)))
            ZdJson.parseToJsonElement("""{"sop":$sop,"graph":$graph}""")
        }
    }

    @Test
    fun secondWireDuringFirstKeepsBoth() = runTest {
        val c = rpc().apply { markingWires() }
        val v = vm(c)
        v.startConnect(1, PortRole.Next); v.connectTo(2)
        v.startConnect(3, PortRole.Next); v.connectTo(4)
        runCurrent()
        val d = v.state.value.draft!!
        assertTrue("first connection lost", d.containsKey("w1_2"))
        assertTrue(d.containsKey("w3_4"))
        v.undo(); runCurrent()
        assertTrue(v.state.value.draft!!.containsKey("w1_2"))
        assertFalse(v.state.value.draft!!.containsKey("w3_4"))
    }

    @Test
    fun editDuringWireKeepsBoth() = runTest {
        val c = rpc().apply { markingWires() }
        val v = vm(c)
        v.startConnect(1, PortRole.Next); v.connectTo(2)
        v.edit { SopDraft.set(it, "description", JsonPrimitive("typed")) }
        runCurrent()
        val d = v.state.value.draft!!
        assertTrue(d.containsKey("w1_2"))
        assertEquals("typed", d["description"]!!.jsonPrimitive.content)
    }

    @Test
    fun undoReprojectsGraph() = runTest {
        val c = rpc()
        val v = vm(c)
        v.edit { SopDraft.deleteStep(it, 3) }
        v.undo()
        val before = c.methods().count { it == "sops/graph-draft" }
        advanceTimeBy(401); runCurrent()
        assertTrue(c.methods().count { it == "sops/graph-draft" } > before)
    }

    @Test
    fun connectSendsRoleAndPort() = runTest {
        val c = rpc().apply { on("sops/wire-draft", """{"sop":$loaded,"graph":$graph}""") }
        val v = vm(c)
        v.startConnect(2, PortRole.Branch, port = 1)
        assertEquals(EditorMode.Connect(2, PortRole.Branch, 1), v.state.value.mode)
        v.connectTo(4); runCurrent()
        val edit = c.sent.last { it.method == "sops/wire-draft" }.params!!.jsonObject["edit"]!!.jsonObject
        assertEquals(obj("""{"op":"connect","from":2,"to":4,"role":"switch","port":1}"""), edit)
        assertEquals(EditorMode.Browse, v.state.value.mode)
        assertTrue(v.state.value.dirty)
    }

    @Test
    fun waitsForWiresPickedStepAsSource() = runTest {
        // Dependency edits write the *target's* depends_on (zeroclaw-runtime sop/wire.rs), so "step 4
        // waits for step 2" is the edge 2 -> 4.
        val c = rpc().apply { on("sops/wire-draft", """{"sop":$loaded,"graph":$graph}""") }
        val v = vm(c)
        v.startConnect(4, PortRole.WaitsFor)
        v.connectTo(2); runCurrent()
        val edit = c.sent.last { it.method == "sops/wire-draft" }.params!!.jsonObject["edit"]!!.jsonObject
        assertEquals(obj("""{"op":"connect","from":2,"to":4,"role":"dependency"}"""), edit)
    }

    @Test
    fun editDebouncesGraphDraft() = runTest {
        val c = rpc()
        val v = vm(c)
        repeat(3) { i ->
            v.edit { SopDraft.setStep(it, 1, listOf("title"), JsonPrimitive("t$i")) }
            advanceTimeBy(100)
        }
        assertEquals(0, c.methods().count { it == "sops/graph-draft" })
        advanceTimeBy(401); runCurrent()
        assertEquals(1, c.methods().count { it == "sops/graph-draft" })
        assertTrue(v.state.value.dirty)
    }

    @Test
    fun undoRestoresAndCapsAt50() = runTest {
        val v = vm(rpc())
        val start = v.state.value.draft
        repeat(60) { i -> v.edit { SopDraft.set(it, "description", JsonPrimitive("d$i")) } }
        assertTrue(v.state.value.canUndo)
        repeat(50) { v.undo() }
        assertFalse(v.state.value.canUndo)
        assertEquals("d9", v.state.value.draft!!["description"]!!.jsonPrimitive.content)
        assertTrue(start != v.state.value.draft)
    }

    @Test
    fun saveBlockedByValidation() = runTest {
        val c = rpc().apply { on("sops/validate", """{"ok":false,"blocking":["x"],"warnings":[]}""") }
        val v = vm(c)
        v.edit { SopDraft.set(it, "description", JsonPrimitive("z")) }
        v.save(); runCurrent()
        assertFalse("sops/save" in c.methods())
        assertEquals(listOf("x"), v.state.value.review!!.blocking)
    }

    @Test
    fun saveWithWarningsNeedsForce() = runTest {
        val c = rpc().apply { on("sops/validate", """{"ok":true,"blocking":[],"warnings":["w"]}""") }
        val v = vm(c)
        v.edit { SopDraft.set(it, "description", JsonPrimitive("z")) }
        v.save(); runCurrent()
        assertFalse("sops/save" in c.methods())
        assertEquals(listOf("w"), v.state.value.review!!.warnings)
        v.save(force = true); runCurrent()
        assertTrue("sops/save" in c.methods())
        assertNull(v.state.value.review)
    }

    @Test
    fun saveSendsDraftWithUnknownFields() = runTest {
        val c = rpc()
        val v = vm(c)
        v.edit { SopDraft.setStep(it, 2, listOf("title"), JsonPrimitive("B")) }
        v.save(); runCurrent()
        val sent = c.sent.last { it.method == "sops/save" }.params!!.jsonObject["sop"]!!.jsonObject
        assertEquals(SopDraft.setStep(obj(loaded), 2, listOf("title"), JsonPrimitive("B")), sent)
        assertEquals(obj("""{"keep":true}"""), sent["x_future"])
    }

    @Test
    fun newSopUsesCreateAndNeedsName() = runTest {
        val c = rpc()
        val v = vm(c, name = null)
        assertTrue(v.state.value.isNew)
        v.save(); runCurrent()
        assertFalse("sops/create" in c.methods())
        assertEquals("Give the workflow a name first", v.state.value.error)
        v.edit { SopDraft.set(it, "name", JsonPrimitive("fresh")) }
        v.save(); runCurrent()
        assertTrue("sops/create" in c.methods())
        assertFalse(v.state.value.isNew)
    }

    @Test
    fun dirtyClearsAfterSave() = runTest {
        val v = vm(rpc())
        v.edit { SopDraft.set(it, "description", JsonPrimitive("z")) }
        assertTrue(v.state.value.dirty)
        v.save(); runCurrent()
        assertFalse(v.state.value.dirty)
    }

    @Test
    fun typingCoalescesUndo() = runTest {
        val v = vm(rpc())
        val start = v.state.value.draft
        "hello".forEachIndexed { i, _ -> v.edit("title:1") { SopDraft.setStep(it, 1, listOf("title"), JsonPrimitive("hello".take(i + 1))) } }
        v.edit { SopDraft.set(it, "description", JsonPrimitive("x")) }
        v.edit("title:1") { SopDraft.setStep(it, 1, listOf("title"), JsonPrimitive("hello!")) }
        v.undo(); v.undo(); v.undo()
        assertEquals(start, v.state.value.draft)
        assertFalse(v.state.value.canUndo)
    }

    @Test
    fun newSopDefaultsToFirstEnabledAgent() = runTest {
        // agents/list order from the fixture: skydroid (enabled), coder (disabled).
        val c = rpc().apply {
            on("agents/list", """{"agents":[{"alias":"coder","enabled":false,"channels":[]},{"alias":"skydroid","enabled":true,"channels":[]},{"alias":"architect","enabled":true,"channels":[]}]}""")
        }
        val v = vm(c, name = null)
        assertEquals("skydroid", v.state.value.draft!!["agent"]!!.jsonPrimitive.content)
        assertFalse(v.state.value.canUndo)
    }

    @Test
    fun existingSopKeepsItsAgent() = runTest {
        assertNull(vm(rpc()).state.value.draft!!["agent"])
    }

    @Test
    fun loadsAgentAliases() = runTest {
        assertEquals(listOf("coder", "skydroid"), vm(rpc()).state.value.agents)
    }

    @Test
    fun saveOfDeletedWorkflowTurnsIntoCreate() = runTest {
        val c = rpc().apply { fail("sops/save", pt.tiago.zerodroid.rpc.RpcCodes.SOP_NOT_FOUND, "gone") }
        val v = vm(c)
        v.edit { SopDraft.set(it, "description", JsonPrimitive("z")) }
        v.save(); runCurrent()
        assertTrue(v.state.value.isNew)
        assertEquals("This workflow was deleted elsewhere. Save again to create it.", v.state.value.error)
        v.save(); runCurrent()
        assertTrue("sops/create" in c.methods())
    }

    @Test
    fun selectedTabLivesInTheViewModel() = runTest {
        val v = vm(rpc())
        v.startConnect(1, PortRole.Next)
        v.selectTab(2)
        assertEquals(2, v.state.value.tab)
        assertEquals(EditorMode.Browse, v.state.value.mode)
    }

    @Test
    fun addStepOpensIt() = runTest {
        val v = vm(rpc())
        v.addStep()
        assertEquals(5, v.state.value.openStep)
        assertEquals(5, SopDraft.steps(v.state.value.draft as JsonObject).size)
    }
}
