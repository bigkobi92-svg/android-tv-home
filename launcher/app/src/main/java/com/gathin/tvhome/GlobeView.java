package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The night globe: a slowly turning Earth drawn as land dots, lit by the real sun for the current time, with
 * glowing cities on the night side and live aircraft from the OpenSky Network over Africa and Europe.
 * Planes near Accra are highlighted. Drawn with batched points at about 30 frames a second.
 */
public class GlobeView extends View {

    private static final double D2R = Math.PI / 180;
    private static final double ACCRA_LAT = 5.6037, ACCRA_LON = -0.187;

    /** Cities that glow at night: lat, lon, size. */
    private static final double[][] CITIES = {
            {5.6, -0.19, 1.3}, {6.7, -1.6, .7}, {6.5, 3.4, 1.1}, {9.1, 7.5, .7}, {5.3, -4.0, .8}, {14.7, -17.4, .6},
            {12.6, -8.0, .5}, {12.4, -1.5, .5}, {6.1, 1.2, .5}, {6.4, 2.4, .5}, {-1.3, 36.8, .8}, {-26.2, 28.0, 1.0},
            {-33.9, 18.4, .8}, {30.0, 31.2, 1.1}, {33.6, -7.6, .7}, {36.8, 3.1, .7}, {9.0, 38.7, .7}, {-4.3, 15.3, .8},
            {-8.8, 13.2, .6}, {51.5, -0.1, 1.2}, {48.9, 2.35, 1.1}, {52.5, 13.4, .9}, {40.4, -3.7, .9}, {41.9, 12.5, .8},
            {52.4, 4.9, .7}, {41.0, 29.0, 1.0}, {55.8, 37.6, 1.0}, {25.2, 55.3, 1.0}, {25.3, 51.5, .7}, {24.7, 46.7, .8},
            {31.8, 35.2, .6}, {19.0, 72.8, 1.1}, {28.6, 77.2, 1.1}, {40.7, -74.0, 1.2}, {-23.5, -46.6, 1.1}, {-22.9, -43.2, .8},
            {43.7, -79.4, .8}, {25.8, -80.2, .7}, {33.7, -84.4, .7}, {4.7, -74.1, .7}, {50.1, 8.7, .7}, {59.3, 18.1, .6},
            {38.7, -9.1, .6}, {45.5, 9.2, .7}, {37.98, 23.7, .6}, {-6.8, 39.3, .6}, {0.3, 32.6, .6}, {-15.4, 28.3, .5}
    };

    /** City pairs (indexes into CITIES) for the animated flights; Accra routes come first and fly more often. */
    private static final int[][] ROUTES = {
            {0, 19}, {0, 27}, {0, 2}, {0, 11}, {0, 20}, {0, 24}, {0, 4}, {0, 10}, {0, 33}, {0, 25}, {0, 14}, {0, 16},
            {0, 28}, {0, 40}, {0, 5}, {0, 38}, {2, 19}, {2, 27}, {2, 33}, {19, 33}, {20, 27}, {13, 19}, {11, 27},
            {10, 27}, {25, 26}, {22, 34}, {5, 20}, {12, 19}, {16, 31}, {27, 32}, {19, 36}, {42, 34}, {23, 13},
            {40, 25}, {17, 20}, {14, 42}
    };
    private static final int ACCRA_ROUTES = 16;
    private static final int FLIGHTS = 22;

    /** One animated plane: route, progress 0..1 and speed per second. */
    private static final class Flight {
        double[] a, b;
        double omega, p, speed;
        boolean accra;
    }

    private final Flight[] flights = new Flight[FLIGHTS];
    private final Random rnd = new Random();
    private final android.graphics.Path plane = new android.graphics.Path();
    private final android.graphics.Matrix planeMatrix = new android.graphics.Matrix();
    private final android.graphics.Path planeDrawn = new android.graphics.Path();
    private final Paint planePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final double[] tmp = new double[3];
    private RadialGradient limb;
    private long lastFrame;

