package com.gathin.tvhome;

import android.content.Context;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * What's playing, for TVs where notification access is blocked (low-memory Android TVs refuse it for apps that
 * didn't ship with the TV). Reads `dumpsys media_session`, which needs the DUMP permission granted once over adb,
 * and controls playback with media keys, which go to the app that last played. Background thread for read().
 */
final class MediaStatus {

    static final class Playing {
        String pkg, title, artist;
        int uid = -1;
        boolean playing;    // the app says it's playing...
        boolean audible;    // ...and its audio really is (some apps keep saying "playing" after pausing)
    }

    private MediaStatus() {
    }

    /** Sessions in Android's order (most recent first) that are playing or paused. */
    static List<Playing> read() {
        List<Playing> out = new ArrayList<>();
        java.util.Set<Integer> sounding = audibleUids();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"dumpsys", "media_session"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            Playing cur = null;
            int lastUid = -1;   // "ownerPid=…, ownerUid=…" comes just before "package=…"
            for (String line; (line = r.readLine()) != null; ) {
                line = line.trim();
                if (line.startsWith("ownerPid=") && line.contains("ownerUid=")) {
                    try {
                        lastUid = Integer.parseInt(line.substring(line.indexOf("ownerUid=") + 9).split("[^0-9]")[0]);
                    } catch (Exception e) {
                        lastUid = -1;
                    }
                } else if (line.startsWith("package=")) {
                    cur = new Playing();
                    cur.pkg = line.substring(8);
                    cur.uid = lastUid;
                } else if (cur != null && line.startsWith("state=PlaybackState {state=")) {
                    int st = line.charAt(27) - '0';
                    if (st == 3 || st == 2) {   // playing or paused
                        cur.playing = st == 3;
                        cur.audible = cur.playing && sounding.contains(cur.uid);
                        out.add(cur);
                    }
                } else if (cur != null && line.contains("description=")) {
                    String d = line.substring(line.indexOf("description=") + 12);
                    if (!d.equals("null")) {
                        String[] parts = d.split(", ");
                        cur.title = clean(parts[0]);
                        cur.artist = parts.length > 1 ? clean(parts[1]) : null;
                    }
                } else if (line.startsWith("Sessions Stack") && cur != null) {
                    break;
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return out;
    }

    /** Apps whose media audio is actually playing right now (from `dumpsys audio`). */
    private static java.util.Set<Integer> audibleUids() {
        java.util.Set<Integer> uids = new java.util.HashSet<>();
        Process p = null;
        try {
            p = Runtime.getRuntime().exec(new String[]{"dumpsys", "audio"});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            for (String line; (line = r.readLine()) != null; ) {
                int i = line.indexOf("u/pid:");
                if (i < 0 || !line.contains("state:started")) continue;
                try {
                    uids.add(Integer.parseInt(line.substring(i + 6).split("/")[0]));
                } catch (Exception ignored) {
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (p != null) p.destroy();
        }
        return uids;
    }

    private static String clean(String s) {
        return s == null || s.equals("null") || s.isEmpty() ? null : s;
    }

    /** Sends a media key (previous, play/pause, next) to the app Android routes media buttons to: the one playing. */
    static void key(Context ctx, int keyCode) {
        AudioManager am = ctx.getSystemService(AudioManager.class);
        if (am == null) return;
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, keyCode, 0));
    }

    private static final java.util.Map<String, String> coverCache = new java.util.HashMap<>();

    /**
     * Album cover for a song, from Apple's free iTunes search (the playing app's own artwork isn't readable on these
     * TVs). Remembered per song; null when nothing matches. Background thread only.
     */
    static String coverUrl(String title, String artist) {
        if (title == null) return null;
        String key = title + "|" + artist;
        synchronized (coverCache) {
            if (coverCache.containsKey(key)) return coverCache.get(key);
        }
        String url = null;
        try {
            String q = java.net.URLEncoder.encode(title + (artist != null ? " " + artist : ""), "UTF-8");
            byte[] body = ImageLoader.Net.get("https://itunes.apple.com/search?entity=song&limit=1&term=" + q);
            if (body != null) {
                org.json.JSONArray r = new org.json.JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                        .getJSONArray("results");
                if (r.length() > 0) url = r.getJSONObject(0).optString("artworkUrl100", null);
                if (url != null) url = url.replace("100x100bb", "300x300bb");
            }
        } catch (Exception ignored) {
        }
        synchronized (coverCache) {
            coverCache.put(key, url);
        }
        return url;
    }
}
