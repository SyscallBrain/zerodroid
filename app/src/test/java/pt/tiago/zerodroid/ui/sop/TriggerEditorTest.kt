package pt.tiago.zerodroid.ui.sop

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.TriggerSourceRegistryDto

class TriggerEditorTest {
    private val registry = ZdJson.decodeFromString(
        TriggerSourceRegistryDto.serializer(),
        """{"sources":["manual","cron","webhook","filesystem"],
           "bound":[
             {"source":"cron","fields":[{"name":"expression","kind":"expression"}]},
             {"source":"webhook","fields":[{"name":"path","kind":"text"}]},
             {"source":"filesystem","fields":[{"name":"path","kind":"text"},{"name":"events","options":["create","modify"],"multi":true,"kind":"list"}]}],
           "channels":[{"channel":"telegram","aliases":[{"alias":"main"}],"configured":true}]}""",
    )

    private fun obj(s: String) = ZdJson.parseToJsonElement(s).jsonObject

    @Test
    fun blankTriggerHasDeclaredFields() {
        assertEquals(obj("""{"type":"manual"}"""), blankTrigger("manual", registry))
        assertEquals(obj("""{"type":"cron","expression":""}"""), blankTrigger("cron", registry))
        assertEquals(obj("""{"type":"filesystem","path":"","events":[]}"""), blankTrigger("filesystem", registry))
        assertEquals(obj("""{"type":"channel","channel":"telegram"}"""), blankTrigger("telegram", registry))
    }

    @Test
    fun triggerLabels() {
        assertEquals("Manual", triggerLabel(obj("""{"type":"manual"}""")))
        assertEquals("Schedule · 0 9 * * *", triggerLabel(obj("""{"type":"cron","expression":"0 9 * * *"}""")))
        assertEquals("Telegram · main", triggerLabel(obj("""{"type":"channel","channel":"telegram","alias":"main"}""")))
        assertEquals("Telegram", triggerLabel(obj("""{"type":"channel","channel":"telegram"}""")))
        assertEquals("Webhook · /hook", triggerLabel(obj("""{"type":"webhook","path":"/hook"}""")))
        assertEquals("Peripheral", triggerLabel(obj("""{"type":"peripheral"}""")))
    }

    @Test
    fun sourceOptionsListChannelsByName() {
        assertEquals(listOf("manual", "cron", "webhook", "filesystem", "telegram"), triggerSourceOptions(registry).map { it.first })
    }
}
