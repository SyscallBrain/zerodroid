package pt.tiago.zerodroid.data

import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.SessionEntry

class SearchModelTest {
    private fun s(id: String, last: String, name: String? = null, alias: String? = "main") =
        SessionEntry(sessionId = id, sessionKey = "rpc_$id", createdAt = "t", lastActivity = last, messageCount = 1, agentAlias = alias, name = name)

    private val all = listOf(
        s("a1", "2026-11-20T10:00:00Z", name = "Trip planning"),
        s("b2", "2026-11-20T12:00:00Z", alias = "coder"),
        s("c3", "2026-11-20T11:00:00Z", name = "Budget"),
    )

    @Test
    fun renamedSessionFoundByName() {
        assertEquals(listOf("a1"), mergeSessionSearch(all, emptyList(), "trip").map { it.sessionId })
    }

    @Test
    fun contentHitsMergedWithoutDuplicates() {
        val hits = listOf(all[2], all[0])
        assertEquals(listOf("c3", "a1"), mergeSessionSearch(all, hits, "trip").map { it.sessionId })
    }

    @Test
    fun blankQueryKeepsAllAndUnnamedMatchByAlias() {
        assertEquals(all, mergeSessionSearch(all, emptyList(), "  "))
        assertEquals(listOf("b2"), mergeSessionSearch(all, emptyList(), "COD").map { it.sessionId })
        assertEquals(listOf("c3"), mergeSessionSearch(all, emptyList(), "c3").map { it.sessionId })
    }

    @Test
    fun agentFilterIsCaseInsensitiveAndPinnedFirst() {
        val agents = listOf("architect", "coder", "codex", "news").map { AgentEntry(it, true, emptyList()) }
        assertEquals(listOf("codex", "coder"), filterAgents(agents, "COD", listOf("codex")).map { it.alias })
        assertEquals(listOf("news", "architect", "coder", "codex"), filterAgents(agents, "", listOf("news")).map { it.alias })
    }

    @Test
    fun trailingTableReferenceRemoved() {
        assertEquals("Notion integration configuration", cleanDescription("Notion integration configuration (`[notion]`).", "notion"))
        assertEquals("Delegate tool global default configuration", cleanDescription("Delegate tool global default configuration (`[delegate]`)", "delegate"))
    }

    @Test
    fun descriptionOnlyReferenceFallsBackToKey() {
        assertEquals("Notion", cleanDescription("(`[notion]`)", "notion"))
        assertEquals("Claude code", cleanDescription("", "claude_code"))
    }

    @Test
    fun midTextCodeSpanKept() {
        assertEquals("Uses `model` field", cleanDescription("Uses `model` field", "x"))
    }

    @Test
    fun tableReferencesRemovedAnywhereAndSummaryIsFirstSentence() {
        assertEquals(
            "Optional model-visible built-ins. The default set is used.",
            cleanDescription("Optional model-visible built-ins (`[tools]`). The default set is used.", "tools"),
        )
        assertEquals("Browser delegation configuration", summaryOf("Browser delegation configuration (`[browser_delegate]`).\n\nUses a `delegate` agent.", "browser_delegate"))
        assertEquals("Text browser tool configuration", summaryOf("Text browser tool configuration", "x"))
    }

    @Test
    fun typeSummaryShowsNotConfiguredWithoutDescription() {
        assertEquals("2 configured", typeSummary(2, "Anthropic", "providers.models.anthropic"))
        assertEquals("Anthropic", typeSummary(0, "Anthropic (`[anthropic]`)", "providers.models.anthropic"))
        assertEquals("Not configured", typeSummary(0, "", "providers.models.anthropic"))
    }
}
