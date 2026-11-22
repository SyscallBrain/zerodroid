package pt.tiago.zerodroid.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.AgentStatusEntry
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.InitializeResult
import pt.tiago.zerodroid.ui.kit.AgentState
import java.time.Instant
import java.time.ZoneId

class HomeModelTest {
    private val zone = ZoneId.of("Europe/Lisbon")
    private val now = Instant.parse("2026-11-20T10:30:00Z") // Friday
    private val ready = ConnState.Ready(InitializeResult(1, "0.9.0", 123))

    private fun inputs(
        conn: ConnState = ready,
        host: String = "100.64.0.10",
        agents: List<AgentStatusEntry>? = emptyList(),
        crons: List<CronJob>? = emptyList(),
        cost: Double? = 1.25,
        limit: Double? = null,
    ) = HomeInputs(
        connState = conn, host = host, daemonName = "home-server",
        uptimeSeconds = 390_000, costToday = cost, costLimit = limit, activeSessions = 3,
        agents = agents, crons = crons,
    )

    @Test
    fun headlineRunningWhenReady() {
        assertEquals("ZeroClaw is running", buildHomeModel(inputs(), now, zone).headline)
        assertEquals("Connecting…", buildHomeModel(inputs(conn = ConnState.Connecting(1)), now, zone).headline)
        assertEquals("Daemon unreachable", buildHomeModel(inputs(conn = ConnState.Backoff(2_000, "net")), now, zone).headline)
    }

    @Test
    fun nextCronPicksEarliestEnabledJob() {
        val jobs = listOf(
            CronJob("a", "0 18 * * *", name = "late", nextRun = "2026-11-20T18:00:00Z"),
            CronJob("b", "0 12 * * *", name = "brief", nextRun = "2026-11-20T12:00:00Z"),
            CronJob("c", "0 11 * * *", name = "off", enabled = false, nextRun = "2026-11-20T11:00:00Z"),
        )
        val m = buildHomeModel(inputs(crons = jobs), now, zone)
        assertEquals("12:00", m.nextCron)
        assertEquals("brief", m.nextCronLabel)
    }

    @Test
    fun nextCronOnAnotherDayShowsWeekday() {
        val jobs = listOf(CronJob("a", "0 7 * * 2", name = "weekly", nextRun = "2026-11-24T07:00:00Z"))
        assertEquals("Tue 07:00", buildHomeModel(inputs(crons = jobs), now, zone).nextCron)
    }

    @Test
    fun agentsSortedNeedsYouThenWorkingThenIdle() {
        val agents = listOf(
            AgentStatusEntry("idle1", true), AgentStatusEntry("busy", true), AgentStatusEntry("asks", true), AgentStatusEntry("off", false),
        )
        val m = buildHomeModel(
            inputs(agents = agents).copy(workingAgents = setOf("busy"), approvalAgents = listOf("asks" to "shell"), pendingApprovals = 1),
            now, zone,
        )
        assertEquals(listOf("asks", "busy", "idle1", "off"), m.agents.map { it.alias })
        assertEquals(listOf(AgentState.NeedsYou, AgentState.Working, AgentState.Idle, AgentState.Offline), m.agents.map { it.state })
        assertEquals("asks", m.agents.first().alias)
        assertEquals("asks · shell", m.attentionSubtitle)
    }

    @Test
    fun costLimitHiddenWhenUnknown() {
        val m = buildHomeModel(inputs(limit = null), now, zone)
        assertNull(m.costLimit)
        assertNull(m.costFraction)
        assertEquals("$1.25", m.costToday)
        assertEquals("/ $5.00", buildHomeModel(inputs(limit = 5.0), now, zone).costLimit)
    }

    @Test
    fun offlineKeepsLastKnownValues() {
        val online = buildHomeModel(inputs(agents = listOf(AgentStatusEntry("main", true))), now, zone)
        assertTrue(online.loaded)
        val offline = buildHomeModel(
            HomeInputs(connState = ConnState.Backoff(5_000, "Connection refused"), host = "100.64.0.10", daemonName = "home-server"),
            now, zone, previous = online,
        )
        assertTrue(offline.stale)
        assertEquals(online.uptimeText, offline.uptimeText)
        assertEquals(online.costToday, offline.costToday)
        assertEquals(online.agents, offline.agents)
        assertEquals("Daemon unreachable", offline.headline)
        assertTrue(offline.hint!!.startsWith("Retrying in 5s"))
    }

    @Test
    fun pillShowsThisPhoneForLoopback() {
        assertEquals("this phone", buildHomeModel(inputs(host = "127.0.0.1"), now, zone).connection.label)
        assertEquals("home-server · Tailscale", buildHomeModel(inputs(), now, zone).connection.label)
    }

