package com.gathin.tvhome;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Phone remote served by the TV itself: open http://<TV address>:8765 on a phone on the same Wi-Fi.
 * A phone pairs once with a 4-digit code shown on the TV; after that it can open apps, switch inputs,
 * change volume, search and start the screensaver. Arrow keys and power stay with the TV remote or the
 * Google TV app, because Android doesn't let an ordinary app press keys for other apps.
 */
public class RemoteService extends Service {

    private static final String TAG = "TvHomeRemote";
    private static final String CHANNEL = "remote";
    private static final String COOKIE = "kt";

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private final SecureRandom random = new SecureRandom();
    private final Map<String, byte[]> iconCache = new HashMap<>();
    private ServerSocket server;
    private volatile boolean running;
    private String pairCode;
    private long pairCodeAt;

    static void start(Context ctx) {
        Intent i = new Intent(ctx, RemoteService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception e) {
            Log.w(TAG, "could not start: " + e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null && Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, getString(R.string.remote_service),
                    NotificationManager.IMPORTANCE_MIN));
        }
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle(getString(R.string.remote_service))
                .setSmallIcon(R.drawable.ic_tv)
                .build();
        startForeground(42, n);
        running = true;
        new Thread(this::serve, "remote-server").start();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void serve() {
        int port = getResources().getInteger(R.integer.remote_port);
        while (running) {
            try (ServerSocket s = new ServerSocket(port)) {
                server = s;
                Log.i(TAG, "listening on " + port);
                while (running) {
                    Socket c = s.accept();
                    pool.execute(() -> handle(c));
                }
            } catch (Exception e) {
                if (!running) return;
                Log.w(TAG, "server error, retrying: " + e);
                try {
                    Thread.sleep(3000);
                } catch (InterruptedException ignored) {
                    return;
                }
            }
        }
    }

    // ---- HTTP --------------------------------------------------------------------------------------

    private void handle(Socket c) {
        try (Socket sock = c) {
            sock.setSoTimeout(8000);
            BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
            String line = in.readLine();
            if (line == null) return;
            String[] parts = line.split(" ");
            if (parts.length < 2) return;
            String method = parts[0], target = parts[1];
            String cookie = null;
            for (String h; (h = in.readLine()) != null && !h.isEmpty(); ) {
                if (h.toLowerCase(Locale.US).startsWith("cookie:")) cookie = h.substring(7).trim();
            }
            String path = target, query = "";
            int q = target.indexOf('?');
            if (q >= 0) {
                path = target.substring(0, q);
                query = target.substring(q + 1);
            }
            Map<String, String> params = parse(query);
            boolean paired = paired(cookie);
            route(sock.getOutputStream(), method, path, params, paired);
        } catch (Exception e) {
            Log.w(TAG, "request failed: " + e);
        }
    }

    private void route(OutputStream out, String method, String path, Map<String, String> p, boolean paired) throws Exception {
        if (path.equals("/") || path.equals("/index.html")) {
            send(out, 200, "text/html; charset=utf-8", asset("remote.html"), null);
            return;
        }
        if (path.equals("/api/status")) {
            json(out, new JSONObject().put("paired", paired).put("name", deviceName()), null);
            return;
        }
        if (path.equals("/api/pair/start") && method.equals("POST")) {
            pairCode = String.format(Locale.US, "%04d", random.nextInt(10000));
            pairCodeAt = System.currentTimeMillis();
            String code = pairCode;
            main.post(() -> Toast.makeText(this, getString(R.string.remote_code, code), Toast.LENGTH_LONG).show());
            main.postDelayed(() -> Toast.makeText(this, getString(R.string.remote_code, code), Toast.LENGTH_LONG).show(), 3600);
            json(out, new JSONObject().put("ok", true), null);
            return;
        }
        if (path.equals("/api/pair") && method.equals("POST")) {
            boolean fresh = pairCode != null && System.currentTimeMillis() - pairCodeAt < 5 * 60_000L;
            if (fresh && pairCode.equals(p.get("code"))) {
                String token = Long.toHexString(random.nextLong()) + Long.toHexString(random.nextLong());
                tokens().edit().putBoolean(token, true).apply();
                pairCode = null;
                json(out, new JSONObject().put("ok", true),
                        COOKIE + "=" + token + "; Max-Age=31536000; Path=/; SameSite=Strict; HttpOnly");
            } else {
                send(out, 403, "application/json", "{\"ok\":false}".getBytes(StandardCharsets.UTF_8), null);
            }
            return;
        }
        if (!paired) {
            send(out, 401, "application/json", "{\"paired\":false}".getBytes(StandardCharsets.UTF_8), null);
            return;
        }
        switch (path) {
            case "/api/apps": {
                JSONArray arr = new JSONArray();
                for (Apps.App a : Apps.load(this)) arr.put(new JSONObject().put("pkg", a.pkg).put("label", a.label));
                json(out, new JSONObject().put("apps", arr), null);
                return;
            }
            case "/api/icon": {
                byte[] png = icon(p.get("pkg"));
                if (png == null) send(out, 404, "text/plain", new byte[0], null);
                else send(out, 200, "image/png", png, null);
                return;
            }
            case "/api/inputs": {
                JSONArray arr = new JSONArray();
                List<TvInputs.Source> list = TvInputs.list(this);
                for (int i = 0; i < list.size(); i++) {
                    TvInputs.Source s = list.get(i);
                    arr.put(new JSONObject().put("i", i).put("label", s.label)
                            .put("connected", s.state == android.media.tv.TvInputManager.INPUT_STATE_CONNECTED));
                }
                json(out, new JSONObject().put("inputs", arr), null);
                return;
            }
            default:
                break;
        }
        if (!method.equals("POST")) {
            send(out, 405, "text/plain", new byte[0], null);
            return;
        }
        boolean ok = act(path, p);
        json(out, new JSONObject().put("ok", ok), null);
    }

