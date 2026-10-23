package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * "Play" opens the exact title inside the streaming app when we can find the app's own ID for it. TMDB doesn't
 * carry those IDs, but Wikidata does for many titles (Netflix ID P1874, Apple TV ID P9586), looked up by the
 * TMDB id (P4983 for TV, P4947 for films) and remembered on the TV. When there's no ID the app just opens.
 * Netflix's TV app only follows a title link sent the way Netflix's own launchers send it (component, flags
 * and the source extra below); tested on a TCL Android TV 9.
 */
final class DeepLinks {

    private static final ExecutorService io = Executors.newSingleThreadExecutor();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final String NETFLIX = "com.netflix.ninja";
    private static final String APPLE_TV = "com.apple.atve.androidtv.appletv";

    private DeepLinks() {
    }

    static void open(Context ctx, Catalog.Title t) {
        Context app = ctx.getApplicationContext();
        if (t.servicePkg == null) {
            Apps.globalSearch(ctx, t.title);
            return;
        }
        if (t.tmdbId <= 0 || !(NETFLIX.equals(t.servicePkg) || APPLE_TV.equals(t.servicePkg))) {
            Apps.openTitle(ctx, t.servicePkg, t.title);
            return;
        }
        io.execute(() -> {
            String id = lookup(app, t);
            main.post(() -> {
                if (id == null || !start(app, t, id)) Apps.openTitle(app, t.servicePkg, t.title);
            });
        });
    }

    private static boolean start(Context ctx, Catalog.Title t, String id) {
        try {
            if (NETFLIX.equals(t.servicePkg)) {
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("https://www.netflix.com/title/" + id))
                        .setComponent(new ComponentName(NETFLIX, NETFLIX + ".MainActivity"))
                        .setFlags(0x10808000)
                        .putExtra("source", "30");
                ctx.startActivity(i);
                return true;
            }
            Intent i = new Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://tv.apple.com/" + (t.movie ? "movie/" : "show/") + id))
                    .setPackage(APPLE_TV)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** The service's ID for this title, from the TV's cache or Wikidata. "" is cached for titles Wikidata lacks. */
    private static String lookup(Context ctx, Catalog.Title t) {
        String prop = NETFLIX.equals(t.servicePkg) ? "P1874" : "P9586";
        String key = prop + ":" + (t.movie ? "m" : "t") + t.tmdbId;
        SharedPreferences cache = ctx.getSharedPreferences("deeplinks", Context.MODE_PRIVATE);
        if (cache.contains(key)) {
            String v = cache.getString(key, "");
            return v.isEmpty() ? null : v;
        }
        try {
            String q = "SELECT ?id WHERE { ?item wdt:" + (t.movie ? "P4947" : "P4983") + " \"" + t.tmdbId + "\" ."
                    + " ?item wdt:" + prop + " ?id . } LIMIT 1";
            byte[] body = ImageLoader.Net.get("https://query.wikidata.org/sparql?format=json&query="
                    + URLEncoder.encode(q, "UTF-8"), 15000);
            if (body == null) return null;   // network trouble: don't cache, try again next time
            JSONArray b = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8))
                    .getJSONObject("results").getJSONArray("bindings");
            String id = b.length() > 0 ? b.getJSONObject(0).getJSONObject("id").getString("value") : "";
            cache.edit().putString(key, id).apply();
            return id.isEmpty() ? null : id;
        } catch (Exception e) {
            return null;
        }
    }
}
