package com.yotakon.pesport;

import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import com.yotakon.pesport.core.Texture;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Carga de previas en segundo plano con caché, para listas largas de FACES y HAIRS. */
final class Thumbs {
    interface Source {
        /** Sección de textura de PES (formato de {@link Texture}). */
        byte[] texture() throws Exception;
    }

    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(24 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };
    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());

    /** Muestra en {@code iv} la previa {@code key}; con key null deja la imagen vacía. */
    void load(final ImageView iv, final String key, final Source src) {
        iv.setTag(key);
        if (key == null || src == null) {
            iv.setImageDrawable(null);
            return;
        }
        Bitmap cached = cache.get(key);
        if (cached != null) {
            iv.setImageBitmap(cached);
            return;
        }
        iv.setImageDrawable(null);
        pool.execute(() -> {
            Bitmap bm;
            try {
                bm = bitmap(src.texture());
            } catch (Exception e) {
                bm = null;
            }
            final Bitmap result = bm;
            main.post(() -> {
                if (result != null) {
                    cache.put(key, result);
                }
                if (key.equals(iv.getTag())) {
                    iv.setImageBitmap(result);
                }
            });
        });
    }

    void clear() {
        cache.evictAll();
    }

    void shutdown() {
        pool.shutdown();
    }

    /** Bitmap de la textura, ampliado x2 sin suavizado para que se vean los pixeles. */
    static Bitmap bitmap(byte[] tex) {
        int[] argb = Texture.toArgb(tex);
        int w = argb[0], h = argb[1];
        int[] px = new int[w * h];
        System.arraycopy(argb, 2, px, 0, px.length);
        Bitmap b = Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        Bitmap scaled = Bitmap.createScaledBitmap(b, w * 2, h * 2, false);
        if (scaled != b) {
            b.recycle();
        }
        return scaled;
    }
}
