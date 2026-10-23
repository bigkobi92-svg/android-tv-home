package com.gathin.tvhome;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Titles for the home screen. With a TMDB key (res/values/secrets.xml): the featured banner shows what's
 * popular right now on the streaming services installed on this TV, in this country (config.xml), with wide
 * backdrops; the trending row is TMDB's weekly trending list. Without a key, the free iTunes charts fill in.
 * Everything is cached on disk for 6 hours and the stale copy is used offline. Call from a background thread.
 */
final class Catalog {

    enum Kind { HOME, MOVIES, SHOWS }

    static final class Title {
        String title, overview, backdrop, poster, year, genre, servicePkg, serviceLabel;
        int rank, tmdbId;
        boolean movie;
    }

    /** "Top 10 movies on Netflix": the 10 most popular titles of one kind on one service, in this country. */
    static final class ServiceRow {
        final String label, pkg;
        final List<Title> items = new ArrayList<>();

        ServiceRow(String label, String pkg) {
            this.label = label;
            this.pkg = pkg;
        }
    }

    static final class Page {
        final List<Title> featured = new ArrayList<>();
        final List<Title> trending = new ArrayList<>();
        final List<ServiceRow> serviceRows = new ArrayList<>();   // Movies and TV Shows tabs only
        boolean fromTmdb;
    }

    private static final long TTL = 6 * 60 * 60_000L;
    private static final String API = "https://api.themoviedb.org/3/";
    private static final String IMG = "https://image.tmdb.org/t/p/";

    private Catalog() {
    }

    static Page load(Context ctx, Kind kind) {
        String key = ctx.getString(R.string.tmdb_api_key).trim();
        if (!key.isEmpty()) {
            try {
                Page p = tmdb(ctx, key, kind);
                if (!p.featured.isEmpty() || !p.trending.isEmpty()) return p;
            } catch (Exception ignored) {
            }
        }
        return itunes(ctx, kind);
    }

    // ---- TMDB ---------------------------------------------------------------------------------------

    private static Page tmdb(Context ctx, String key, Kind kind) throws Exception {
        Page page = new Page();
        page.fromTmdb = true;
        String region = ctx.getString(R.string.watch_region).trim();
        Map<Integer, String> genres = genres(ctx, key);

        // Featured: the top titles per installed service, interleaved so the banner rotates between services.
        List<List<Title>> perService = new ArrayList<>();
        for (String spec : ctx.getResources().getStringArray(R.array.streaming_services)) {
            String[] s = spec.split("\\|");
            if (s.length < 3 || !Apps.installed(ctx, s[1])) continue;
            List<Title> list = new ArrayList<>();
            ServiceRow row = kind == Kind.HOME ? null : new ServiceRow(s[2], s[1]);
            for (String media : kind == Kind.MOVIES ? new String[]{"movie"} : kind == Kind.SHOWS ? new String[]{"tv"}
                    : new String[]{"tv", "movie"}) {
                String url = API + "discover/" + media + "?api_key=" + key + "&language=en-US&watch_region=" + region
                        + "&with_watch_providers=" + s[0] + "&with_watch_monetization_types=flatrate"
                        + "&sort_by=popularity.desc&vote_count.gte=50";
                JSONArray res = results(ctx, url, "disc-" + media + "-" + s[0] + "-" + region);
                for (int i = 0; i < res.length(); i++) {
                    Title t = parse(res.getJSONObject(i), media.equals("movie"), genres);
                    if (t == null) continue;
                    t.servicePkg = s[1];
                    t.serviceLabel = s[2];
                    if (row != null && t.poster != null && row.items.size() < 10) {
                        t.rank = row.items.size() + 1;
                        row.items.add(t);
                    }
                    if (list.size() < 6 && t.backdrop != null && !TextUtils.isEmpty(t.overview)) list.add(t);
                }
            }
            if (row != null && !row.items.isEmpty()) page.serviceRows.add(row);
            // Mixed home: most popular first regardless of TV or movie.
            perService.add(list);
        }
        for (int round = 0; page.featured.size() < 5 && round < 6; round++) {
            for (List<Title> l : perService) {
                if (round < l.size() && page.featured.size() < 5 && !contains(page.featured, l.get(round))) {
                    page.featured.add(l.get(round));
                }
            }
        }

        String media = kind == Kind.MOVIES ? "movie" : kind == Kind.SHOWS ? "tv" : "all";
        JSONArray res = results(ctx, API + "trending/" + media + "/week?api_key=" + key + "&language=en-US",
                "trend-" + media);
        for (int i = 0; i < res.length() && page.trending.size() < 20; i++) {
            JSONObject o = res.getJSONObject(i);
            String mt = o.optString("media_type", media);
            if (!mt.equals("movie") && !mt.equals("tv")) continue;
            Title t = parse(o, mt.equals("movie"), genres);
            if (t == null || t.poster == null) continue;
            t.rank = page.trending.size() + 1;
            page.trending.add(t);
        }
        // No streaming apps installed (or nothing matched): feature the top trending titles instead.
        for (Title t : page.trending) {
            if (page.featured.size() >= 5) break;
            if (t.backdrop != null && !TextUtils.isEmpty(t.overview)) page.featured.add(t);
        }
        return page;
    }

