package ru.ballistica.target

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File

/** Складываем кадры и цифры в Downloads/мишень-отладка, чтобы можно было разобрать со стороны. */
object Debug {

    const val DIR = "мишень-отладка"

    fun save(ctx: Context, name: String, bytes: ByteArray, mime: String) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/$DIR")
                }
                val uri: Uri? = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let { ctx.contentResolver.openOutputStream(it)?.use { out -> out.write(bytes) } }
            } else {
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DIR)
                dir.mkdirs()
                File(dir, name).writeBytes(bytes)
            }
        } catch (e: Exception) {
            // отладка не должна ронять приложение
        }
    }

    fun jpeg(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray()
    }

    fun png(bmp: Bitmap): ByteArray {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
        return out.toByteArray()
    }

    /** Мишень с подсветкой всех пикселей, которые детектор счёл изменившимися. */
    fun maskImage(base: Detector.Gray, mask: BooleanArray?): Bitmap {
        val bmp = Bitmap.createBitmap(base.w, base.h, Bitmap.Config.ARGB_8888)
        val px = IntArray(base.w * base.h)
        for (i in px.indices) {
            val v = base.p[i]
            px[i] = if (mask != null && mask[i]) {
                (0xFF shl 24) or (0xFF shl 16) or (0x30 shl 8) or 0x30
            } else {
                (0xFF shl 24) or (v shl 16) or (v shl 8) or v
            }
        }
        bmp.setPixels(px, 0, base.w, 0, 0, base.w, base.h)
        return bmp
    }

    private val log = StringBuilder()

    fun log(ctx: Context, line: String) {
        log.append(line).append('\n')
        save(ctx, "отладка.txt", log.toString().toByteArray(), "text/plain")
    }

    fun grayImage(g: Detector.Gray): Bitmap {
        val bmp = Bitmap.createBitmap(g.w, g.h, Bitmap.Config.ARGB_8888)
        val px = IntArray(g.w * g.h)
        for (i in px.indices) {
            val v = g.p[i]
            px[i] = (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        bmp.setPixels(px, 0, g.w, 0, 0, g.w, g.h)
        return bmp
    }
}
