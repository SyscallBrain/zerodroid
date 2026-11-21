package pt.tiago.zerodroid.data.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.PropKind

class FieldModelTest {
    private fun f(kind: PropKind, variants: List<String> = emptyList(), secret: Boolean = false, env: Boolean = false, multiline: Boolean = false, required: Boolean = false, value: String? = null) =
        ConfigField(
            path = "a.b", kind = kind, enumVariants = variants, isSecret = secret, isEnvOverridden = env, multiline = multiline,
            setup = if (required) JsonPrimitive(true) else null, value = value?.let { JsonPrimitive(it) }, populated = value != null,
        )

    @Test
    fun controlMapping() {
        assertEquals(Control.Switch, controlFor(f(PropKind.Bool)))
        assertEquals(Control.Segmented, controlFor(f(PropKind.Enum, listOf("a", "b", "c"))))
        assertEquals(Control.Picker, controlFor(f(PropKind.Enum, listOf("a", "b", "c", "d"))))
        assertEquals(Control.Text, controlFor(f(PropKind.Enum)))
        assertEquals(Control.AliasPicker, controlFor(f(PropKind.AliasRef)))
        assertEquals(Control.Number, controlFor(f(PropKind.Integer)))
        assertEquals(Control.Number, controlFor(f(PropKind.Float)))
        assertEquals(Control.Text, controlFor(f(PropKind.String)))
        assertEquals(Control.MultilineText, controlFor(f(PropKind.String, multiline = true)))
        assertEquals(Control.Chips, controlFor(f(PropKind.StringArray)))
        assertEquals(Control.SecretMap, controlFor(f(PropKind.SecretMap)))
        assertEquals(Control.Secret, controlFor(f(PropKind.String, secret = true)))
        assertEquals(Control.ReadOnly, controlFor(f(PropKind.Object)))
        assertEquals(Control.ReadOnly, controlFor(f(PropKind.ObjectArray)))
        assertEquals(Control.ReadOnly, controlFor(f(PropKind.Unknown)))
    }

    @Test
    fun envOverriddenIsReadOnly() {
        assertEquals(Control.ReadOnly, controlFor(f(PropKind.Bool, env = true)))
        assertEquals(Control.ReadOnly, controlFor(f(PropKind.String, secret = true, env = true)))
    }

    @Test
    fun humanLabels() {
        assertEquals("Max tool iterations", humanLabel("agents.coder.max_tool_iterations", "agents.coder"))
        assertEquals("Limits · Max cost", humanLabel("agents.coder.limits.max_cost", "agents.coder"))
        assertEquals("Port", humanLabel("gateway.port", "somewhere.else"))
    }

    @Test
    fun checkValueRules() {
        assertNotNull(checkValue(f(PropKind.Integer), "12a"))
        assertNull(checkValue(f(PropKind.Integer), "12"))
        assertNull(checkValue(f(PropKind.Float), "3.5"))
        assertNotNull(checkValue(f(PropKind.Float), "x"))
        assertNotNull(checkValue(f(PropKind.Enum, listOf("low", "high")), "turbo"))
        assertNull(checkValue(f(PropKind.Enum, listOf("low", "high")), "low"))
        assertNotNull(checkValue(f(PropKind.String, required = true), "  "))
        assertNotNull(checkValue(f(PropKind.AliasRef), "nope", aliases = listOf("a.b")))
        assertNull(checkValue(f(PropKind.AliasRef), "nope"))
    }

    @Test
    fun draftValueTypes() {
        assertEquals(DraftValue.Plain(JsonPrimitive(true)), toDraftValue(f(PropKind.Bool), "true"))
        assertEquals(DraftValue.Plain(JsonPrimitive(42L)), toDraftValue(f(PropKind.Integer), "42"))
        assertEquals(DraftValue.Plain(JsonPrimitive(0.5)), toDraftValue(f(PropKind.Float), "0.5"))
        assertEquals(DraftValue.Plain(JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))), toDraftValue(f(PropKind.StringArray), "a, b,"))
        assertEquals(DraftValue.Plain(JsonPrimitive("hi")), toDraftValue(f(PropKind.String), "hi"))
        assertTrue(toDraftValue(f(PropKind.String, secret = true), "sk") is DraftValue.Secret)
    }

    @Test
    fun displayValues() {
        assertEquals("Set", displayValue(f(PropKind.String, secret = true).copy(populated = true)))
        assertEquals("Not set", displayValue(f(PropKind.String, secret = true)))
        assertEquals("—", displayValue(f(PropKind.String)))
        assertEquals("medium", displayValue(f(PropKind.Enum, value = "medium")))
    }

    private val templates = listOf("agents", "providers.models.anthropic", "channels.discord").map { pt.tiago.zerodroid.rpc.proto.ConfigTemplate(it) }

    @Test
    fun formPrefixForAliasFields() {
        assertEquals("agents.coder", formPrefixFor("agents.coder.model", "agents", templates))
        assertEquals("providers.models.anthropic.default", formPrefixFor("providers.models.anthropic.default.api_key", "providers.models", templates))
        assertEquals("autonomy", formPrefixFor("autonomy.level", "autonomy", templates))
        assertEquals("channels", formPrefixFor("channels.cli_enabled", "channels", templates))
    }

    @Test
    fun formExcludesDeeperAliasMaps() {
        assertTrue(belongsToForm("channels.cli_enabled", "channels", templates))
        assertFalse(belongsToForm("channels.discord.main.token", "channels", templates))
        assertTrue(belongsToForm("agents.coder.limits.max", "agents.coder", templates))
        assertFalse(belongsToForm("agents.coderx.model", "agents.coder", templates))
    }

    private fun wire(name: String) = pt.tiago.zerodroid.rpc.ZdJson.decodeFromString(
        pt.tiago.zerodroid.rpc.proto.ConfigListResult.serializer(),
        javaClass.getResourceAsStream("/fixtures/$name")!!.bufferedReader().readText(),
    ).entries

    @Test
    fun arrayItemsParseTheWireFormat() {
        val channels = wire("config_list_agents.json").first { it.path == "agents.coder.channels" }
        assertEquals(listOf("discord.coder", "telegram"), arrayItems(channels))
        assertEquals(emptyList<String>(), arrayItems(f(PropKind.StringArray, value = "<unset>").copy(populated = false)))
        assertEquals(
            DraftValue.Plain(JsonArray(listOf(JsonPrimitive("discord.coder")))),
            arrayDraft(listOf("discord.coder")),
        )
    }

    @Test
    fun unsetIsNeverShownOrEdited() {
        val prompt = wire("config_list_agents.json").first { it.path == "agents.coder.system_prompt" }
        assertEquals("—", displayValue(prompt))
        assertEquals("", editText(prompt))
        assertEquals("medium", editText(f(PropKind.Enum, value = "medium")))
    }

    @Test
    fun secretMapContainerIsNotASecretField() {
        val rows = wire("config_list_secret.json")
        val container = rows.first { it.kind == PropKind.SecretMap }
        assertEquals(Control.SecretMap, controlFor(container))
        assertEquals(listOf("X-Org"), secretMapKeys(container, rows))
    }

    @Test
    fun secretMapChildRowsStayOutOfTheForm() {
        val rows = wire("config_list_secret.json")
        val container = rows.first { it.kind == PropKind.SecretMap }
        assertFalse(isSecretMapChild(rows.first { it.path == container.path }, rows))
        assertTrue(isSecretMapChild(rows.first { it.path.endsWith("X-Org") }, rows))
    }
}
