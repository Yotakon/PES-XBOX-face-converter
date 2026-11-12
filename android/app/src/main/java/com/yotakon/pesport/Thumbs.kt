package com.yotakon.pesport

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.widget.ImageView
import com.yotakon.pesport.core.Texture
import java.util.concurrent.Executors

/** Carga de previas en segundo plano con caché, para listas largas de FACES y HAIRS. */
class Thumbs {
    /** Devuelve una sección de textura de PES (formato de [Texture]). */
    fun interface Source {
        @Throws(Exception::class)
        fun texture(): ByteArray
    }

    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val pool = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    /** Muestra en [iv] la previa [key]; con key null deja la imagen vacía. */
    fun load(iv: ImageView, key: String?, src: Source?) {
        iv.tag = key
        if (key == null || src == null) {
            iv.setImageDrawable(null)
            return
        }
        cache.get(key)?.let {
            iv.setImageBitmap(it)
            return
        }
        iv.setImageDrawable(null)
        pool.execute {
            val bm = try {
                bitmap(src.texture(), 2)
            } catch (e: Exception) {
                null
            }
            main.post {
                if (bm != null) cache.put(key, bm)
                if (key == iv.tag) iv.setImageBitmap(bm)
            }
        }
    }

    fun clear() = cache.evictAll()

    fun shutdown() = pool.shutdownNow()

    companion object {
        /** Bitmap de la textura, ampliado [scale] veces sin suavizado para que se vean los píxeles. */
        fun bitmap(tex: ByteArray, scale: Int): Bitmap {
            val argb = Texture.toArgb(tex)
            val w = argb[0]
            val h = argb[1]
            val b = Bitmap.createBitmap(argb.copyOfRange(2, 2 + w * h), w, h, Bitmap.Config.ARGB_8888)
            val scaled = Bitmap.createScaledBitmap(b, w * scale, h * scale, false)
            if (scaled !== b) b.recycle()
            return scaled
        }
    }
}
