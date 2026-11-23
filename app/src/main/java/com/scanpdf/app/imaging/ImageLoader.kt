package com.scanpdf.app.imaging

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.util.UUID
import kotlin.math.max

object ImageLoader {

    /**
     * Copies a picked image into app-private storage. Picker URIs can lose their read
     * permission later, and the private copy never leaves the app.
     */
    fun copyToPrivateStorage(context: Context, uri: Uri): File {
        val dir = File(context.cacheDir, "imports").apply { mkdirs() }
        val file = File(dir, "${UUID.randomUUID()}.img")
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException("Không mở được ảnh: $uri")
        input.use { stream -> file.outputStream().use { stream.copyTo(it) } }
        return file
    }

    /**
     * Decodes [file] upright (EXIF orientation applied) with its long side at most [maxSide].
     * Uses inSampleSize first so a 50 MP photo never has to fit in memory at full size.
     */
    fun load(file: File, maxSide: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Định dạng ảnh không được hỗ trợ" }

        var sample = 1
        val longSide = max(bounds.outWidth, bounds.outHeight)
        while (longSide / (sample * 2) >= maxSide) sample *= 2

        val decoded = BitmapFactory.decodeFile(
            file.path,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: throw IllegalStateException("Không giải mã được ảnh")

        val matrix = Matrix()
        applyExifOrientation(file, matrix)
        val decodedLong = max(decoded.width, decoded.height)
        if (decodedLong > maxSide) {
            val s = maxSide.toFloat() / decodedLong
            matrix.postScale(s, s)
        }
        if (matrix.isIdentity) return decoded

        val out = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (out !== decoded) decoded.recycle()
        return out
    }

    private fun applyExifOrientation(file: File, matrix: Matrix) {
        val orientation = try {
            ExifInterface(file.path).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL,
            )
        } catch (_: Exception) {
            ExifInterface.ORIENTATION_NORMAL
        }
        when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.postRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.postRotate(270f)
                matrix.postScale(-1f, 1f)
            }
        }
    }
}
