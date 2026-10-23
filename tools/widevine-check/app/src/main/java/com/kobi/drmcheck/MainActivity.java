package com.kobi.drmcheck;

import android.app.Activity;
import android.media.MediaDrm;
import android.media.NotProvisionedException;
import android.os.Bundle;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * One-off Widevine check: reports the TV's Widevine state and, if it isn't provisioned, runs Android's
 * standard provisioning flow (the same request ExoPlayer and streaming apps send to Google). Results go to
 * logcat under the tag DRMCHECK. Launch with: am start -n com.kobi.drmcheck/.MainActivity
 */
public class MainActivity extends Activity {

    private static final String TAG = "DRMCHECK";
    private static final UUID WIDEVINE = new UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        new Thread(() -> {
            try {
                run();
            } catch (Throwable t) {
                Log.e(TAG, "FAILED: " + t, t);
            }
            Log.i(TAG, "DONE");
            runOnUiThread(this::finish);
        }).start();
    }

    private void run() throws Exception {
        MediaDrm drm = new MediaDrm(WIDEVINE);
        for (String p : new String[]{"vendor", "version", "systemId", "securityLevel", "provisioningUniqueId"}) {
            try {
                String v = "provisioningUniqueId".equals(p) ? drm.getPropertyByteArray(p).length + " bytes" : drm.getPropertyString(p);
                Log.i(TAG, p + " = " + v);
            } catch (Exception e) {
                Log.i(TAG, p + " = (unavailable: " + e.getClass().getSimpleName() + ")");
            }
        }
        if (tryOpen(drm)) {
            Log.i(TAG, "RESULT: already provisioned, sessions open fine");
            drm.close();
            return;
        }
        MediaDrm.ProvisionRequest req = drm.getProvisionRequest();
        String url = req.getDefaultUrl() + "&signedRequest=" + new String(req.getData(), StandardCharsets.UTF_8);
        Log.i(TAG, "provisioning via " + req.getDefaultUrl());
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setConnectTimeout(15000);
        c.setReadTimeout(15000);
        c.setRequestProperty("Content-Type", "application/json");
        try (OutputStream out = c.getOutputStream()) {
            out.write(new byte[0]);
        }
        int code = c.getResponseCode();
        Log.i(TAG, "server answered HTTP " + code);
        InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        if (in != null) {
            byte[] buf = new byte[8192];
            for (int n; (n = in.read(buf)) > 0; ) body.write(buf, 0, n);
        }
        if (code != 200) {
            Log.e(TAG, "RESULT: server refused: " + body.toString("UTF-8"));
            drm.close();
            return;
        }
        drm.provideProvisionResponse(body.toByteArray());
        Log.i(TAG, "provision response accepted by the TV");
        Log.i(TAG, tryOpen(drm) ? "RESULT: FIXED, Widevine sessions open now" : "RESULT: still not provisioned after response");
        drm.close();
    }

    private static boolean tryOpen(MediaDrm drm) {
        try {
            byte[] s = drm.openSession();
            drm.closeSession(s);
            return true;
        } catch (NotProvisionedException e) {
            Log.i(TAG, "openSession: not provisioned");
            return false;
        } catch (Exception e) {
            Log.i(TAG, "openSession: " + e);
            return false;
        }
    }
}
