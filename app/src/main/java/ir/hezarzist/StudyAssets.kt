package ir.hezarzist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream
import java.security.MessageDigest

/** All study content is packaged in the APK. No cache or remote source can override it. */
object StudyAssets {
    fun version(@Suppress("UNUSED_PARAMETER") context: Context): String = "bundled-offline-v3"
    fun snapshot(@Suppress("UNUSED_PARAMETER") context: Context): String = "bundled"

    fun safePath(path: String): Boolean = path.isNotBlank() && path.length <= 240 &&
        !path.startsWith('/') && !path.contains('\\') && !path.contains('%') &&
        path.split('/').all { it.isNotBlank() && it != "." && it != ".." && it.matches(Regex("[A-Za-z0-9_.-]+")) }

    fun open(context: Context, path: String, snapshot: String? = null): InputStream {
        require(safePath(path)) { "مسیر فایل محتوا معتبر نیست." }
        require(snapshot == null || snapshot == "bundled") { "این نوبت متعلق به بانک قبلی است و در بایگانی نگهداری می‌شود." }
        return context.assets.open(path)
    }

    /** Decode bounded images to avoid allocating the full source bitmap on small devices. */
    fun decodeBitmap(context: Context, path: String, snapshot: String? = null, maxDimension: Int = 1600): Bitmap? = runCatching {
        require(maxDimension in 128..2048)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(context, path, snapshot).use { BitmapFactory.decodeStream(it, null, options) }
        require(options.outWidth in 1..8000 && options.outHeight in 1..8000 && options.outWidth.toLong() * options.outHeight <= 24_000_000)
        options.inSampleSize = 1
        while(options.outWidth / options.inSampleSize > maxDimension || options.outHeight / options.inSampleSize > maxDimension) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        open(context, path, snapshot).use { BitmapFactory.decodeStream(it, null, options) }
    }.getOrNull()

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
