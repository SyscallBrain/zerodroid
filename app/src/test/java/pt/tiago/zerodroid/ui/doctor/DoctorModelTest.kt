package pt.tiago.zerodroid.ui.doctor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.data.doctor.DoctorState
import pt.tiago.zerodroid.rpc.proto.DiagResult
import pt.tiago.zerodroid.rpc.proto.DoctorRunResult
import pt.tiago.zerodroid.rpc.proto.DoctorSummary
import pt.tiago.zerodroid.ui.config.ConfigRoute
import pt.tiago.zerodroid.ui.config.jumpStack
import pt.tiago.zerodroid.ui.kit.Tone

class DoctorModelTest {
    private fun warn(msg: String, cat: String = "config") = DiagResult("warn", cat, msg)
    private fun open(route: ConfigRoute) = DoctorFix.Open(route)

    @Test
    fun shortModelLabelOpensTheProviderEntry() {
        assertEquals(
            open(ConfigRoute.Entry("providers.models", "providers.models.openai.ss", "ss")),
            doctorFix(warn("openai.ss: no model configured")),
        )
        assertEquals(
            open(ConfigRoute.Entry("providers.models", "providers.models.anthropic.default", "default")),
            doctorFix(warn("anthropic.default: no api_key set (may rely on env vars or model_provider defaults)")),
        )
    }

    @Test
    fun quotedModelProviderLabelIsFound() {
        assertEquals(
            open(ConfigRoute.Entry("providers.models", "providers.models.openrouter.main", "main")),
            DiagResult("error", "config", "model_provider \"openrouter.main\" is invalid: unknown family").let(::doctorFix),
        )
    }

    @Test
    fun channelWinsOverProviderWords() {
        assertEquals(
            open(ConfigRoute.Entry("channels", "channels.discord.gnosis", "gnosis")),
            doctorFix(warn("discord.gnosis: channel has no provider bound")),
        )
    }

    @Test
    fun fullPathWithFieldOpensThatField() {
        assertEquals(
            open(ConfigRoute.Field("channels", "channels.telegram.default", "channels.telegram.default.bot_token")),
            doctorFix(warn("enabled alias has no token: set channels.telegram.default.bot_token")),
        )
        assertEquals(
            open(ConfigRoute.Entry("providers.tts", "providers.tts.openai.voice", "voice")),
            doctorFix(warn("providers.tts.openai.voice: no api_key set — this provider will NOT register; set `[providers.tts.openai.voice].api_key`")),
        )
    }

    @Test
    fun okFindingsAndUnnamedOnesOutsideConfigHaveNoFix() {
        assertNull(doctorFix(DiagResult("ok", "config", "openai.ss: model: gpt")))
        assertNull(doctorFix(warn("low disk space: only 80 MB available", "environment")))
        assertNull(doctorFix(warn("git not found in PATH", "cli-tools")))
    }

    @Test
    fun configFindingWithoutEntityFallsBackToConfigHome() {
        assertEquals(DoctorFix.ConfigHome, doctorFix(DiagResult("error", "config", "config file not found: /x/config.toml")))
        assertEquals(DoctorFix.ConfigHome, doctorFix(warn("directory missing: /w", "workspace")))
    }

    @Test
    fun jumpStackWalksSectionFamilyEntry() {
        val entry = ConfigRoute.Entry("channels", "channels.discord.gnosis", "gnosis")
        assertEquals(listOf(ConfigRoute.Section("channels"), ConfigRoute.Family("channels", "channels.discord"), entry), jumpStack(entry))
        val field = ConfigRoute.Field("providers.models", "providers.models.openai.ss", "providers.models.openai.ss.api_key")
        assertEquals(
            listOf(ConfigRoute.Section("providers.models"), ConfigRoute.Family("providers.models", "providers.models.openai"), field),
            jumpStack(field),
        )
    }

    @Test
    fun jumpStackKeepsOneTierAndSectionFields() {
        val agent = ConfigRoute.Entry("agents", "agents.keryx", "keryx")
        assertEquals(listOf(ConfigRoute.Section("agents"), agent), jumpStack(agent))
        val sop = ConfigRoute.Field("sop", "", "sop.sops_dir")
        assertEquals(listOf(ConfigRoute.Section("sop"), sop), jumpStack(sop))
    }

    @Test
    fun groupsKeepDaemonOrderAndFilter() {
        val rs = listOf(DiagResult("ok", "config", "a"), DiagResult("warn", "daemon", "b"), DiagResult("error", "config", "c"))
        assertEquals(listOf("daemon" to listOf(rs[1]), "config" to listOf(rs[2])), groupFindings(rs, setOf("warn", "error")))
    }

    private fun state(errors: Int, warnings: Int, ok: Int = 10, running: Boolean = false) = DoctorState(
        daemon = "d", result = DoctorRunResult(summary = DoctorSummary(ok, warnings, errors)), at = 0, running = running,
    )

    @Test
    fun tileSaysWorstFirst() {
        val time = { _: Long -> "10:00" }
        assertEquals(DoctorTile("2", "errors", "1 warning · Checked 10:00", pill = "Errors" to Tone.Danger), doctorTile(state(2, 1), "d", time))
        assertEquals(DoctorTile("1", "warning", "Checked 10:00", pill = "Warnings" to Tone.Attention), doctorTile(state(0, 1), "d", time))
        assertEquals(DoctorTile("OK", null, "10 checks passed · Checked 10:00"), doctorTile(state(0, 0), "d", time))
        val again = doctorTile(state(0, 0, running = true), "d", time)
        assertEquals(true, again.running)
        assertEquals("10 checks passed · Checked 10:00", again.caption)
    }

    @Test
    fun tileIgnoresAnotherDaemonsRun() {
        assertEquals(DoctorTile("—", null, "Tap to check the daemon"), doctorTile(state(3, 0), "other") { "" })
        val first = doctorTile(DoctorState(running = true), "d") { "" }
        assertEquals(true, first.loading)
        assertEquals(true, first.running)
    }
}
