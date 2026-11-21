package pt.tiago.zerodroid.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.HealthResult
import pt.tiago.zerodroid.rpc.proto.ProcessStats
import java.time.Instant
import java.time.ZoneId

class OverviewTest {
    private val health = ZdJson.decodeFromString(
        HealthResult.serializer(),
        javaClass.getResourceAsStream("/fixtures/health.json")!!.bufferedReader().readText(),
    )

    @Test
    fun decodesHealthFixture() {
        assertEquals(156000L, health.uptimeSeconds)
        assertEquals(6, health.components.size)
        assertEquals(8, health.process!!.numCpus)
    }

    @Test
    fun memoryMetricFormats() {
        val m = memoryMetric(health.process)
        assertEquals("142 MB", m.value)
        assertEquals("4% of 3.6 GB", m.caption)
        assertEquals(0.0385f, m.fraction!!, 0.001f)
        assertEquals("1.2 GB", formatBytes(1_288_490_189))
        assertEquals("—", formatBytes(0))
    }

    @Test
    fun cpuNormalisedByCores() {
        assertEquals("4%", cpuMetric(health.process).value)
        assertEquals("100%", cpuMetric(ProcessStats(cpuPercent = 900.0, numCpus = 8)).value)
    }

    @Test
    fun missingProcessShowsDashes() {
        assertNull(memoryMetric(null).value)
        assertNull(memoryMetric(ProcessStats()).value)
        assertNull(cpuMetric(ProcessStats(cpuPercent = null, numCpus = 8)).value)
    }

    @Test
    fun componentsSplitAndOrdered() {
        val (core, channels) = systemComponents(health.components)
        assertEquals(listOf("daemon", "gateway", "scheduler", "mqtt"), core.map { it.key })
        assertEquals(Health.Error, core[2].health)
        assertEquals("db locked · restarted 2×", core[2].detail)
        assertEquals(Health.Warn, core[3].health)
        assertNull(core[0].detail)
        assertEquals(listOf("discord · coder", "telegram · main"), channels.map { it.label })
        assertEquals("401 unauthorized", channels[1].detail)
    }

    @Test
    fun upNextTakesThreeEarliest() {
        val now = Instant.parse("2026-11-20T10:00:00Z")
        val jobs = (1..5).map { CronJob("j$it", "x", name = "job$it", nextRun = "2026-11-20T1${it}:00:00Z") } +
            CronJob("off", "x", name = "off", enabled = false, nextRun = "2026-11-20T10:30:00Z")
        val next = upNext(jobs, now, ZoneId.of("UTC"))
        assertEquals(listOf("job1", "job2", "job3"), next.map { it.second })
        assertEquals("11:00", next[0].first)
    }

    @Test
    fun smallMemoryShareKeepsADecimalAndTheTotal() {
        val m = memoryMetric(ProcessStats(rssBytes = 33_000_000, systemRamTotalBytes = 7_797_747_712))
        assertEquals("31 MB", m.value)
        assertEquals("0.4% of 7.3 GB", m.caption)
        assertEquals("4% of 3.6 GB", memoryMetric(health.process).caption)
    }

    @Test
    fun healthyChannelsFoldIntoACount() {
        val (_, channels) = systemComponents(health.components)
        val folded = foldChannels(channels)
        assertEquals(listOf("telegram · main"), folded.problems.map { it.label })
        assertEquals(1, folded.healthy)
    }
}