    private final float density;
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] landX, landY, landZ;
    private final float[] dayPts, duskPts, nightPts, planePts, hotPts, trailPts;
    private final float[][] stars;
    private final long start = SystemClock.uptimeMillis();

    private volatile List<double[]> planes = new ArrayList<>();   // lat, lon, track deg, speed m/s, fetched at ms
    private volatile int planeCount;
    private RadialGradient atmo;
    private float lastR;

    public GlobeView(Context ctx) {
        super(ctx);
        density = ctx.getResources().getDisplayMetrics().density;
        float[][] land = loadLand(ctx);
        landX = land[0];
        landY = land[1];
        landZ = land[2];
        int n = landX.length;
        dayPts = new float[n * 2];
        duskPts = new float[n * 2];
        nightPts = new float[n * 2];
        planePts = new float[4000 * 2];
        hotPts = new float[400 * 2];
        trailPts = new float[4000 * 4];
        Random r = new Random(7);
        stars = new float[240][];
        for (int i = 0; i < stars.length; i++) stars[i] = new float[]{r.nextFloat(), r.nextFloat(), r.nextFloat() * 6.28f, 0.6f + r.nextFloat()};
        dot.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.FILL);
        trail.setStyle(Paint.Style.STROKE);
        trail.setStrokeCap(Paint.Cap.ROUND);
        planePaint.setStyle(Paint.Style.FILL);
        // Airliner seen from above, nose up, in a 24-unit box centred on 0,0.
        float[] pts = {0, -12, 1.6f, -9, 1.6f, -3, 11, 3, 11, 5, 1.6f, 1.5f, 1.4f, 8, 4.5f, 10.5f, 4.5f, 12, 0, 10.8f,
                -4.5f, 12, -4.5f, 10.5f, -1.4f, 8, -1.6f, 1.5f, -11, 5, -11, 3, -1.6f, -3, -1.6f, -9};
        plane.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) plane.lineTo(pts[i], pts[i + 1]);
        plane.close();
        for (int i = 0; i < FLIGHTS; i++) {
            flights[i] = new Flight();
            newRoute(flights[i]);
            flights[i].p = rnd.nextDouble();   // start spread out along their routes
        }
    }

    private void newRoute(Flight f) {
        int[] r = rnd.nextFloat() < 0.25f ? ROUTES[rnd.nextInt(ACCRA_ROUTES)] : ROUTES[ACCRA_ROUTES + rnd.nextInt(ROUTES.length - ACCRA_ROUTES)];
        boolean flip = rnd.nextBoolean();
        f.a = unit(CITIES[flip ? r[1] : r[0]]);
        f.b = unit(CITIES[flip ? r[0] : r[1]]);
        f.accra = r[0] == 0 || r[1] == 0;
        f.omega = Math.acos(Math.max(-1, Math.min(1, f.a[0] * f.b[0] + f.a[1] * f.b[1] + f.a[2] * f.b[2])));
        f.speed = 1.0 / (22 + f.omega / Math.PI * 110);   // short hops ~25 s, long hauls ~1 min
        f.p = 0;
    }

    private static double[] unit(double[] city) {
        double la = city[0] * D2R, lo = city[1] * D2R;
        return new double[]{Math.cos(la) * Math.sin(lo), Math.sin(la), Math.cos(la) * Math.cos(lo)};
    }

    /** Point along the great circle at progress p, lifted off the surface mid-flight. */
    private void along(Flight f, double p, double[] out) {
        double so = Math.sin(f.omega);
        double ka = so < 1e-6 ? 1 - p : Math.sin((1 - p) * f.omega) / so, kb = so < 1e-6 ? p : Math.sin(p * f.omega) / so;
        double lift = 1 + 0.08 * Math.sin(Math.PI * p);
        out[0] = (f.a[0] * ka + f.b[0] * kb) * lift;
        out[1] = (f.a[1] * ka + f.b[1] * kb) * lift;
        out[2] = (f.a[2] * ka + f.b[2] * kb) * lift;
    }

    private static float[][] loadLand(Context ctx) {
        try (InputStream in = ctx.getResources().openRawResource(R.raw.land); DataInputStream d = new DataInputStream(in)) {
            int n = d.readInt();
            float[] x = new float[n], y = new float[n], z = new float[n];
            for (int i = 0; i < n; i++) {
                double lat = d.readShort() / 10.0 * D2R, lon = d.readShort() / 10.0 * D2R;
                x[i] = (float) (Math.cos(lat) * Math.sin(lon));
                y[i] = (float) Math.sin(lat);
                z[i] = (float) (Math.cos(lat) * Math.cos(lon));
            }
            return new float[][]{x, y, z};
        } catch (Exception e) {
            return new float[][]{new float[0], new float[0], new float[0]};
        }
    }

    int planeCount() {
        return planeCount;
    }

    // ---- live flights -----------------------------------------------------------------------------

    static final String FLIGHTS_URL = "https://opensky-network.org/api/states/all?lamin=-40&lomin=-30&lamax=65&lomax=60";
    private static final long CACHE_MAX_AGE_MS = 2 * 60 * 60_000L;

    static java.io.File cacheFile(Context ctx) {
        return new java.io.File(ctx.getCacheDir(), "flights.json");
    }

    /** Downloads the latest flights into the cache file (used by the home screen too). Background thread only. */
    static byte[] download(Context ctx) {
        long t0 = SystemClock.uptimeMillis();
        byte[] body = ImageLoader.Net.get(FLIGHTS_URL, 45000);
        if (body == null) return null;
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(cacheFile(ctx))) {
            out.write(body);
        } catch (Exception ignored) {
        }
        android.util.Log.i("TvHomeGlobe", "flights downloaded in " + (SystemClock.uptimeMillis() - t0) + " ms");
        return body;
    }

    /** Shows the saved flights straight away (planes are moved on from their saved positions). Background thread. */
    void loadCachedPlanes() {
        java.io.File f = cacheFile(getContext());
        if (!f.exists() || System.currentTimeMillis() - f.lastModified() > CACHE_MAX_AGE_MS) return;
        try {
            parse(java.nio.file.Files.readAllBytes(f.toPath()));
        } catch (Exception ignored) {
        }
    }

    /** Fetch fresh planes over Africa, Europe and the Middle East. Call from a background thread. */
    void refreshPlanes() {
        byte[] body = download(getContext());
        if (body != null) parse(body);
    }

    private void parse(byte[] body) {
        long t0 = SystemClock.uptimeMillis();
        try {
            JSONArray states = new JSONObject(new String(body, java.nio.charset.StandardCharsets.UTF_8)).optJSONArray("states");
            if (states == null) return;
            List<double[]> out = new ArrayList<>();
            long now = System.currentTimeMillis();
            int airborne = 0;
            for (int i = 0; i < states.length(); i++) {
                JSONArray s = states.optJSONArray(i);
                if (s == null || s.isNull(5) || s.isNull(6) || s.optBoolean(8, false)) continue;   // no position, or on the ground
                airborne++;
                if (out.size() >= 3500) continue;
                long seen = s.isNull(3) ? now / 1000 : s.optLong(3);
                out.add(new double[]{s.optDouble(6), s.optDouble(5), s.isNull(10) ? 0 : s.optDouble(10),
                        s.isNull(9) ? 0 : s.optDouble(9), seen * 1000.0});
            }
            planes = out;
            planeCount = airborne;
            android.util.Log.i("TvHomeGlobe", airborne + " planes parsed in " + (SystemClock.uptimeMillis() - t0) + " ms");
        } catch (Exception ignored) {
        }
    }

    // ---- drawing ----------------------------------------------------------------------------------

    @Override
    protected void onDraw(Canvas c) {
        int w = getWidth(), h = getHeight();
        float t = (SystemClock.uptimeMillis() - start) / 1000f;
        c.drawColor(0xFF02030A);

        // Stars
        dot.setColor(0xFFCFD8FF);
        for (float[] s : stars) {
            dot.setAlpha((int) (60 + 110 * Math.pow(Math.sin(t * 0.7 + s[2]), 2)));
            dot.setStrokeWidth(s[3] * density);
            c.drawPoint(s[0] * w, s[1] * h, dot);
        }

        float R = h * 0.47f, cx = w * 0.6f, cy = h * 0.53f;
        if (atmo == null || R != lastR) {
            atmo = new RadialGradient(cx, cy, R * 1.2f, new int[]{0x00000000, 0x4D4A8CFF, 0x00000000},
                    new float[]{0.78f, 0.84f, 1f}, Shader.TileMode.CLAMP);
            fill.setShader(new RadialGradient(cx - R * 0.35f, cy - R * 0.4f, R * 1.3f, 0xFF0D1E3B, 0xFF030712, Shader.TileMode.CLAMP));
            limb = new RadialGradient(cx, cy, R, new int[]{0x00020510, 0x00020510, 0xB3020510},
                    new float[]{0f, 0.72f, 1f}, Shader.TileMode.CLAMP);
            lastR = R;
        }
        glow.setShader(atmo);
        c.drawCircle(cx, cy, R * 1.2f, glow);
        glow.setShader(null);
        c.drawCircle(cx, cy, R, fill);

        // View: centred near Accra, swinging slowly east and west, tilted so Europe shows.
        double viewLon = ACCRA_LON + 50 * Math.sin(t / 60.0);
        double rotY = -viewLon * D2R, tilt = 0.30;
        double cr = Math.cos(rotY), sr = Math.sin(rotY), ct = Math.cos(tilt), st = Math.sin(tilt);

        // Sun direction for now (UTC), in the same frame as the land points.
        double[] sun = sunVector();

        int nd = 0, nk = 0, nn = 0;
        for (int i = 0; i < landX.length; i++) {
            double x = landX[i] * cr + landZ[i] * sr, z = -landX[i] * sr + landZ[i] * cr, y = landY[i];
            double y2 = y * ct - z * st, z2 = y * st + z * ct;
            if (z2 <= 0.02) continue;
            float px = (float) (cx + x * R), py = (float) (cy - y2 * R);
            double light = landX[i] * sun[0] + landY[i] * sun[1] + landZ[i] * sun[2];
            if (light > 0.12) {
                dayPts[nd++] = px;
                dayPts[nd++] = py;
            } else if (light > -0.12) {
                duskPts[nk++] = px;
                duskPts[nk++] = py;
            } else {
                nightPts[nn++] = px;
                nightPts[nn++] = py;
            }
        }
        dot.setAlpha(255);
        dot.setStrokeWidth(2.1f * density);
        dot.setColor(0xFF2F4E7A);
        c.drawPoints(nightPts, 0, nn, dot);
        dot.setColor(0xFF6E86A8);
        c.drawPoints(duskPts, 0, nk, dot);
        dot.setColor(0xFF4F86A6);
        c.drawPoints(dayPts, 0, nd, dot);
        glow.setShader(limb);   // darken towards the edge so the globe reads as a sphere
        c.drawCircle(cx, cy, R, glow);
        glow.setShader(null);

        // City lights on the night side
        for (double[] city : CITIES) {
            double la = city[0] * D2R, lo = city[1] * D2R;
            double x0 = Math.cos(la) * Math.sin(lo), y0 = Math.sin(la), z0 = Math.cos(la) * Math.cos(lo);
            double light = x0 * sun[0] + y0 * sun[1] + z0 * sun[2];
            if (light > 0.05) continue;
            double x = x0 * cr + z0 * sr, z = -x0 * sr + z0 * cr;
            double y2 = y0 * ct - z * st, z2 = y0 * st + z * ct;
            if (z2 <= 0.05) continue;
            float px = (float) (cx + x * R), py = (float) (cy - y2 * R), rad = (float) (city[2] * 9 * density * z2);
            boolean accra = Math.abs(city[0] - ACCRA_LAT) < 0.1 && Math.abs(city[1] - ACCRA_LON) < 0.1;
            int col = accra ? 0xFFFFD27A : 0xFFFFC07A;
            glow.setColor(col);
            glow.setAlpha(45);
            c.drawCircle(px, py, rad, glow);
            glow.setAlpha(110);
            c.drawCircle(px, py, rad * 0.5f, glow);
            glow.setAlpha(255);
            c.drawCircle(px, py, rad * 0.18f, glow);
        }

        // Planes, moved forward from their last report along their heading
        long now = System.currentTimeMillis();
        int np = 0, nh = 0, ntr = 0;
        List<double[]> all = planes;
        int stride = Math.max(1, all.size() / 1500);   // Europe alone has thousands; keep the map readable
        for (int pi = 0; pi < all.size(); pi++) {
            double[] p = all.get(pi);
            boolean nearAccra = Math.abs(p[0] - ACCRA_LAT) < 6 && Math.abs(p[1] - ACCRA_LON) < 6;
            if (!nearAccra && pi % stride != 0) continue;
            double dt = Math.min(1800, (now - p[4]) / 1000.0);
            double dist = p[3] * dt / 6_371_000.0, brg = p[2] * D2R;
            double la1 = p[0] * D2R, lo1 = p[1] * D2R;
            double la2 = Math.asin(Math.sin(la1) * Math.cos(dist) + Math.cos(la1) * Math.sin(dist) * Math.cos(brg));
            double lo2 = lo1 + Math.atan2(Math.sin(brg) * Math.sin(dist) * Math.cos(la1), Math.cos(dist) - Math.sin(la1) * Math.sin(la2));
            double x0 = Math.cos(la2) * Math.sin(lo2), y0 = Math.sin(la2), z0 = Math.cos(la2) * Math.cos(lo2);
            double x = x0 * cr + z0 * sr, z = -x0 * sr + z0 * cr;
            double y2 = y0 * ct - z * st, z2 = y0 * st + z * ct;
            if (z2 <= 0.03) continue;
            float px = (float) (cx + x * R * 1.012), py = (float) (cy - y2 * R * 1.012);
            boolean hot = Math.abs(la2 / D2R - ACCRA_LAT) < 6 && Math.abs(lo2 / D2R - ACCRA_LON) < 6;
            if (hot && nh < hotPts.length) {
                hotPts[nh++] = px;
                hotPts[nh++] = py;
            } else if (np < planePts.length) {
                planePts[np++] = px;
                planePts[np++] = py;
            }
            if (ntr < trailPts.length) {   // short contrail pointing back along the heading
                float len = 7 * density * (float) z2;
                trailPts[ntr++] = px;
                trailPts[ntr++] = py;
                trailPts[ntr++] = px - (float) Math.sin(brg) * len;
                trailPts[ntr++] = py + (float) Math.cos(brg) * len;
            }
        }
        dot.setStrokeWidth(0.7f * density);
        dot.setColor(0x14FFE2A8);
        c.drawLines(trailPts, 0, ntr, dot);
        dot.setStrokeWidth(1.2f * density);
        dot.setColor(0x73FFE9B8);
        c.drawPoints(planePts, 0, np, dot);
        dot.setStrokeWidth(2.2f * density);
        dot.setColor(0xB3FFC75A);
        c.drawPoints(hotPts, 0, nh, dot);

        drawFlights(c, cx, cy, R, cr, sr, ct, st);

        postInvalidateDelayed(33);
    }

    /** The animated planes: each flies its route, turns to face its direction and leaves a fading contrail. */
    private void drawFlights(Canvas c, float cx, float cy, float R, double cr, double sr, double ct, double st) {
        long now = SystemClock.uptimeMillis();
        double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1000.0);
        lastFrame = now;
        float[] xy = new float[3], next = new float[3];
        for (Flight f : flights) {
            f.p += f.speed * dt;
            if (f.p >= 1) newRoute(f);

            // Contrail: the last stretch of the route, fading out behind the plane.
            int steps = 14;
            double tail = Math.min(f.p, 0.22);
            float px0 = 0, py0 = 0;
            boolean has = false;
            trail.setStrokeWidth(1.7f * density);
            for (int i = 0; i <= steps; i++) {
                double q = f.p - tail + tail * i / steps;
                along(f, q, tmp);
                if (!project(tmp, cx, cy, R, cr, sr, ct, st, xy)) {
                    has = false;
                    continue;
                }
                if (has) {
                    int a = (int) (170 * (i / (float) steps) * Math.min(1, xy[2] * 3));
                    trail.setColor(f.accra ? 0xFFFFC75A : 0xFFDDEBFF);
                    trail.setAlpha(a);
                    c.drawLine(px0, py0, xy[0], xy[1], trail);
                }
                px0 = xy[0];
                py0 = xy[1];
                has = true;
            }

            // The plane, pointing along its path.
            along(f, f.p, tmp);
            if (!project(tmp, cx, cy, R, cr, sr, ct, st, xy)) continue;
            along(f, Math.min(1, f.p + 0.004), tmp);
            if (!project(tmp, cx, cy, R, cr, sr, ct, st, next)) continue;
            float angle = (float) Math.toDegrees(Math.atan2(next[1] - xy[1], next[0] - xy[0])) + 90f;
            float ends = (float) Math.min(1, Math.min(f.p, 1 - f.p) * 12);   // fade in after take-off, out before landing
            float size = (11 + 7 * xy[2]) * density / 24f;
            int alpha = (int) (255 * ends * Math.min(1, xy[2] * 3));

            planeMatrix.reset();
            planeMatrix.postScale(size, size);
            planeMatrix.postRotate(angle);
            planeMatrix.postTranslate(xy[0], xy[1]);
            plane.transform(planeMatrix, planeDrawn);
            planePaint.setColor(f.accra ? 0xFFFFD27A : 0xFFFFFFFF);
            planePaint.setAlpha(alpha);
            c.drawPath(planeDrawn, planePaint);
        }
    }

    /** Screen position of an Earth-fixed point; false when it's behind the globe. out[2] is depth (0 edge, 1 centre). */
    private static boolean project(double[] v, float cx, float cy, float R, double cr, double sr, double ct, double st, float[] out) {
        double x = v[0] * cr + v[2] * sr, z = -v[0] * sr + v[2] * cr, y = v[1];
        double y2 = y * ct - z * st, z2 = y * st + z * ct;
        if (z2 <= 0.02) return false;
        out[0] = (float) (cx + x * R);
        out[1] = (float) (cy - y2 * R);
        out[2] = (float) Math.min(1, z2);
        return true;
    }

    /** Unit vector toward the sun for the current UTC time, in Earth-fixed coordinates (x east at 90°E, z at 0°E). */
    private static double[] sunVector() {
        java.util.Calendar u = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"));
        int day = u.get(java.util.Calendar.DAY_OF_YEAR);
        double hours = u.get(java.util.Calendar.HOUR_OF_DAY) + u.get(java.util.Calendar.MINUTE) / 60.0;
        double decl = -23.44 * Math.cos(2 * Math.PI / 365 * (day + 10)) * D2R;
        double lon = (12 - hours) * 15 * D2R;
        return new double[]{Math.cos(decl) * Math.sin(lon), Math.sin(decl), Math.cos(decl) * Math.cos(lon)};
    }

}
