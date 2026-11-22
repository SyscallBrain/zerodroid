package pt.tiago.zerodroid.data.sop

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolSuggestionsTest {
    @Test
    fun mergeDedupesAndSorts() {
        assertEquals(listOf("browser", "shell", "weather"), ToolSuggestions.merge(setOf("weather", "shell"), setOf("shell", "browser")))
    }

    @Test
    fun filterMatchesSubstringAndExcludesChosen() {
        val all = listOf("file_edit", "file_read", "shell", "weather")
        assertEquals(listOf("file_read"), ToolSuggestions.filter(all, "FILE", chosen = setOf("file_edit")))
        assertEquals(listOf("shell", "weather"), ToolSuggestions.filter(all, "", chosen = setOf("file_edit", "file_read")))
    }
}