    @Test
    fun uptimeFormatting() {
        assertEquals("4d 12h", formatUptime(4 * 86_400 + 12 * 3_600 + 59))
        assertEquals("3h 5m", formatUptime(3 * 3_600 + 5 * 60))
        assertEquals("7m", formatUptime(420))
    }

    @Test
    fun notLoadedBeforeFirstFetch() {
        val m = buildHomeModel(inputs(agents = null), now, zone)
        assertFalse(m.loaded)
    }

    private val health = pt.tiago.zerodroid.rpc.ZdJson.decodeFromString(
        pt.tiago.zerodroid.rpc.proto.HealthResult.serializer(),
        javaClass.getResourceAsStream("/fixtures/health.json")!!.bufferedReader().readText(),
    )

    @Test
    fun overviewFieldsFromHealthCostAndCron() {
        val jobs = listOf(CronJob("a", "x", name = "brief", nextRun = "2026-11-20T12:00:00Z"))
        val m = buildHomeModel(
            inputs(crons = jobs).copy(process = health.process, components = health.components, costMonth = 8.1),
            now, zone,
        )
        assertEquals("142 MB", m.memory.value)
        assertEquals("4%", m.cpu.value)
        assertEquals("daemon", m.core.first().key)
        assertEquals(2, m.channels.size)
        assertEquals(listOf("12:00" to "brief"), m.upNext)
        assertEquals("$1.25", m.costToday)
        assertEquals("$8.10", m.costMonth)
    }

    @Test
    fun pinnedAgentsListedSeparately() {
        val agents = listOf(AgentStatusEntry("architect", true), AgentStatusEntry("coder", true), AgentStatusEntry("news", true))
        val m = buildHomeModel(inputs(agents = agents).copy(pins = listOf("news", "ghost")), now, zone)
        assertEquals(listOf("news"), m.pinned.map { it.alias })
        assertEquals(listOf("architect", "coder"), m.agents.map { it.alias })
    }

    @Test
    fun offlineKeepsOverviewValues() {
        val online = buildHomeModel(inputs(agents = listOf(AgentStatusEntry("main", true))).copy(process = health.process, components = health.components), now, zone)
        val offline = buildHomeModel(HomeInputs(connState = ConnState.Backoff(5_000, "x"), host = "h", daemonName = "d"), now, zone, previous = online)
        assertEquals(online.memory, offline.memory)
        assertEquals(online.core, offline.core)
        val pinnedOnline = buildHomeModel(inputs(agents = listOf(AgentStatusEntry("main", true), AgentStatusEntry("news", true))).copy(pins = listOf("news")), now, zone)
        val pinnedOffline = buildHomeModel(
            HomeInputs(connState = ConnState.Backoff(5_000, "x"), host = "h", daemonName = "d", pins = listOf("news")), now, zone, previous = pinnedOnline,
        )
        assertEquals(listOf("news"), pinnedOffline.pinned.map { it.alias })
        assertEquals(listOf("main"), pinnedOffline.agents.map { it.alias })
    }

    @Test
    fun expiredSignInAsksToSignInAgainNotUnpair() {
        val m = buildHomeModel(
            HomeInputs(connState = ConnState.AuthFailed(pt.tiago.zerodroid.data.EXPIRED_MESSAGE), host = "h", daemonName = "d"),
            java.time.Instant.EPOCH, java.time.ZoneId.of("UTC"),
        )
        assertTrue(m.hint!!, m.hint!!.contains("Sign in again"))
        assertFalse(m.hint!!, m.hint!!.contains("Unpair"))
    }

    @Test
    fun chatBannerForExpiredSignInSaysSignInAgain() {
        assertTrue(authFailedBanner(pt.tiago.zerodroid.data.EXPIRED_MESSAGE).contains("Sign in again"))
        assertTrue(authFailedBanner("refused").contains("Pair again"))
    }

    @Test
    fun chatBannerStepsAsideForTheGlobalSignInBanner() {
        assertFalse(chatConnectionBannerVisible(ConnState.AuthFailed(pt.tiago.zerodroid.data.EXPIRED_MESSAGE)))
        assertTrue(chatConnectionBannerVisible(ConnState.AuthFailed("refused")))
        assertTrue(chatConnectionBannerVisible(ConnState.Backoff(1000, "x")))
        assertFalse(chatConnectionBannerVisible(ConnState.Ready(pt.tiago.zerodroid.rpc.proto.InitializeResult(1, "0.9.0", 1))))
    }
}
