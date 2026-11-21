package pt.tiago.zerodroid.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import pt.tiago.zerodroid.data.OutgoingImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

private const val LONG_SIDE = 1600
private const val QUALITY = 85

/** Decodes a picked image, scales its long side to ≤ 1600 px and re-encodes it as JPEG 85. */
suspend fun loadForUpload(context: Context, uri: Uri): OutgoingImage = withContext(Dispatchers.IO) {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
        val w = info.size.width
        val h = info.size.height
        val scale = minOf(1f, LONG_SIDE.toFloat() / maxOf(w, h))
        decoder.setTargetSize(maxOf(1, (w * scale).toInt()), maxOf(1, (h * scale).toInt()))
        decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
    }
    val out = ByteArrayOutputStream()
    bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
    bitmap.recycle()
    val bytes = out.toByteArray()
    val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    OutgoingImage(sha, bytes)
}

/** A small bitmap for thumbnails, decoded off the main thread. */
suspend fun thumbnail(bytes: ByteArray, size: Int = 160): Bitmap? = withContext(Dispatchers.IO) {
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= size) sample *= 2
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()
}

suspend fun thumbnail(file: File, size: Int = 160): Bitmap? =
    withContext(Dispatchers.IO) { runCatching { file.readBytes() }.getOrNull() }?.let { thumbnail(it, size) }
