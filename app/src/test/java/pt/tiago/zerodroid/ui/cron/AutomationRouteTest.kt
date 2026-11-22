package pt.tiago.zerodroid.ui.cron

import org.junit.Assert.assertEquals
import org.junit.Test

class AutomationRouteTest {
    @Test
    fun stackRoundTrips() {
        val stack = listOf(AutomationRoute.List, AutomationRoute.Detail("a"), AutomationRoute.Edit(null))
        assertEquals(stack, restoreAutomationStack(saveAutomationStack(stack)))
        val edit = listOf(AutomationRoute.List, AutomationRoute.Detail("a:b"), AutomationRoute.Edit("a:b"))
        assertEquals(edit, restoreAutomationStack(saveAutomationStack(edit)))
    }

    @Test
    fun outputIsNotRestored() {
        val stack = listOf(AutomationRoute.List, AutomationRoute.Detail("a"), AutomationRoute.Output("Run", "long text"))
        assertEquals(listOf(AutomationRoute.List, AutomationRoute.Detail("a")), restoreAutomationStack(saveAutomationStack(stack)))
    }

    @Test
    fun savedJobReplacesEditorWithItsDetail() {
        val fromDetail = listOf(AutomationRoute.List, AutomationRoute.Detail("a"), AutomationRoute.Edit("a"))
        assertEquals(listOf(AutomationRoute.List, AutomationRoute.Detail("a")), afterSave(fromDetail, "a"))
        val fresh = listOf(AutomationRoute.List, AutomationRoute.Edit(null))
        assertEquals(listOf(AutomationRoute.List, AutomationRoute.Detail("n")), afterSave(fresh, "n"))
    }
}