    private static boolean contains(List<Title> l, Title t) {
        for (Title x : l) if (x.title.equals(t.title)) return true;
        return false;
    }

    private static JSONArray results(Context ctx, String url, String cacheName) throws Exception {
        String json = ImageLoader.Net.cached(ctx, url, cacheName, TTL);
        if (json == null) return new JSONArray();
        return new JSONObject(json).optJSONArray("results") != null ? new JSONObject(json).getJSONArray("results")
                : new JSONArray();
    }

    private static Map<Integer, String> genres(Context ctx, String key) {
        Map<Integer, String> out = new HashMap<>();
        for (String media : new String[]{"movie", "tv"}) {
            try {
                String json = ImageLoader.Net.cached(ctx, API + "genre/" + media + "/list?api_key=" + key + "&language=en",
                        "genres-" + media, 7 * 24 * 60 * 60_000L);
                if (json == null) continue;
                JSONArray g = new JSONObject(json).getJSONArray("genres");
                for (int i = 0; i < g.length(); i++) out.put(g.getJSONObject(i).getInt("id"), g.getJSONObject(i).getString("name"));
            } catch (Exception ignored) {
            }
        }
        return out;
    }

    private static Title parse(JSONObject o, boolean movie, Map<Integer, String> genres) {
        Title t = new Title();
        t.movie = movie;
        t.tmdbId = o.optInt("id");
        t.title = o.optString(movie ? "title" : "name", "");
        if (t.title.isEmpty()) return null;
        t.overview = o.optString("overview", "");
        String bd = o.optString("backdrop_path", ""), ps = o.optString("poster_path", "");
        t.backdrop = bd.isEmpty() || "null".equals(bd) ? null : IMG + "w780" + bd;
        t.poster = ps.isEmpty() || "null".equals(ps) ? null : IMG + "w342" + ps;
        String date = o.optString(movie ? "release_date" : "first_air_date", "");
        t.year = date.length() >= 4 ? date.substring(0, 4) : "";
        JSONArray g = o.optJSONArray("genre_ids");
        if (g != null && g.length() > 0) t.genre = genres.get(g.optInt(0));
        return t;
    }

    // ---- free fallback: iTunes charts ----------------------------------------------------------------

    private static Page itunes(Context ctx, Kind kind) {
        Page page = new Page();
        List<Title> movies = chart(ctx, ctx.getString(R.string.fallback_movies_feed), true);
        List<Title> shows = chart(ctx, ctx.getString(R.string.fallback_shows_feed), false);
        List<Title> src = kind == Kind.SHOWS ? shows : movies;
        for (Title t : src) {
            if (page.featured.size() < 5 && !TextUtils.isEmpty(t.overview)) page.featured.add(t);
        }
        if (page.featured.isEmpty()) for (int i = 0; i < Math.min(5, src.size()); i++) page.featured.add(src.get(i));
        page.trending.addAll(src);
        return page;
    }

    private static List<Title> chart(Context ctx, String url, boolean movie) {
        List<Title> out = new ArrayList<>();
        try {
            String json = ImageLoader.Net.cached(ctx, url, "itunes-" + (movie ? "m" : "s"), TTL);
            if (json == null) return out;
            Object e = new JSONObject(json).getJSONObject("feed").opt("entry");
            JSONArray entries = e instanceof JSONArray ? (JSONArray) e : new JSONArray().put(e);
            for (int i = 0; i < entries.length(); i++) {
                JSONObject en = entries.getJSONObject(i);
                Title t = new Title();
                t.movie = movie;
                t.title = en.getJSONObject("im:name").optString("label").replaceAll(", (The Complete|Season).*$", "");
                JSONObject sum = en.optJSONObject("summary");
                t.overview = sum != null ? sum.optString("label") : "";
                JSONArray imgs = en.optJSONArray("im:image");
                if (imgs == null || imgs.length() == 0) continue;
                String img = imgs.getJSONObject(imgs.length() - 1).optString("label");
                t.poster = img.replaceAll("/\\d+x\\d+bb\\.(png|jpg)$", "/342x513bb.jpg");
                t.backdrop = img.replaceAll("/\\d+x\\d+bb\\.(png|jpg)$", "/600x900bb.jpg");
                JSONObject cat = en.optJSONObject("category");
                t.genre = cat != null && cat.optJSONObject("attributes") != null ? cat.getJSONObject("attributes").optString("label") : "";
                JSONObject rel = en.optJSONObject("im:releaseDate");
                String d = rel != null ? rel.optString("label") : "";
                t.year = d.length() >= 4 ? d.substring(0, 4) : "";
                t.rank = out.size() + 1;
                out.add(t);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
