package pt.tiago.zerodroid.data.sop

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.FakeCaller
import pt.tiago.zerodroid.rpc.RpcCodes
import pt.tiago.zerodroid.rpc.RpcError
import pt.tiago.zerodroid.rpc.RpcException
import pt.tiago.zerodroid.rpc.ZdJson

class SopRepositoryTest {
    private val sop = SopDraft.blank("weather")

    @Test
    fun engineOffMapsToUnavailable() = runTest {
        val rpc = FakeCaller().apply { fail("sops/runs", RpcCodes.INTERNAL_ERROR, "SOP subsystem not enabled") }
        assertEquals(SopResult.Unavailable, SopRepository(rpc).runs())
    }

    @Test
    fun forbiddenMapsToForbidden() = runTest {
        val rpc = FakeCaller().apply { fail("sops/save", RpcCodes.FORBIDDEN) }
        assertEquals(SopResult.Forbidden, SopRepository(rpc).save(sop))
    }

    @Test
    fun saveSendsOriginalNameEqualToName() = runTest {
        val rpc = FakeCaller()
        SopRepository(rpc).save(sop)
        val p = rpc.sent.single().params!!.jsonObject
        assertEquals("weather", p["original_name"]!!.jsonPrimitive.content)
        assertEquals(sop, p["sop"])
    }

    @Test
    fun wireDraftSendsEdit() = runTest {
        val rpc = FakeCaller().apply { on("sops/wire-draft", """{"sop":{"name":"weather"},"graph":{}}""") }
        val r = SopRepository(rpc).wireDraft(sop, WireEdit("connect", 1, 3, "failure"))
        assertTrue(r is SopResult.Ok)
        val edit = rpc.sent.single().params!!.jsonObject["edit"]!!.jsonObject
        assertEquals(ZdJson.parseToJsonElement("""{"op":"connect","from":1,"to":3,"role":"failure"}"""), edit)
        assertFalse(edit.containsKey("port"))
    }

    @Test
    fun decideSendsCanonicalShapes() = runTest {
        val rpc = FakeCaller()
        val repo = SopRepository(rpc)
        repo.decide("s", "r", approve = true)
        repo.decide("s", "r", approve = false, reason = "no")
        assertEquals("approve", rpc.sent[0].params!!.jsonObject["decision"]!!.jsonPrimitive.content)
        assertEquals(
            ZdJson.parseToJsonElement("""{"deny":{"reason":"no"}}"""),
            rpc.sent[1].params!!.jsonObject["decision"],
        )
    }

    @Test
    fun runReturnsRunId() = runTest {
        val rpc = FakeCaller().apply { on("sops/run", """{"run_id":"r-1"}""") }
        assertEquals(SopResult.Ok("r-1"), SopRepository(rpc).run("weather", null))
        assertFalse(rpc.sent.single().params!!.jsonObject.containsKey("payload"))
    }

    @Test
    fun getReturnsRawJson() = runTest {
        val rpc = FakeCaller().apply { on("sops/get", """{"name":"weather","x_future":1}""") }
        val r = SopRepository(rpc).get("weather") as SopResult.Ok<JsonObject>
        assertEquals("1", r.value["x_future"]!!.jsonPrimitive.content)
    }

    @Test
    fun alreadyExistsSentence() {
        val e = RpcException(RpcError(RpcCodes.SOP_ALREADY_EXISTS, "SOP 'x' already exists"))
        assertEquals("A workflow with this name already exists", sopErrorText(e))
        assertEquals(
            "This workflow was deleted elsewhere. Save as new?",
            sopErrorText(RpcException(RpcError(RpcCodes.SOP_NOT_FOUND, "nf"))),
        )
        assertEquals("Your sign-in can't edit workflows", sopErrorText(RpcException(RpcError(RpcCodes.FORBIDDEN, "x"))))
    }

    @Test
    fun createFailureCarriesSentence() = runTest {
        val rpc = FakeCaller().apply { fail("sops/create", RpcCodes.SOP_ALREADY_EXISTS) }
        assertEquals(SopResult.Error("A workflow with this name already exists"), SopRepository(rpc).create(sop))
    }
}
