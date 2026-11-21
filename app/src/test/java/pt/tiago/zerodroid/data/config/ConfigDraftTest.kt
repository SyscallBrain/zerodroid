package pt.tiago.zerodroid.data.config

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.PropKind

class ConfigDraftTest {
    private fun field(path: String, value: String? = "medium", kind: PropKind = PropKind.String, secret: Boolean = false, populated: Boolean = true) =
        ConfigField(path = path, kind = kind, value = if (secret) null else value?.let { JsonPrimitive(it) }, isSecret = secret, populated = populated)

    private val reasoning = field("agents.coder.reasoning")
    private val key = field("providers.models.anthropic.default.api_key", secret = true)

    @Test
    fun stageAndUnstage() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        assertTrue(d.isStaged("agents.coder.reasoning"))
        assertEquals("reasoning", d.entries.value.single().label)
        d.unstage("agents.coder.reasoning")
        assertTrue(d.entries.value.isEmpty())
    }

    @Test
    fun stagingOriginalValueRemovesEntry() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("medium")))
        assertTrue(d.entries.value.isEmpty())
    }

    @Test
    fun restagingReplacesInPlace() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        d.stage(field("agents.coder.enabled", "true"), "agents", DraftValue.Plain(JsonPrimitive(false)))
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("low")))
        assertEquals(listOf("agents.coder.reasoning", "agents.coder.enabled"), d.entries.value.map { it.path })
    }

    @Test
    fun toSetManyKeepsOrderAndComment() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        d.stage(key, "providers.models", DraftValue.Secret("sk-live-999"))
        val sets = d.toSetMany()
        assertEquals(listOf("agents.coder.reasoning", "providers.models.anthropic.default.api_key"), sets.map { it.prop })
        assertTrue(sets.all { it.comment == "ZeroDroid" })
        assertEquals(JsonPrimitive("sk-live-999"), sets[1].value)
    }

    @Test
    fun secretValueNeverInDiffOrPersisted() {
        val d = ConfigDraft()
        d.stage(key, "providers.models", DraftValue.Secret("sk-live-999"))
        val e = d.entries.value.single()
        assertFalse(d.persistable().contains("sk-live-999"))
        assertFalse(diffLine(e).toString().contains("sk-live-999"))
        assertFalse(d.entries.value.toString().contains("sk-live-999"))
        assertFalse(d.toSetMany().toString().contains("sk-live-999"))
        assertEquals("••••" to "new value", diffLine(e))
        assertEquals("not set" to "new value", diffLine(ConfigDraft().also { it.stage(field("a.k", secret = true, populated = false), "a", DraftValue.Secret("x")) }.entries.value.single()))
    }

    @Test
    fun diffLineForPlain() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        assertEquals("medium" to "high", diffLine(d.entries.value.single()))
    }

    @Test
    fun rebaseDetectsServerChange() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        d.stage(key, "providers.models", DraftValue.Secret("x"))
        assertTrue(d.conflicts(listOf(reasoning, key)).isEmpty())
        val changed = d.conflicts(listOf(field("agents.coder.reasoning", "low"), field(key.path, secret = true, populated = false)))
        assertEquals(listOf("agents.coder.reasoning", key.path), changed)
    }

    @Test
    fun unstageUnderRemovesAliasEntries() {
        val d = ConfigDraft()
        listOf("agents.x.model", "agents.x.temperature", "agents.xy.model").forEach {
            d.stage(field(it, "a"), "agents", DraftValue.Plain(JsonPrimitive("b")))
        }
        d.unstageUnder("agents.x")
        assertEquals(listOf("agents.xy.model"), d.entries.value.map { it.path })
    }

    @Test
    fun restoreDropsSecretsAndFlagsLoss() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        d.stage(key, "providers.models", DraftValue.Secret("x"))
        val saved = d.persistable()
        val r = ConfigDraft()
        r.restore(saved) { p -> listOf(reasoning, key).firstOrNull { it.path == p } }
        assertEquals(listOf("agents.coder.reasoning"), r.entries.value.map { it.path })
        assertEquals(JsonPrimitive("high"), (r.entries.value.single().value as DraftValue.Plain).json)
        assertTrue(r.lostSecrets)
        assertFalse(d.lostSecrets)
    }

    @Test
    fun restoreKeepsOriginalSoConflictsSurviveProcessDeath() {
        val d = ConfigDraft()
        d.stage(reasoning, "agents", DraftValue.Plain(JsonPrimitive("high")))
        val saved = d.persistable()
        val desktopChanged = field("agents.coder.reasoning", "low")
        val r = ConfigDraft()
        r.restore(saved) { desktopChanged }
        assertEquals(listOf("agents.coder.reasoning"), r.conflicts(listOf(desktopChanged)))
    }
}
