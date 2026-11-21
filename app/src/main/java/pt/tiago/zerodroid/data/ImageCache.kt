package pt.tiago.zerodroid.data

import pt.tiago.zerodroid.rpc.proto.PromptAttachment
import java.io.File

const val MAX_IMAGES = 4
const val MAX_IMAGE_BYTES_TOTAL = 8L * 1024 * 1024

/** A resized JPEG ready to send. [sha256] is the hex digest of [bytes], which the daemon also uses as its file name. */
class OutgoingImage(val sha256: String, val bytes: ByteArray) {
    override fun equals(other: Any?) = other is OutgoingImage && other.sha256 == sha256
    override fun hashCode() = sha256.hashCode()
    override fun toString() = "OutgoingImage(${sha256.take(12)}, ${bytes.size} bytes)"
}

fun checkImages(images: List<OutgoingImage>): String? = when {
    images.size > MAX_IMAGES -> "Up to $MAX_IMAGES images per message"
    images.sumOf { it.bytes.size.toLong() } > MAX_IMAGE_BYTES_TOTAL -> "Images are too large to send together"
    else -> null
}

fun freeImageSlots(current: List<OutgoingImage>): Int = (MAX_IMAGES - current.size).coerceAtLeast(0)

/** Adds picked images up to the limit; returns the merged list and a notice when some did not fit. */
fun mergePicked(current: List<OutgoingImage>, picked: List<OutgoingImage>): Pair<List<OutgoingImage>, String?> {
    val all = (current + picked).distinct()
    val kept = all.take(MAX_IMAGES)
    val dropped = all.size - kept.size
    return kept to if (dropped > 0) "Only $MAX_IMAGES images per message; $dropped not added" else null
}

fun toAttachments(images: List<OutgoingImage>): List<PromptAttachment> = images.map {
    PromptAttachment("${it.sha256.take(12)}.jpg", java.util.Base64.getEncoder().encodeToString(it.bytes))
}

/** `[IMAGE:<workspace path>]`; the stored file is named by the payload's full SHA-256 (rpc/attachments.rs). */
val IMAGE_MARKER = Regex("""\[IMAGE:[^\]]*?([0-9a-f]{64})[^\]]*]""")

/** The text with image markers removed, and the image digests in order. */
fun splitMarkers(text: String): Pair<String, List<String>> {
    val shas = IMAGE_MARKER.findAll(text).map { it.groupValues[1] }.toList()
    if (shas.isEmpty()) return text to emptyList()
    val cleaned = text.replace(IMAGE_MARKER, " ").replace(Regex("""[ \t]{2,}"""), " ").trim()
    return cleaned to shas
}

/** Copies of images this phone sent, so thumbnails survive a reload (the daemon cannot send them back). */
class ImageCache(private val dir: File, private val capBytes: Long = 100L * 1024 * 1024) {
    init { dir.mkdirs() }

    private fun fileFor(sha: String) = File(dir, "$sha.jpg")

    fun put(img: OutgoingImage) {
        val f = fileFor(img.sha256)
        if (!f.exists()) f.writeBytes(img.bytes)
        f.setLastModified(System.currentTimeMillis())
        evict()
    }

    fun file(sha256: String): File? = fileFor(sha256).takeIf { it.exists() }

    private fun evict() {
        val files = dir.listFiles()?.sortedBy { it.lastModified() }?.toMutableList() ?: return
        var total = files.sumOf { it.length() }
        while (total > capBytes && files.size > 1) {
            val oldest = files.removeAt(0)
            total -= oldest.length()
            oldest.delete()
        }
    }
}
