package com.dishii.soh;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small thumbnail loader for the mod browser: 3 download threads, an in-memory LRU cache. */
final class ImageLoader {

    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(12 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getByteCount(); }
    };
    private static final ExecutorService POOL = Executors.newFixedThreadPool(3);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static volatile int generation = 0;

    /** Drop the cache and any queued fetches (when the launcher closes, before the game uses the RAM). */
    static void reset() {
        generation++;
        CACHE.evictAll();
    }

    static void load(ImageView view, String url, int maxWidth) {
        view.setTag(url);
        if (url == null) { view.setImageDrawable(null); return; }
        Bitmap cached = CACHE.get(url);
        if (cached != null) { view.setImageBitmap(cached); return; }
        view.setImageDrawable(null);
        final int gen = generation;
        POOL.execute(() -> {
            if (gen != generation || !url.equals(view.getTag())) return; // stale request
            Bitmap bmp = fetch(url, maxWidth);
            if (gen != generation) return;
            if (bmp == null) return;
            CACHE.put(url, bmp);
            MAIN.post(() -> { if (url.equals(view.getTag())) view.setImageBitmap(bmp); });
        });
    }

    private static Bitmap fetch(String url, int maxWidth) {
        try {
            HttpURLConnection c = GameBananaApi.open(url);
            c.setRequestProperty("Accept", "image/*");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[16384];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                byte[] data = out.toByteArray();
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inJustDecodeBounds = true;
                BitmapFactory.decodeByteArray(data, 0, data.length, o);
                int sample = 1;
                while (o.outWidth / (sample * 2) >= maxWidth) sample *= 2;
                o = new BitmapFactory.Options();
                o.inSampleSize = sample;
                o.inPreferredConfig = Bitmap.Config.RGB_565; // half the memory; fine for thumbnails
                return BitmapFactory.decodeByteArray(data, 0, data.length, o);
            } finally {
                c.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private ImageLoader() { }
}
