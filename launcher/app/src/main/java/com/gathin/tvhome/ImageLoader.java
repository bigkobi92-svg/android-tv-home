package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Small image loader for posters and backdrops: disk cache (7 days), a memory cache sized for a 1.4 GB TV,
 * and decoding at the size the view needs. Views that get recycled drop late results via their tag.
 */
final class ImageLoader {

    private static final long DISK_TTL_MS = 7 * 24 * 60 * 60_000L;
    private static ImageLoader instance;

    private final File dir;
    private final ExecutorService io = Executors.newFixedThreadPool(2);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> mem = new LruCache<String, Bitmap>(14 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap b) {
            return b.getByteCount();
        }
    };

    static synchronized ImageLoader get(Context ctx) {
        if (instance == null) instance = new ImageLoader(ctx.getApplicationContext());
        return instance;
    }

    private ImageLoader(Context ctx) {
        dir = new File(ctx.getCacheDir(), "img");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        io.execute(() -> {
            File[] files = dir.listFiles();
            if (files == null) return;
            long now = System.currentTimeMillis();
            for (File f : files) {
                //noinspection ResultOfMethodCallIgnored
                if (now - f.lastModified() > DISK_TTL_MS) f.delete();
            }
        });
    }

    interface Callback {
        void done(Bitmap b);
    }

    /** Loads url into the view at about w x h pixels; fades it in when it arrives from disk or network. */
    void into(ImageView v, String url, int w, int h) {
        v.setTag(R.id.image_url, url);
        if (url == null) {
            v.setImageDrawable(null);
            return;
        }
        String key = url + "@" + w;
        Bitmap hit = mem.get(key);
        if (hit != null) {
            v.setImageBitmap(hit);
            v.setAlpha(1f);
            return;
        }
        v.setImageDrawable(null);
        load(url, w, h, b -> {
            if (b == null || !url.equals(v.getTag(R.id.image_url))) return;
            v.setImageBitmap(b);
            v.setAlpha(0f);
            v.animate().alpha(1f).setDuration(250).start();
        });
    }

    void load(String url, int w, int h, Callback cb) {
        String key = url + "@" + w;
        Bitmap hit = mem.get(key);
        if (hit != null) {
            cb.done(hit);
            return;
        }
        io.execute(() -> {
            File f = fetch(url);
            Bitmap b = f != null ? decode(f, w, h) : null;
            if (b != null) mem.put(key, b);
            main.post(() -> cb.done(b));
        });
    }

    /** Downloads into the disk cache without decoding, so the next image in a rotation is ready. */
    void prefetch(String url) {
        if (url != null) io.execute(() -> fetch(url));
    }

    private File fetch(String url) {
        File f = new File(dir, Integer.toHexString(url.hashCode()) + ".img");
        if (f.exists() && f.length() > 0) return f;
        byte[] body = Net.get(url);
        if (body == null) return null;
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(body);
        } catch (Exception e) {
            return null;
        }
        return f;
    }

    private static Bitmap decode(File f, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(f.getPath(), o);
        int sample = 1;
        while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), o);
    }

    /** Tiny shared HTTP helper used by every network feature. */
    static final class Net {
        private Net() {
        }

        static byte[] get(String url) {
            return get(url, 12000);
        }

        static byte[] get(String url, int readTimeoutMs) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(10000);
                c.setReadTimeout(readTimeoutMs);
                c.setRequestProperty("User-Agent", "AndroidTvHome/3 (personal home screen)");
                c.setRequestProperty("Accept", "application/json, */*");
                if (c.getResponseCode() != 200) {
                    android.util.Log.w("TvHomeNet", c.getResponseCode() + " from " + c.getURL().getHost());
                    return null;
                }
                try (InputStream in = c.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                    byte[] buf = new byte[16384];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    return out.toByteArray();
                }
            } catch (Exception e) {
                android.util.Log.w("TvHomeNet", "failed " + url.replaceAll("api_key=[0-9a-f]+", "api_key=…").replaceAll("\\?.*$", "") + ": " + e);
                return null;
            } finally {
                if (c != null) c.disconnect();
            }
        }

        /** GET with a disk cache: fresh for maxAgeMs, and the stale copy is used when the network fails. */
        static String cached(Context ctx, String url, String name, long maxAgeMs) {
            File f = new File(ctx.getCacheDir(), "json-" + name);
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < maxAgeMs) return read(f);
            byte[] body = get(url);
            if (body != null) {
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(body);
                } catch (Exception ignored) {
                }
                return new String(body, java.nio.charset.StandardCharsets.UTF_8);
            }
            return f.exists() ? read(f) : null;
        }

        private static String read(File f) {
            try {
                return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return null;
            }
        }
    }
}
