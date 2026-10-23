package com.gathin.tvhome;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** The TV apps installed on this TV: label, launch component and tile art, in the order config.xml asks for. */
final class Apps {

    static final String YOUTUBE = "com.google.android.youtube.tv";

    static final class App {
        final String label, pkg;
        final ComponentName component;
        final Drawable banner;   // landscape TV tile; null when the app only has an icon
        final Drawable icon;

        App(String label, ComponentName component, Drawable banner, Drawable icon) {
            this.label = label;
            this.component = component;
            this.pkg = component.getPackageName();
            this.banner = banner;
            this.icon = icon;
        }
    }

    private Apps() {
    }

    /** Every launchable TV app: featured apps first in config order, then the rest A to Z. */
    static List<App> load(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        List<String> hidden = Arrays.asList(ctx.getResources().getStringArray(R.array.hidden_apps));
        List<String> featured = Arrays.asList(ctx.getResources().getStringArray(R.array.featured_apps));
        Intent probe = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<App> out = new ArrayList<>();
        for (ResolveInfo ri : pm.queryIntentActivities(probe, 0)) {
            if (ri.activityInfo == null) continue;
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(ctx.getPackageName()) || hidden.contains(pkg)) continue;
            Drawable banner = ri.activityInfo.loadBanner(pm);
            if (banner == null) banner = ri.activityInfo.applicationInfo.loadBanner(pm);
            if (YOUTUBE.equals(pkg)) {
                Drawable red = YouTubeTile.from(banner);
                if (red != null) banner = red;
            }
            out.add(new App(String.valueOf(ri.loadLabel(pm)), new ComponentName(pkg, ri.activityInfo.name),
                    banner, ri.loadIcon(pm)));
        }
        Collections.sort(out, (a, b) -> {
            int fa = featured.indexOf(a.pkg), fb = featured.indexOf(b.pkg);
            if (fa >= 0 || fb >= 0) {
                if (fa < 0) return 1;
                if (fb < 0) return -1;
                return Integer.compare(fa, fb);
            }
            return a.label.compareToIgnoreCase(b.label);
        });
        return out;
    }

    /** Only the apps listed in featured_apps that are installed, in that order. */
    static List<App> featured(Context ctx, List<App> all) {
        List<String> featured = Arrays.asList(ctx.getResources().getStringArray(R.array.featured_apps));
        List<App> out = new ArrayList<>();
        for (App a : all) if (featured.contains(a.pkg)) out.add(a);
        return out;
    }

    static boolean installed(Context ctx, String pkg) {
        return ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg) != null;
    }

    static void launch(Context ctx, App a) {
        Intent i = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(a.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(ctx, ctx.getString(R.string.cant_open, a.label), Toast.LENGTH_SHORT).show();
        }
    }

    static boolean launch(Context ctx, String pkg) {
        Intent i = ctx.getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) return false;
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(i);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /**
     * Opens an app on a title: the app's own search when it supports ACTION_SEARCH, otherwise the app itself.
     * The charts can't start a specific episode, so this is the closest honest "Play".
     */
    static void openTitle(Context ctx, String pkg, String title) {
        if (pkg != null) {
            Intent search = new Intent(Intent.ACTION_SEARCH).setPackage(pkg)
                    .putExtra(android.app.SearchManager.QUERY, title)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (search.resolveActivity(ctx.getPackageManager()) != null) {
                try {
                    ctx.startActivity(search);
                    return;
                } catch (Exception ignored) {
                }
            }
            if (launch(ctx, pkg)) return;
        }
        globalSearch(ctx, title);
    }

    /** The TV's own Google search, with the query filled in when there is one. */
    static void globalSearch(Context ctx, String query) {
        Intent i = new Intent("android.search.action.GLOBAL_SEARCH").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (query != null) i.putExtra(android.app.SearchManager.QUERY, query);
        try {
            ctx.startActivity(i);
        } catch (ActivityNotFoundException e) {
            try {
                ctx.startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException e2) {
                Toast.makeText(ctx, R.string.not_available, Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** Tile art as a bitmap (for the phone remote's buttons). */
    static Bitmap render(App a, int w, int h) {
        Drawable d = a.banner != null ? a.banner : a.icon;
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.drawColor(0xFF1C1D22);
        if (d != null) {
            Drawable copy = d.getConstantState() != null ? d.getConstantState().newDrawable().mutate() : d;
            if (a.banner != null) {
                copy.setBounds(0, 0, w, h);
            } else {
                int s = (int) (h * 0.7f);
                copy.setBounds((w - s) / 2, (h - s) / 2, (w + s) / 2, (h + s) / 2);
            }
            copy.draw(c);
        }
        return b;
    }
}
