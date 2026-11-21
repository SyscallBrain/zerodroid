package pt.tiago.zerodroid.rpc.proto

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson

class ConfigProtoTest {
    private fun fixture(name: String) = javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().use { it.readText() }

    @Test
    fun decodesAllShapes() {
        val r = ZdJson.decodeFromString(ConfigSectionsResult.serializer(), fixture("config_sections.json"))
        assertEquals(
            listOf(SectionShape.TypedFamilyMap, SectionShape.OneTierAliasMap, SectionShape.TypedFamilyMap, SectionShape.DirectForm, SectionShape.BackendPicker, SectionShape.Unknown),
            r.sections.map { it.shape },
        )
        assertEquals("foundation", r.sections[0].groupKey)
    }

    @Test
    fun decodesFieldKindsAndFlags() {
        val f = ZdJson.decodeFromString(ConfigListResult.serializer(), fixture("config_list_agents.json")).entries
        assertEquals(PropKind.Bool, f[0].kind)
        assertEquals(listOf("low", "medium", "high"), f[1].enumVariants)
        assertEquals("Model", f[1].tab)
        assertEquals("model_providers", f[2].aliasSource)
        assertTrue(f[4].isEnvOverridden)
        assertTrue(f[5].required)
        assertTrue(f[5].multiline)
        assertFalse(f[0].required)
        val secret = ZdJson.decodeFromString(ConfigListResult.serializer(), fixture("config_list_secret.json")).entries
        assertTrue(secret[0].isSecret)
        assertNull(secret[0].value)
        assertEquals(PropKind.SecretMap, secret[1].kind)
    }

    @Test
    fun unknownKindAndOutcomeDoNotThrow() {
        val f = ZdJson.decodeFromString(ConfigListResult.serializer(), fixture("config_list_agents.json")).entries
        assertEquals(PropKind.Unknown, f.last().kind)
        val s = ZdJson.decodeFromString(ConfigStatusResult.serializer(), fixture("config_status.json"))
        assertEquals(
            listOf(ApplyOutcome.AppliedLive, ApplyOutcome.QueuedForReload, ApplyOutcome.Rejected, ApplyOutcome.Pending, ApplyOutcome.Unknown),
            s.application!!.records.map { it.outcome },
        )
        assertEquals(listOf("gateway", "port"), s.application!!.records[1].path)
    }

    @Test
    fun secretEntryToStringHidesValue() {
        val e = ConfigSetEntry.secret("a.b", "sk-123")
        assertFalse(e.toString().contains("sk-123"))
        assertFalse(ConfigSetManyParams(listOf(e)).toString().contains("sk-123"))
    }

    @Test
    fun setManyParamsEncode() {
        val p = ConfigSetManyParams(listOf(ConfigSetEntry("a", JsonPrimitive(true), "ZeroDroid"), ConfigSetEntry("b", JsonPrimitive("x"), "ZeroDroid")))
        assertEquals(
            """{"sets":[{"prop":"a","value":true,"comment":"ZeroDroid"},{"prop":"b","value":"x","comment":"ZeroDroid"}]}""",
            ZdJson.encodeToString(ConfigSetManyParams.serializer(), p),
        )
    }

    @Test
    fun decodesDeletePreview() {
        val p = ZdJson.decodeFromString(AgentDeletePreview.serializer(), fixture("config_delete_preview.json"))
        assertTrue(p.allowed)
        assertEquals(1, p.ownedState.size)
    }
}
