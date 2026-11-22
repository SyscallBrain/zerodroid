package pt.tiago.zerodroid.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CanvasRouteTest {
    @Test
    fun backFromEachRoute() {
        assertNull(canvasBack(CanvasRoute.List))
        assertNull(canvasBack(CanvasRoute.ChatList("s")))
        assertEquals(CanvasRoute.List, canvasBack(CanvasRoute.View("c")))
        assertNull(canvasBack(CanvasRoute.View("c", fromChat = true)))
        assertEquals(CanvasRoute.ChatList("s"), canvasBack(CanvasRoute.View("c", chatSession = "s")))
    }
}
