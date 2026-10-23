package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** The TV's picture sources (HDMI ports, AV, Live TV) and how to switch to each. Shared by Home and the remote. */
final class TvInputs {

    static final class Source {
        final String label;
        final boolean hdmi, liveTv;
        final int state;   // TvInputManager.INPUT_STATE_*, or -1 when unknown
        final Intent intent;

        Source(String label, boolean hdmi, boolean liveTv, int state, Intent intent) {
            this.label = label;
            this.hdmi = hdmi;
            this.liveTv = liveTv;
            this.state = state;
            this.intent = intent;
        }
    }

    private TvInputs() {
    }

    static List<Source> list(Context ctx) {
        List<Source> out = new ArrayList<>();
        TvInputManager tim = (TvInputManager) ctx.getSystemService(Context.TV_INPUT_SERVICE);
        String tunerId = null;
        if (tim != null) {
            List<TvInputInfo> inputs = new ArrayList<>(tim.getTvInputList());
            Collections.sort(inputs, (a, b) -> {
                boolean ha = a.getType() == TvInputInfo.TYPE_HDMI, hb = b.getType() == TvInputInfo.TYPE_HDMI;
                if (ha != hb) return ha ? -1 : 1;
                return String.valueOf(a.loadLabel(ctx)).compareToIgnoreCase(String.valueOf(b.loadLabel(ctx)));
            });
            int hdmiPorts = 0, hdmiConnected = 0;
            for (TvInputInfo info : inputs) {
                if (!info.isPassthroughInput() || info.getType() != TvInputInfo.TYPE_HDMI) continue;
                hdmiPorts++;
                if (tim.getInputState(info.getId()) == TvInputManager.INPUT_STATE_CONNECTED) hdmiConnected++;
            }
            boolean bogus = hdmiPorts > 1 && hdmiConnected == hdmiPorts;
            for (TvInputInfo info : inputs) {
                if (info.getType() == TvInputInfo.TYPE_TUNER
                        && (info.getServiceInfo().applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) != 0
                        && (tunerId == null || info.getId().compareTo(tunerId) < 0)) {
                    tunerId = info.getId();
                }
                if (!info.isPassthroughInput()) continue;
                CharSequence l = info.loadLabel(ctx);
                String label = l == null ? info.getId() : l.toString();
                boolean hdmi = info.getType() == TvInputInfo.TYPE_HDMI;
                if (hdmi && !label.toUpperCase().contains("HDMI")) label = "HDMI " + label;
                int state = hdmi && bogus ? -1 : tim.getInputState(info.getId());
                out.add(new Source(label, hdmi, false, state, channel(info.getId())));
            }
        }
        String liveTvPkg = ctx.getString(R.string.live_tv_package).trim();
        Intent liveTv = liveTvPkg.isEmpty() ? null : ctx.getPackageManager().getLeanbackLaunchIntentForPackage(liveTvPkg);
        if (liveTv != null && tunerId != null) liveTv = channel(tunerId).setPackage(liveTvPkg);
        if (liveTv != null) out.add(new Source(ctx.getString(R.string.live_tv), false, true, -1, liveTv));
        return out;
    }

    private static Intent channel(String inputId) {
        return new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TvContract.buildChannelUriForPassthroughInput(inputId), "vnd.android.cursor.item/channel");
    }

    static boolean open(Context ctx, Source s) {
        try {
            ctx.startActivity(new Intent(s.intent).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
