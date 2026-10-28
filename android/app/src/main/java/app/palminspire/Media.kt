package app.palminspire

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayOutputStream

/** 附件读取：图片压到长边 2560、JPEG 85；其他文件原样，单件 ≤ 20 MB。 */
object Media {
    const val FILE_MAX = 20 * 1024 * 1024
    private const val LONG_EDGE = 2560

    data class Picked(val bytes: ByteArray, val name: String, val mime: String)

    fun read(ctx: Context, uri: Uri): Picked {
        val cr = ctx.contentResolver
        var name = "file"
        cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) name = c.getString(0) ?: name
        }
        val mime = cr.getType(uri) ?: "application/octet-stream"
        val raw = cr.openInputStream(uri)?.use { it.readBytes() } ?: error("读不到文件")
        if (mime.startsWith("image/") && mime != "image/gif") {
            compress(raw)?.let { return Picked(it, name.substringBeforeLast('.') + ".jpg", "image/jpeg") }
        }
        require(raw.size <= FILE_MAX) { "文件超过 20 MB" }
        return Picked(raw, name, mime)
    }

    fun compress(raw: ByteArray): ByteArray? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= LONG_EDGE) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(raw, 0, raw.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val scale = LONG_EDGE.toFloat() / maxOf(bmp.width, bmp.height)
        val out = if (scale < 1f) Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true) else bmp
        return ByteArrayOutputStream().also { out.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }
}
