package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.ColorDrawable;
import android.media.tv.TvInputManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TV home screen: side menu, tabs, a rotating featured banner of what's streaming on the installed
 * services, then Your apps, Recently used and Trending rows. Built to be driven with a D-pad remote.
 */
public class MainActivity extends Activity {

    private static final long ROTATE_MS = 10_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService bg = Executors.newSingleThreadExecutor();

    private float density;
    private ScrollView scroll;
    private View hero, heroInfo, allApps, infoPanel, btnPlay, btnInfo;
    private ImageView heroBackdrop, heroPoster;
    private TextView heroService, heroTitle, heroOverview, heroMeta, trendLabel, recentLabel, credit;
    private LinearLayout heroDots, serviceRows;
    private View page0, appsLabel;

    // Now playing card
    private View nowPlaying;
    private ImageView npArt, npPlay;
    private TextView npTitle, npArtist, npApp;
    private android.media.session.MediaSessionManager sessions;
    private android.media.session.MediaController npController;
    private boolean posterModeNow;
    private float infoWidthDp = 440;
    private boolean useDumpsys;
    private String npShownPkg, npShownSong;
    private final Runnable pollMedia = new Runnable() {
        @Override
        public void run() {
            bg.execute(() -> {
                List<MediaStatus.Playing> list = MediaStatus.read();
                String cover = null;
                for (MediaStatus.Playing p : list) {
                    if (p.audible || p.pkg.equals(npShownPkg)) {
                        cover = MediaStatus.coverUrl(p.title, p.artist);
                        break;
                    }
                }
                String coverUrl = cover;
                handler.post(() -> {
                    showFromDumpsys(list);
                    if (coverUrl != null && nowPlaying.getVisibility() == View.VISIBLE) {
                        ImageLoader.get(MainActivity.this).into(npArt, coverUrl, (int) (64 * density), (int) (64 * density));
                    }
                });
            });
            handler.postDelayed(this, 3000);
        }
    };
    private final android.media.session.MediaSessionManager.OnActiveSessionsChangedListener sessionsChanged =
            this::pickSession;
    private final android.media.session.MediaController.Callback npCallback = new android.media.session.MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(android.media.session.PlaybackState state) {
            updateNowPlaying();
        }

        @Override
        public void onMetadataChanged(android.media.MediaMetadata metadata) {
            updateNowPlaying();
        }

