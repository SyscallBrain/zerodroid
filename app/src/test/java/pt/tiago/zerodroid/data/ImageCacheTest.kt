package pt.tiago.zerodroid.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageCacheTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)
    private fun img(sha: String, size: Int) = OutgoingImage(sha, ByteArray(size) { 1 })

    @Test
    fun checkImagesLimits() {
        assertNull(checkImages(listOf(img(a, 10))))
        assertEquals("Up to 4 images per message", checkImages((1..5).map { img("$it".repeat(64), 1) }))
        assertEquals("Images are too large to send together", checkImages(listOf(img(a, 5 * 1024 * 1024), img(b, 5 * 1024 * 1024))))
    }

    @Test
    fun attachmentsAreInlineBase64() {
        val att = toAttachments(listOf(OutgoingImage(a, byteArrayOf(1, 2, 3))))
        assertEquals("aaaaaaaaaaaa.jpg", att.single().filename)
        assertEquals("AQID", att.single().dataB64)
        assertEquals("image/jpeg", att.single().mimeType)
        assertFalse(OutgoingImage(a, byteArrayOf(9)).toString().contains("[9"))
    }

    @Test
    fun historyMarkersSplitIntoImagesAndText() {
        val (text, shas) = splitMarkers("look [IMAGE:/ws/attachments/$a.jpg] and [IMAGE:/ws/attachments/$b.jpeg]")
        assertEquals("look and", text)
        assertEquals(listOf(a, b), shas)
        assertEquals("plain" to emptyList<String>(), splitMarkers("plain"))
    }

    @Test
    fun cacheEvictsOldest() {
        val dir = kotlin.io.path.createTempDirectory("imgcache").toFile()
        val cache = ImageCache(dir, capBytes = 10)
        cache.put(img(a, 6)); cache.file(a)!!.setLastModified(1_000)
        cache.put(img(b, 6))
        assertNull(cache.file(a))
        assertNotNull(cache.file(b))
        assertTrue(cache.file(b)!!.length() == 6L)
    }

    @Test
    fun pickingMoreThanTheFreeSlotsKeepsTheFirstOnesWithANotice() {
        val have = listOf(img(a, 1))
        val picked = (1..4).map { img("$it".repeat(64), 1) }
        val (merged, notice) = mergePicked(have, picked)
        assertEquals(4, merged.size)
        assertEquals(a, merged.first().sha256)
        assertEquals("Only 4 images per message; 1 not added", notice)
        assertEquals(3, freeImageSlots(have))
        assertEquals(null, mergePicked(emptyList(), listOf(img(a, 1))).second)
    }
}