    /** Runs a remote action on the TV. Activity starts and volume go through the main thread. */
    private boolean act(String path, Map<String, String> p) {
        final boolean[] ok = {true};
        Runnable r;
        switch (path) {
            case "/api/launch":
                r = () -> ok[0] = Apps.launch(this, p.get("pkg"));
                break;
            case "/api/input":
                r = () -> {
                    List<TvInputs.Source> list = TvInputs.list(this);
                    int i = parseInt(p.get("i"), -1);
                    ok[0] = i >= 0 && i < list.size() && TvInputs.open(this, list.get(i));
                };
                break;
            case "/api/volume":
                r = () -> {
                    AudioManager am = getSystemService(AudioManager.class);
                    if (am == null) {
                        ok[0] = false;
                        return;
                    }
                    String d = p.get("d");
                    int dir = "up".equals(d) ? AudioManager.ADJUST_RAISE : "down".equals(d) ? AudioManager.ADJUST_LOWER
                            : AudioManager.ADJUST_TOGGLE_MUTE;
                    am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI);
                };
                break;
            case "/api/home":
                r = () -> startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                break;
            case "/api/search":
                r = () -> Apps.globalSearch(this, p.get("q"));
                break;
            case "/api/find":
                r = () -> Apps.openTitle(this, p.get("pkg"), p.get("q"));
                break;
            case "/api/screensaver":
                r = () -> {
                    try {
                        startActivity(new Intent().setClassName("com.android.systemui", "com.android.systemui.Somnambulator")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (Exception e) {
                        ok[0] = false;
                    }
                };
                break;
            case "/api/morning":
                r = () -> Morning.show(this);
                break;
            default:
                return false;
        }
        final Object lock = new Object();
        final boolean[] done = {false};
        main.post(() -> {
            try {
                r.run();
            } catch (Exception e) {
                ok[0] = false;
            }
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            long until = System.currentTimeMillis() + 3000;
            while (!done[0] && System.currentTimeMillis() < until) {
                try {
                    lock.wait(500);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }
        return ok[0];
    }

    // ---- helpers -----------------------------------------------------------------------------------

    private boolean paired(String cookieHeader) {
        if (cookieHeader == null) return false;
        for (String c : cookieHeader.split(";")) {
            String[] kv = c.trim().split("=", 2);
            if (kv.length == 2 && kv[0].equals(COOKIE)) return tokens().getBoolean(kv[1], false);
        }
        return false;
    }

    private SharedPreferences tokens() {
        return getSharedPreferences("remote_tokens", MODE_PRIVATE);
    }

    private String deviceName() {
        String n = android.provider.Settings.Global.getString(getContentResolver(), "device_name");
        return n != null ? n : "TV";
    }

    private synchronized byte[] icon(String pkg) {
        if (pkg == null) return null;
        byte[] hit = iconCache.get(pkg);
        if (hit != null) return hit;
        for (Apps.App a : Apps.load(this)) {
            if (!a.pkg.equals(pkg)) continue;
            Bitmap b = Apps.render(a, 320, 180);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            b.compress(Bitmap.CompressFormat.PNG, 100, bo);
            b.recycle();
            iconCache.put(pkg, bo.toByteArray());
            return bo.toByteArray();
        }
        return null;
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream in = getAssets().open(name); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }

    private static Map<String, String> parse(String query) {
        Map<String, String> m = new HashMap<>();
        if (query == null || query.isEmpty()) return m;
        for (String kv : query.split("&")) {
            String[] p = kv.split("=", 2);
            try {
                m.put(URLDecoder.decode(p[0], "UTF-8"), p.length > 1 ? URLDecoder.decode(p[1], "UTF-8") : "");
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static void json(OutputStream out, JSONObject o, String setCookie) throws Exception {
        send(out, 200, "application/json; charset=utf-8", o.toString().getBytes(StandardCharsets.UTF_8), setCookie);
    }

    private static void send(OutputStream out, int code, String type, byte[] body, String setCookie) throws Exception {
        String status = code == 200 ? "OK" : code == 401 ? "Unauthorized" : code == 403 ? "Forbidden"
                : code == 404 ? "Not Found" : "Error";
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(status).append("\r\n")
                .append("Content-Type: ").append(type).append("\r\n")
                .append("Content-Length: ").append(body.length).append("\r\n")
                .append("Cache-Control: no-store\r\n")
                .append("Connection: close\r\n");
        if (setCookie != null) h.append("Set-Cookie: ").append(setCookie).append("\r\n");
        h.append("\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.write(body);
        out.flush();
    }
}