        @Override
        public void onSessionDestroyed() {
            setController(null);
        }
    };
    private boolean hasRecents;
    private RecyclerView appsRow, recentRow, trendRow, allAppsGrid;
    private TextView[] tabs;
    private WeatherLine weather;

    private List<Apps.App> apps = new ArrayList<>();
    private Catalog.Kind kind = Catalog.Kind.HOME;
    private Catalog.Page page;
    private int heroIdx;
    private Catalog.Title infoTitle;
    private int loadGen;
    private boolean firstFocus = true;
    private boolean userMoved;

    private final Runnable rotate = new Runnable() {
        @Override
        public void run() {
            boolean busy = infoPanel.getVisibility() == View.VISIBLE;   // keeps moving under Play / More info; pauses while reading More info
            if (page != null && page.featured.size() > 1 && !busy) {
                heroIdx = (heroIdx + 1) % page.featured.size();
                showHero(true);
            }
            handler.postDelayed(this, ROTATE_MS);
        }
    };

    private final Runnable minuteTick = new Runnable() {
        @Override
        public void run() {
            weather.refresh();
            handler.postDelayed(this, 60_000L);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        density = getResources().getDisplayMetrics().density;

        scroll = findViewById(R.id.scroll);
        hero = findViewById(R.id.hero);
        heroInfo = findViewById(R.id.heroInfo);
        heroBackdrop = findViewById(R.id.heroBackdrop);
        heroPoster = findViewById(R.id.heroPoster);
        heroService = findViewById(R.id.heroService);
        heroTitle = findViewById(R.id.heroTitle);
        heroOverview = findViewById(R.id.heroOverview);
        heroMeta = findViewById(R.id.heroMeta);
        heroDots = findViewById(R.id.heroDots);
        serviceRows = findViewById(R.id.serviceRows);
        page0 = findViewById(R.id.page);
        appsLabel = findViewById(R.id.appsLabel);
        nowPlaying = findViewById(R.id.nowPlaying);
        npArt = findViewById(R.id.npArt);
        npPlay = findViewById(R.id.npPlay);
        npTitle = findViewById(R.id.npTitle);
        npArtist = findViewById(R.id.npArtist);
        npApp = findViewById(R.id.npApp);
        round(npArt, 8);
        findViewById(R.id.npPrev).setOnClickListener(v -> {
            if (npController != null) npController.getTransportControls().skipToPrevious();
            else MediaStatus.key(this, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        });
        findViewById(R.id.npNext).setOnClickListener(v -> {
            if (npController != null) npController.getTransportControls().skipToNext();
            else MediaStatus.key(this, android.view.KeyEvent.KEYCODE_MEDIA_NEXT);
        });
        npPlay.setOnClickListener(v -> {
            if (npController == null) {
                MediaStatus.key(this, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                handler.postDelayed(() -> handler.post(pollMedia), 600);
                return;
            }
            android.media.session.PlaybackState st = npController.getPlaybackState();
            if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                npController.getTransportControls().pause();
            } else {
                npController.getTransportControls().play();
            }
        });
        findViewById(R.id.npPrev).setNextFocusLeftId(R.id.btnInfo);
        btnPlay = findViewById(R.id.btnPlay);
        btnInfo = findViewById(R.id.btnInfo);
        appsRow = findViewById(R.id.appsRow);
        recentRow = findViewById(R.id.recentRow);
        trendRow = findViewById(R.id.trendRow);
        trendLabel = findViewById(R.id.trendLabel);
        recentLabel = findViewById(R.id.recentLabel);
        credit = findViewById(R.id.credit);
        allApps = findViewById(R.id.allApps);
        allAppsGrid = findViewById(R.id.allAppsGrid);
        infoPanel = findViewById(R.id.infoPanel);
        weather = new WeatherLine(this, findViewById(R.id.weather), findViewById(R.id.weatherDot));

        round(hero, 14);
        round(heroPoster, 10);
        round(findViewById(R.id.infoPoster), 10);

        for (RecyclerView rv : new RecyclerView[]{appsRow, recentRow, trendRow}) {
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rv.setItemAnimator(null);
        }
        allAppsGrid.setLayoutManager(new GridLayoutManager(this, 6));
        allAppsGrid.setItemAnimator(null);

        // Side menu
        click(R.id.railHome, () -> selectTab(Catalog.Kind.HOME, true));
        click(R.id.railSearch, () -> Apps.globalSearch(this, null));
        click(R.id.railMovies, () -> selectTab(Catalog.Kind.MOVIES, true));
        click(R.id.railInputs, () -> showInputs(findViewById(R.id.railInputs)));
        click(R.id.railApps, this::openAllApps);
        click(R.id.railSettings, () -> open(new Intent(Settings.ACTION_SETTINGS)));
        for (int id : new int[]{R.id.railHome, R.id.railSearch, R.id.railMovies, R.id.railInputs, R.id.railApps, R.id.railSettings}) {
            findViewById(id).setNextFocusRightId(R.id.btnPlay);
        }

        // Tabs
        tabs = new TextView[]{findViewById(R.id.tabHome), findViewById(R.id.tabMovies), findViewById(R.id.tabShows),
                findViewById(R.id.tabApps), findViewById(R.id.tabSettings)};
        tabs[0].setOnClickListener(v -> selectTab(Catalog.Kind.HOME, false));
        tabs[1].setOnClickListener(v -> selectTab(Catalog.Kind.MOVIES, false));
        tabs[2].setOnClickListener(v -> selectTab(Catalog.Kind.SHOWS, false));
        tabs[3].setOnClickListener(v -> openAllApps());
        tabs[4].setOnClickListener(v -> open(new Intent(Settings.ACTION_SETTINGS)));
        for (TextView t : tabs) t.setNextFocusDownId(R.id.btnPlay);
        tabs[0].setNextFocusLeftId(R.id.railHome);
        tabs[4].setNextFocusRightId(R.id.tabSettings);

        // Featured banner
        btnPlay.setOnClickListener(v -> playFeatured());
        btnInfo.setOnClickListener(v -> {
            if (page != null && !page.featured.isEmpty()) showInfo(page.featured.get(heroIdx));
        });
        btnPlay.setNextFocusUpId(R.id.tabHome);
        btnInfo.setNextFocusUpId(R.id.tabMovies);
        btnPlay.setNextFocusLeftId(R.id.railHome);
        View.OnFocusChangeListener toTop = (v, has) -> {
            if (has) scroll.smoothScrollTo(0, 0);
        };
        btnPlay.setOnFocusChangeListener(toTop);
        btnInfo.setOnFocusChangeListener(toTop);
        for (TextView t : tabs) t.setOnFocusChangeListener(toTop);

        // More info
        findViewById(R.id.infoClose).setOnClickListener(v -> hideInfo());
        findViewById(R.id.infoPlay).setOnClickListener(v -> {
            if (infoTitle != null) DeepLinks.open(this, infoTitle);
            hideInfo();
        });

        markTab(Catalog.Kind.HOME);   // onResume loads the titles
        RemoteService.start(this);
        Morning.schedule(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        apps = Apps.load(this);
        appsRow.setAdapter(new AppsAdapter(Apps.featured(this, apps), true));
        List<Recents.Recent> recents = Recents.load(this, apps, 8);
        hasRecents = !recents.isEmpty();
        showHomeRows();
        recentRow.setAdapter(new RecentAdapter(recents));
        loadCatalog();
        weather.refresh();
        prefetchFlights();
        watchSessions(true);
        handler.removeCallbacks(rotate);
        handler.postDelayed(rotate, ROTATE_MS);
        handler.postDelayed(minuteTick, 60_000L);
        if (firstFocus || getCurrentFocus() == null) {
            firstFocus = false;
            userMoved = false;
            btnPlay.post(() -> btnPlay.requestFocus());
        }
    }

    /** Home pressed while already home: close overlays and go back to the banner. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        infoPanel.setVisibility(View.GONE);
        allApps.setVisibility(View.GONE);
        if (kind != Catalog.Kind.HOME) selectTab(Catalog.Kind.HOME, false);
        scroll.smoothScrollTo(0, 0);
        btnPlay.requestFocus();
    }

    @Override
    protected void onPause() {
        super.onPause();
        watchSessions(false);
        handler.removeCallbacks(rotate);
        handler.removeCallbacks(minuteTick);
    }

    @Override
    public void onBackPressed() {
        if (infoPanel.getVisibility() == View.VISIBLE) {
            hideInfo();
        } else if (allApps.getVisibility() == View.VISIBLE) {
            allApps.setVisibility(View.GONE);
            findViewById(R.id.railApps).requestFocus();
        } else {
            scroll.smoothScrollTo(0, 0);
            btnPlay.requestFocus();
        }
    }

    @Override
    public boolean dispatchKeyEvent(android.view.KeyEvent e) {
        if (e.getAction() == android.view.KeyEvent.ACTION_DOWN) userMoved = true;
        return super.dispatchKeyEvent(e);
    }

    /** Keeps the screensaver's saved flight list fresh (at most every 20 minutes, within OpenSky's free limit). */
    private void prefetchFlights() {
        java.io.File f = GlobeView.cacheFile(this);
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 20 * 60_000L) return;
        bg.execute(() -> GlobeView.download(getApplicationContext()));
    }

    // ---- tabs and data ---------------------------------------------------------------------------

    private void selectTab(Catalog.Kind k, boolean fromRail) {
        markTab(k);
        loadCatalog();
        if (fromRail) {
            scroll.smoothScrollTo(0, 0);
            btnPlay.requestFocus();
        }
    }

    /** Your apps and Recently used belong to Home; Movies and TV Shows are pages of their own. */
    private void showHomeRows() {
        boolean home = kind == Catalog.Kind.HOME;
        appsLabel.setVisibility(home ? View.VISIBLE : View.GONE);
        appsRow.setVisibility(home ? View.VISIBLE : View.GONE);
        recentLabel.setVisibility(home && hasRecents ? View.VISIBLE : View.GONE);
        recentRow.setVisibility(home && hasRecents ? View.VISIBLE : View.GONE);
    }

    /** Highlights the tab and side-menu icon for this kind and sets the row title, without loading. */
    private void markTab(Catalog.Kind k) {
        kind = k;
        showHomeRows();
        for (int i = 0; i < 3; i++) tabs[i].setSelected(i == k.ordinal());
        findViewById(R.id.railHome).setSelected(k == Catalog.Kind.HOME);
        findViewById(R.id.railMovies).setSelected(k == Catalog.Kind.MOVIES);
        trendLabel.setText(k == Catalog.Kind.MOVIES ? R.string.trending_movies
                : k == Catalog.Kind.SHOWS ? R.string.trending_shows : R.string.trending_now);
    }

    private void loadCatalog() {
        final int gen = ++loadGen;
        final Catalog.Kind k = kind;
        bg.execute(() -> {
            Catalog.Page p = Catalog.load(getApplicationContext(), k);
            handler.post(() -> {
                if (gen != loadGen || isDestroyed()) return;
                page = p;
                heroIdx = 0;
                buildDots();
                showHero(false);
                trendRow.setAdapter(new PosterAdapter(p.trending));
                buildServiceRows(p);
                credit.setVisibility(p.fromTmdb ? View.VISIBLE : View.GONE);
                if (!userMoved) btnPlay.requestFocus();
                if (!p.featured.isEmpty()) ImageLoader.get(this).prefetch(p.featured.get(1 % p.featured.size()).backdrop);
            });
        });
    }

    /** On Movies / TV Shows: one row of 5 per service instead of the long trending row. */
    private void buildServiceRows(Catalog.Page p) {
        serviceRows.removeAllViews();
        boolean show = kind != Catalog.Kind.HOME && !p.serviceRows.isEmpty();
        serviceRows.setVisibility(show ? View.VISIBLE : View.GONE);
        trendLabel.setVisibility(show ? View.GONE : View.VISIBLE);
        trendRow.setVisibility(show ? View.GONE : View.VISIBLE);
        if (!show) return;
        String what = kind == Catalog.Kind.MOVIES ? "Top 10 movies on " : "Top 10 TV shows on ";
        for (Catalog.ServiceRow r : p.serviceRows) {
            TextView title = (TextView) getLayoutInflater().inflate(R.layout.row_title, serviceRows, false);
            title.setText(what + r.label);
            serviceRows.addView(title);
            RecyclerView rv = (RecyclerView) getLayoutInflater().inflate(R.layout.row_posters, serviceRows, false);
            rv.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
            rv.setItemAnimator(null);
            rv.setAdapter(new PosterAdapter(r.items));
            serviceRows.addView(rv);
        }
    }

    // ---- featured banner -------------------------------------------------------------------------

    private void buildDots() {
        heroDots.removeAllViews();
        int n = page == null ? 0 : page.featured.size();
        for (int i = 0; i < n; i++) {
            View d = new View(this);
            d.setBackgroundResource(R.drawable.dot);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams((int) (7 * density), (int) (7 * density));
            lp.setMarginStart((int) (5 * density));
            lp.setMarginEnd((int) (5 * density));
            heroDots.addView(d, lp);
        }
    }

    private void showHero(boolean animate) {
        if (page == null || page.featured.isEmpty()) return;
        Catalog.Title t = page.featured.get(heroIdx);
        Runnable apply = () -> {
            boolean wide = page.fromTmdb;
            // Movies / TV Shows: poster on the left, details and buttons on the right. Home: wide film still.
            boolean posterMode = kind != Catalog.Kind.HOME && t.poster != null;
            layoutHero(posterMode, wide);
            int w = getResources().getDisplayMetrics().widthPixels;
            String bg = wide && t.backdrop != null ? t.backdrop : t.poster;
            ImageLoader.get(this).into(heroBackdrop, bg, wide ? w : w / 3, wide ? w * 9 / 16 : w / 2);
            heroBackdrop.setAlpha(posterMode ? 0.4f : wide ? 1f : 0.35f);
            if (posterMode || !wide) {
                ImageLoader.get(this).into(heroPoster, t.poster, heroPoster.getLayoutParams().width,
                        heroPoster.getLayoutParams().height);
            }
            fitTitle(t.title);
            heroService.setText(t.serviceLabel != null ? t.serviceLabel : page.fromTmdb ? "Trending this week" : t.movie ? "Top movies" : "Top TV shows");
            String label = heroService.getText().toString();
            if (t.servicePkg != null) {
                android.text.SpannableString sp = new android.text.SpannableString("\u25CF  " + label);
                sp.setSpan(new android.text.style.ForegroundColorSpan(serviceColor(t.servicePkg)), 0, 1, 0);
                heroService.setText(sp);
            }
            heroService.setTextColor(0xF2FFFFFF);
            heroOverview.setText(t.overview);
            heroOverview.setVisibility(TextUtils.isEmpty(t.overview) ? View.GONE : View.VISIBLE);
            heroMeta.setText(meta(t));
            ((TextView) btnPlay).setText(t.servicePkg != null ? getString(R.string.play) : getString(R.string.search));
            for (int i = 0; i < heroDots.getChildCount(); i++) heroDots.getChildAt(i).setAlpha(i == heroIdx ? 1f : 0.35f);
            heroInfo.setTranslationX(animate ? 14 * density : 0);
            heroInfo.animate().alpha(1f).translationX(0f).setDuration(animate ? 320 : 0).start();
            if (page.featured.size() > 1) {
                ImageLoader.get(this).prefetch(page.featured.get((heroIdx + 1) % page.featured.size()).backdrop);
            }
        };
        if (animate) heroInfo.animate().alpha(0f).setDuration(160).withEndAction(apply).start();
        else apply.run();
    }

    /** Places the poster and the details block for the two banner layouts. */
    private void layoutHero(boolean posterMode, boolean wide) {
        posterModeNow = posterMode;
        android.widget.FrameLayout.LayoutParams pp = (android.widget.FrameLayout.LayoutParams) heroPoster.getLayoutParams();
        android.widget.FrameLayout.LayoutParams ip = (android.widget.FrameLayout.LayoutParams) heroInfo.getLayoutParams();
        heroPoster.setVisibility(posterMode || !wide ? View.VISIBLE : View.GONE);
        if (posterMode) {
            pp.width = (int) (130 * density);
            pp.height = (int) (195 * density);
            pp.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            pp.setMarginStart((int) (32 * density));
            pp.setMarginEnd(0);
            ip.gravity = android.view.Gravity.START | android.view.Gravity.CENTER_VERTICAL;
            ip.setMarginStart((int) ((32 + 130 + 28) * density));
            ip.topMargin = 0;
            infoWidthDp = fitBesideCard(190, 560);
            ip.width = (int) (infoWidthDp * density);
        } else {
            pp.width = (int) (120 * density);
            pp.height = (int) (180 * density);
            pp.gravity = android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL;
            pp.setMarginStart(0);
            pp.setMarginEnd((int) (44 * density));
            ip.gravity = android.view.Gravity.START | android.view.Gravity.TOP;
            ip.setMarginStart((int) (32 * density));
            ip.topMargin = (int) (24 * density);
            infoWidthDp = fitBesideCard(32, 440);
            ip.width = (int) (infoWidthDp * density);
        }
        heroPoster.setLayoutParams(pp);
        heroInfo.setLayoutParams(ip);
    }

    /**
     * Big for short names, smaller for long ones, so the service label, description and Play / More info always
     * fit inside the banner (Bebas Neue is about 0.44 of its size wide per letter).
     */
    private void fitTitle(String title) {
        float widthDp = infoWidthDp;
        int len = title.length();
        float size;
        int lines;
        if (len * 50 * 0.44f <= widthDp) {
            size = 50;
            lines = 1;
        } else if (len * 38 * 0.44f <= widthDp * 1.9f) {
            size = 38;
            lines = 2;
        } else {
            size = 30;
            lines = 2;
        }
        heroTitle.setTextSize(size);
        heroTitle.setMaxLines(lines);
        heroTitle.setText(title);
        heroOverview.setMaxLines(lines == 1 ? 2 : 1);   // a two-line title leaves room for one line of description
    }

    /** Details width so the text never runs under the Now playing card. */
    private float fitBesideCard(float startDp, float wantDp) {
        if (nowPlaying.getVisibility() != View.VISIBLE) return wantDp;
        float heroDp = hero.getWidth() > 0 ? hero.getWidth() / density : 862;
        return Math.max(260, Math.min(wantDp, heroDp - 16 - 270 - 20 - startDp));
    }

    // ---- now playing ----------------------------------------------------------------------------

    private void watchSessions(boolean on) {
        try {
            if (sessions == null) sessions = getSystemService(android.media.session.MediaSessionManager.class);
            android.content.ComponentName me = new android.content.ComponentName(this, NowPlayingListener.class);
            if (on) {
                if (useDumpsys) throw new SecurityException("use dumpsys");
                sessions.addOnActiveSessionsChangedListener(sessionsChanged, me, handler);
                pickSession(sessions.getActiveSessions(me));
            } else {
                handler.removeCallbacks(pollMedia);
                if (!useDumpsys) sessions.removeOnActiveSessionsChangedListener(sessionsChanged);
                setController(null);
            }
        } catch (SecurityException e) {
            // No notification access (blocked on low-memory TVs): read the media status instead, if allowed.
            useDumpsys = checkSelfPermission(android.Manifest.permission.DUMP)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (useDumpsys) {
                handler.removeCallbacks(pollMedia);
                handler.post(pollMedia);
            }
        }
    }

    /** The app playing right now (music in the background); keeps showing it while paused. */
    private void pickSession(List<android.media.session.MediaController> list) {
        android.media.session.MediaController pick = null;
        if (list != null) {
            for (android.media.session.MediaController c : list) {
                if (c.getPackageName().equals(getPackageName())) continue;
                android.media.session.PlaybackState st = c.getPlaybackState();
                if (st != null && st.getState() == android.media.session.PlaybackState.STATE_PLAYING) {
                    pick = c;
                    break;
                }
                if (pick == null && npController != null && c.getSessionToken().equals(npController.getSessionToken())) pick = c;
            }
        }
        setController(pick);
    }

    /** Card from the dumpsys reading: an app that's really making sound; keep showing it while it's paused. */
    private void showFromDumpsys(List<MediaStatus.Playing> list) {
        MediaStatus.Playing pick = null;
        for (MediaStatus.Playing p : list) {
            if (p.pkg.equals(getPackageName())) continue;
            if (p.audible) {
                pick = p;
                break;
            }
            if (pick == null && p.pkg.equals(npShownPkg)) pick = p;
        }
        boolean was = nowPlaying.getVisibility() == View.VISIBLE;
        npShownPkg = pick == null ? null : pick.pkg;
        if (pick != null) {
            String appLabel = pick.pkg;
            android.graphics.drawable.Drawable appIcon = null;
            try {
                android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(pick.pkg, 0);
                appLabel = String.valueOf(getPackageManager().getApplicationLabel(ai));
                appIcon = getPackageManager().getApplicationIcon(ai);
            } catch (Exception ignored) {
            }
            npTitle.setText(pick.title != null ? pick.title : appLabel);
            npArtist.setText(pick.artist != null ? pick.artist : "");
            npArtist.setVisibility(pick.artist != null ? View.VISIBLE : View.GONE);
            npApp.setText(appLabel);
            if (!(pick.title + "|" + pick.artist).equals(npShownSong)) {   // new song: app icon until the cover loads
                npArt.setTag(R.id.image_url, null);
                npArt.setImageDrawable(appIcon);
                npShownSong = pick.title + "|" + pick.artist;
            }
            npPlay.setImageResource(pick.audible ? R.drawable.ic_pause : R.drawable.ic_play);
            npPlay.setContentDescription(pick.audible ? "Pause" : "Play");
        }
        nowPlaying.setVisibility(pick != null ? View.VISIBLE : View.GONE);
        btnInfo.setNextFocusRightId(pick != null ? R.id.npPrev : View.NO_ID);
        if ((pick != null) != was && page != null && !page.featured.isEmpty()) {
            layoutHero(posterModeNow, page.fromTmdb);
            fitTitle(heroTitle.getText().toString());
        }
    }

    private void setController(android.media.session.MediaController c) {
        if (npController != null && c != null && npController.getSessionToken().equals(c.getSessionToken())) {
            updateNowPlaying();
            return;
        }
        if (npController != null) npController.unregisterCallback(npCallback);
        npController = c;
        if (c != null) c.registerCallback(npCallback, handler);
        updateNowPlaying();
    }

    private void updateNowPlaying() {
        if (useDumpsys) return;
        android.media.session.MediaController c = npController;
        android.media.session.PlaybackState st = c != null ? c.getPlaybackState() : null;
        int state = st != null ? st.getState() : android.media.session.PlaybackState.STATE_NONE;
        boolean show = c != null && state != android.media.session.PlaybackState.STATE_NONE
                && state != android.media.session.PlaybackState.STATE_STOPPED
                && state != android.media.session.PlaybackState.STATE_ERROR;
        boolean was = nowPlaying.getVisibility() == View.VISIBLE;
        if (show) {
            android.media.MediaMetadata md = c.getMetadata();
            String title = md == null ? null : md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
            if (TextUtils.isEmpty(title) && md != null) title = md.getString(android.media.MediaMetadata.METADATA_KEY_TITLE);
            String artist = md == null ? null : md.getString(android.media.MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE);
            if (TextUtils.isEmpty(artist) && md != null) artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST);
            if (TextUtils.isEmpty(artist) && md != null) artist = md.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
            String appLabel = c.getPackageName();
            android.graphics.drawable.Drawable appIcon = null;
            try {
                android.content.pm.ApplicationInfo ai = getPackageManager().getApplicationInfo(c.getPackageName(), 0);
                appLabel = String.valueOf(getPackageManager().getApplicationLabel(ai));
                appIcon = getPackageManager().getApplicationIcon(ai);
            } catch (Exception ignored) {
            }
            npTitle.setText(TextUtils.isEmpty(title) ? appLabel : title);
            npArtist.setText(TextUtils.isEmpty(artist) ? "" : artist);
            npArtist.setVisibility(TextUtils.isEmpty(artist) ? View.GONE : View.VISIBLE);
            npApp.setText(appLabel);
            android.graphics.Bitmap art = null;
            String artUri = null;
            if (md != null) {
                for (String k : new String[]{android.media.MediaMetadata.METADATA_KEY_ALBUM_ART,
                        android.media.MediaMetadata.METADATA_KEY_ART, android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON}) {
                    if (art == null) art = md.getBitmap(k);
                }
                for (String k : new String[]{android.media.MediaMetadata.METADATA_KEY_ALBUM_ART_URI,
                        android.media.MediaMetadata.METADATA_KEY_ART_URI, android.media.MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI}) {
                    String u = md.getString(k);
                    if (artUri == null && u != null && u.startsWith("http")) artUri = u;
                }
            }
            if (art != null) npArt.setImageBitmap(art);
            else if (artUri != null) ImageLoader.get(this).into(npArt, artUri, (int) (64 * density), (int) (64 * density));
            else npArt.setImageDrawable(appIcon);
            boolean playing = state == android.media.session.PlaybackState.STATE_PLAYING;
            npPlay.setImageResource(playing ? R.drawable.ic_pause : R.drawable.ic_play);
            npPlay.setContentDescription(playing ? "Pause" : "Play");
        }
        nowPlaying.setVisibility(show ? View.VISIBLE : View.GONE);
        btnInfo.setNextFocusRightId(show ? R.id.npPrev : View.NO_ID);
        if (show != was && page != null && !page.featured.isEmpty()) {   // make room for the card, or take it back
            layoutHero(posterModeNow, page.fromTmdb);
            fitTitle(heroTitle.getText().toString());
        }
    }

    private static int serviceColor(String pkg) {
        if ("com.netflix.ninja".equals(pkg)) return 0xFFFF3D47;
        if ("com.amazon.amazonvideo.livingroom".equals(pkg)) return 0xFF4FC3FF;
        return 0xF2FFFFFF;
    }

    private String meta(Catalog.Title t) {
        List<String> parts = new ArrayList<>();
        if (!TextUtils.isEmpty(t.genre)) parts.add(t.genre);
        if (!TextUtils.isEmpty(t.year)) parts.add(t.year);
        parts.add(t.movie ? "Movie" : "Series");
        return TextUtils.join("  ·  ", parts);
    }

    private void playFeatured() {
        if (page == null || page.featured.isEmpty()) return;
        DeepLinks.open(this, page.featured.get(heroIdx));
    }

    private void showInfo(Catalog.Title t) {
        infoTitle = t;
        ((TextView) findViewById(R.id.infoService)).setText(t.serviceLabel != null ? "On " + t.serviceLabel
                : (t.rank > 0 ? "#" + t.rank + " trending this week" : ""));
        ((TextView) findViewById(R.id.infoTitle)).setText(t.title);
        ((TextView) findViewById(R.id.infoMeta)).setText(meta(t));
        ((TextView) findViewById(R.id.infoOverview)).setText(TextUtils.isEmpty(t.overview) ? "" : t.overview);
        ((TextView) findViewById(R.id.infoPlay)).setText(t.servicePkg != null
                ? getString(R.string.play_in, t.serviceLabel) : getString(R.string.search));
        ImageLoader.get(this).into(findViewById(R.id.infoPoster), t.poster != null ? t.poster : t.backdrop,
                (int) (140 * density), (int) (210 * density));
        infoPanel.setVisibility(View.VISIBLE);
        findViewById(R.id.infoPlay).requestFocus();
    }

    private void hideInfo() {
        infoPanel.setVisibility(View.GONE);
        btnInfo.requestFocus();
    }

    // ---- overlays and system screens --------------------------------------------------------------

    private void openAllApps() {
        allAppsGrid.setAdapter(new AppsAdapter(apps, false));
        allApps.setVisibility(View.VISIBLE);
        allAppsGrid.post(() -> {
            RecyclerView.ViewHolder vh = allAppsGrid.findViewHolderForAdapterPosition(0);
            if (vh != null) vh.itemView.requestFocus();
        });
    }

    private void open(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void click(int id, Runnable r) {
        findViewById(id).setOnClickListener(v -> r.run());
    }

    private void round(View v, int dp) {
        v.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline o) {
                o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp * density);
            }
        });
        v.setClipToOutline(true);
    }

    /** A drawable shared by two rows keeps only the last bounds it was given, so each tile gets its own copy. */
    private static android.graphics.drawable.Drawable copy(android.graphics.drawable.Drawable d) {
        if (d == null || d.getConstantState() == null) return d;
        return d.getConstantState().newDrawable().mutate();
    }

    /** Lifts a tile when it has focus, and scrolls its row into view. */
    private void focusFx(View card, int scrollTarget) {
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? 1.07f : 1f;
            v.animate().scaleX(s).scaleY(s).setDuration(150).start();
            v.setElevation(has ? 8 * density : 0);
            if (has && scrollTarget >= 0) {
                View row = (View) v.getParent();
                android.graphics.Rect r = new android.graphics.Rect(0, 0, row.getWidth(), row.getHeight());
                ((ViewGroup) page0).offsetDescendantRectToMyCoords(row, r);
                scroll.smoothScrollTo(0, Math.max(0, r.top - (int) (40 * density)));
            }
        });
    }

    /** Inputs dropdown: HDMI ports with connection state, AV and Live TV. */
    private void showInputs(View anchor) {
        View panel = getLayoutInflater().inflate(R.layout.popup_inputs, (ViewGroup) findViewById(R.id.root), false);
        LinearLayout rows = panel.findViewById(R.id.rows);
        final PopupWindow popup = new PopupWindow(panel, (int) (220 * density), ViewGroup.LayoutParams.WRAP_CONTENT, true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        for (TvInputs.Source s : TvInputs.list(this)) {
            View row = getLayoutInflater().inflate(R.layout.item_input, rows, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(s.liveTv ? R.drawable.ic_livetv
                    : s.hdmi ? R.drawable.ic_input : R.drawable.ic_av);
            ((TextView) row.findViewById(R.id.title)).setText(s.label);
            TextView status = row.findViewById(R.id.status);
            View dot = row.findViewById(R.id.dot);
            if (s.state == TvInputManager.INPUT_STATE_CONNECTED) {
                status.setText(R.string.connected);
            } else {
                status.setVisibility(View.GONE);
                dot.setVisibility(View.GONE);
            }
            row.setOnClickListener(v -> {
                popup.dismiss();
                if (!TvInputs.open(this, s)) Toast.makeText(this, R.string.not_available, Toast.LENGTH_SHORT).show();
            });
            rows.addView(row);
        }
        if (rows.getChildCount() == 0) {
            Toast.makeText(this, R.string.no_inputs, Toast.LENGTH_SHORT).show();
            return;
        }
        popup.showAsDropDown(anchor, (int) (48 * density), (int) (-40 * density), Gravity.START);
        rows.getChildAt(0).requestFocus();
    }

    // ---- row adapters ----------------------------------------------------------------------------

    private static final class Holder extends RecyclerView.ViewHolder {
        Holder(View v) {
            super(v);
        }
    }

    /** Your apps (with an All apps tile at the end) or the All apps grid. */
    private final class AppsAdapter extends RecyclerView.Adapter<Holder> {
        private final List<Apps.App> list;
        private final boolean withAllTile;

        AppsAdapter(List<Apps.App> list, boolean withAllTile) {
            this.list = list;
            this.withAllTile = withAllTile;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_app_tile, parent, false);
            round(v, 9);
            focusFx(v, withAllTile ? 0 : -1);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            ImageView banner = h.itemView.findViewById(R.id.banner);
            View iconBox = h.itemView.findViewById(R.id.iconBox);
            ImageView icon = h.itemView.findViewById(R.id.icon);
            TextView label = h.itemView.findViewById(R.id.label);
            if (position == list.size()) {   // All apps
                banner.setImageDrawable(null);
                iconBox.setVisibility(View.VISIBLE);
                icon.setImageResource(R.drawable.ic_apps);
                label.setText(R.string.all_apps);
                h.itemView.setContentDescription(getString(R.string.all_apps));
                h.itemView.setOnClickListener(v -> openAllApps());
                return;
            }
            Apps.App a = list.get(position);
            h.itemView.setContentDescription(a.label);
            if (a.banner != null) {
                banner.setImageDrawable(copy(a.banner));
                iconBox.setVisibility(View.GONE);
            } else {
                banner.setImageDrawable(null);
                iconBox.setVisibility(View.VISIBLE);
                icon.setImageDrawable(copy(a.icon));
                label.setText(a.label);
            }
            h.itemView.setOnClickListener(v -> {
                allApps.setVisibility(View.GONE);
                Apps.launch(MainActivity.this, a);
            });
        }

        @Override
        public int getItemCount() {
            return list.size() + (withAllTile ? 1 : 0);
        }
    }

    private final class RecentAdapter extends RecyclerView.Adapter<Holder> {
        private final List<Recents.Recent> list;

        RecentAdapter(List<Recents.Recent> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_recent, parent, false);
            round(v, 9);
            focusFx(v, 0);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Recents.Recent r = list.get(position);
            ImageView banner = h.itemView.findViewById(R.id.banner);
            ImageView icon = h.itemView.findViewById(R.id.icon);
            if (r.app.banner != null) {
                banner.setImageDrawable(copy(r.app.banner));
                icon.setVisibility(View.GONE);
            } else {
                banner.setImageDrawable(null);
                icon.setVisibility(View.VISIBLE);
                icon.setImageDrawable(copy(r.app.icon));
            }
            String when = r.when(MainActivity.this);
            ((TextView) h.itemView.findViewById(R.id.caption)).setText(when);
            h.itemView.setContentDescription(when);
            h.itemView.setOnClickListener(v -> Apps.launch(MainActivity.this, r.app));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

    private final class PosterAdapter extends RecyclerView.Adapter<Holder> {
        private final List<Catalog.Title> list;

        PosterAdapter(List<Catalog.Title> list) {
            this.list = list;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_poster, parent, false);
            round(v, 9);
            focusFx(v, 0);
            return new Holder(v);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder h, int position) {
            Catalog.Title t = list.get(position);
            ImageLoader.get(MainActivity.this).into(h.itemView.findViewById(R.id.poster), t.poster,
                    (int) (100 * density), (int) (150 * density));
            ((TextView) h.itemView.findViewById(R.id.rank)).setText(String.valueOf(t.rank));
            h.itemView.setContentDescription(t.title);
            h.itemView.setOnClickListener(v -> showInfo(t));
        }

        @Override
        public int getItemCount() {
            return list.size();
        }
    }

}
