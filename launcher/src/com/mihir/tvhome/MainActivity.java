package com.mihir.tvhome;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Apple TV style home screen: clock and weather top-left, pills top-right, the focused dock app's
 * name over an ambient glow, a dock of pinned apps, and every other app in a grid that scrolls up on
 * Down. Long-press a tile to move, hide, uninstall or inspect it. Plain framework views only (no
 * AndroidX); the only network use is the weather (Open-Meteo, or met.no as a fallback), refreshed
 * while the home is visible.
 */
public class MainActivity extends Activity {

    // Dock, hidden apps, Live TV and grid width come from res/values/config.xml.
    private String[] dockApps;
    private final Set<String> hidden = new HashSet<>();
    private String liveTvPkg, inputPickerPkg;
    private int cols;

    static final class App {
        String pkg, label;
        ComponentName component;
        Drawable art;
        boolean isBanner;
        int color;
    }

    private final Map<String, App> apps = new HashMap<>();   // every launchable app, by package
    private final List<String> order = new ArrayList<>();     // shown apps: the first `cols` form the dock
    private final Set<String> userHidden = new HashSet<>();   // hidden from the long-press menu
    private String focusedPkg, movingPkg;
    private boolean relayout;

    private final Map<String, Integer> colorCache = new HashMap<>();
    private final List<View> dockCards = new ArrayList<>();
    private final List<View> gridCards = new ArrayList<>();
    private final List<View> pills = new ArrayList<>();

    private HomeScrollView scroll;
    private LinearLayout content, dockRow;
    private FrameLayout hero;
    private LinearLayout grid;
    private View glow;
    private ImageView wallBack, wallFront;
    private float shade;            // 0..1 grid dimming of the photo, see setShade
    private ValueAnimator shadeAnim;
    private String[] wallpapers = new String[0];
    private int wallIndex = -1;
    private long wallChangedAt;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable rotateWallpaper = this::nextWallpaper;
    static final long WALLPAPER_INTERVAL_MS = 10 * 60 * 1000;
    private final Runnable refreshWeather = this::updateWeather;
    private long weatherAt;
    private TextView greeting, clock, ampm, date, weather, title, wifiPill, hiddenPill, appsLabel;
    private int glowColor = 0xFF3A3A3A;
    private ValueAnimator glowAnim;
    // The glow is drawn at 1/10 of the screen and stretched: it's a soft gradient, so it looks the
    // same, but the GPU samples a tiny texture instead of evaluating a gradient for every pixel.
    private final Bitmap glowBitmap = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
    private final Canvas glowCanvas = new Canvas(glowBitmap);
    private int lastFocusedId = View.NO_ID;
    private boolean firstShow = true;
    private int packageSeq;
    private PopupWindow menu;

    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { updateClock(); updateWifi(); }
    };
    private final BroadcastReceiver packages = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { loadApps(); }
    };

    // ---------------------------------------------------------------- lifecycle

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        dockApps = getResources().getStringArray(R.array.dock_apps);
        hidden.addAll(Arrays.asList(getResources().getStringArray(R.array.hidden_apps)));
        hidden.add(getPackageName());
        liveTvPkg = getString(R.string.live_tv_package);
        inputPickerPkg = getString(R.string.input_picker_package);
        cols = getResources().getInteger(R.integer.columns);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        setContentView(buildUi());
        getWindow().setBackgroundDrawable(null); // the root view paints black until the photo is in
        // 16-bit window (the photos are 16-bit already): half the memory traffic per frame, which
        // matters when the system composes the window on the GPU instead of the display hardware.
        getWindow().setFormat(android.graphics.PixelFormat.RGB_565);
        loadApps();
        showWeather();

        IntentFilter pf = new IntentFilter();
        pf.addAction(Intent.ACTION_PACKAGE_ADDED);
        pf.addAction(Intent.ACTION_PACKAGE_REMOVED);
        pf.addAction(Intent.ACTION_PACKAGE_CHANGED);
        pf.addDataScheme("package");
        registerReceiver(packages, pf);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter tf = new IntentFilter(Intent.ACTION_TIME_TICK);
        tf.addAction(Intent.ACTION_TIME_CHANGED);
        tf.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        tf.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        registerReceiver(tick, tf);
        updateClock();
        updateWifi();
        restoreFocus();
        // Catch installs/removals that happened while another app was in front.
        if (getPackageManager().getChangedPackages(packageSeq) != null) loadApps();
        if (firstShow) { firstShow = false; playEntrance(); }
        long sinceWeather = SystemClock.elapsedRealtime() - weatherAt;
        if (weatherAt == 0 || sinceWeather >= Weather.INTERVAL_MS || weather.getVisibility() != View.VISIBLE) updateWeather();
        else handler.postDelayed(refreshWeather, Weather.INTERVAL_MS - sinceWeather);
        // New photo on return if the current one has been up for the full interval.
        if (wallIndex < 0 || SystemClock.elapsedRealtime() - wallChangedAt >= WALLPAPER_INTERVAL_MS) nextWallpaper();
        else scheduleWallpaper(WALLPAPER_INTERVAL_MS - (SystemClock.elapsedRealtime() - wallChangedAt));
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(rotateWallpaper);
        handler.removeCallbacks(refreshWeather);
        unregisterReceiver(tick);
        if (menu != null) menu.dismiss();
        if (movingPkg != null) { movingPkg = null; layoutApps(); }
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(packages);
        super.onDestroy();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Home pressed while already home: jump back to the first dock app.
        if (menu != null) menu.dismiss();
        if (movingPkg != null) { movingPkg = null; layoutApps(); }
        if (!dockCards.isEmpty()) dockCards.get(0).requestFocus();
        scroll.smoothScrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Home has nowhere to go back to; Back from the grid returns to the dock.
        if (scroll.getScrollY() > 0 && !dockCards.isEmpty()) dockCards.get(0).requestFocus();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        // The TV's GPU manages only a few full-screen fills per frame at 60 fps, so the background is
        // just the photo (scrim baked in, grid dimming as a colour filter) and, on the dock, the glow.
        // Wallpaper: two stacked layers so a new photo can crossfade in over the old one.
        wallBack = wallpaperLayer();
        wallFront = wallpaperLayer();
        root.addView(wallBack, new FrameLayout.LayoutParams(-1, -1));
        root.addView(wallFront, new FrameLayout.LayoutParams(-1, -1));
        try {
            wallpapers = getAssets().list("wallpapers");
        } catch (java.io.IOException ignored) { }

        glow = flatView();
        glow.setBackground(new android.graphics.drawable.BitmapDrawable(getResources(), glowBitmap));
        root.addView(glow, new FrameLayout.LayoutParams(-1, -1));
        setGlow(glowColor);

        scroll = new HomeScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setFillViewport(true);
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setClipChildren(false);
        content.setClipToPadding(false);
        scroll.addView(content, new FrameLayout.LayoutParams(-1, -2));

        // Hero: exactly one screen tall, header on top, title + dock at the bottom.
        hero = new FrameLayout(this);
        hero.setClipChildren(false);
        int screenH = getResources().getDisplayMetrics().heightPixels;
        content.addView(hero, new LinearLayout.LayoutParams(-1, screenH));

        hero.addView(buildHeader(), lp(-1, -2, Gravity.TOP, dp(48), dp(36), dp(48), 0));

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setClipChildren(false);
        bottom.setClipToPadding(false);
        hero.addView(bottom, lp(-1, -2, Gravity.BOTTOM, dp(48), 0, dp(48), dp(30)));

        title = text(34, "sans-serif-light", Color.WHITE);
        title.setShadowLayer(dp(18), 0, 0, 0x66000000);
        title.setSingleLine(true);
        bottom.addView(title, new LinearLayout.LayoutParams(-2, -2));
        ((LinearLayout.LayoutParams) title.getLayoutParams()).setMargins(dp(8), 0, 0, dp(14));

        dockRow = new LinearLayout(this);
        dockRow.setOrientation(LinearLayout.HORIZONTAL);
        dockRow.setGravity(Gravity.CENTER);
        dockRow.setClipChildren(false);
        dockRow.setClipToPadding(false);
        dockRow.setPadding(dp(20), dp(22), dp(20), dp(22));
        GradientDrawable band = new GradientDrawable();
        band.setCornerRadius(dp(26));
        band.setColor(0x1FFFFFFF);
        band.setStroke(dp(1), 0x14FFFFFF);
        dockRow.setBackground(band);
        bottom.addView(dockRow, new LinearLayout.LayoutParams(-1, -2));

        appsLabel = text(11, "sans-serif-medium", 0x99FFFFFF);
        appsLabel.setText("ВСЕ ПРИЛОЖЕНИЯ");
        appsLabel.setLetterSpacing(0.22f);
        content.addView(appsLabel, llp(-2, -2, dp(68), dp(8), 0, dp(18)));

        grid = new LinearLayout(this);
        grid.setOrientation(LinearLayout.VERTICAL);
        grid.setClipChildren(false);
        grid.setClipToPadding(false);
        content.addView(grid, llp(-1, -2, dp(68), 0, dp(68), dp(160)));
        return root;
    }

    private View buildHeader() {
        FrameLayout header = new FrameLayout(this);
        header.setClipChildren(false);

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false); // let the text shadows spill past each line instead of showing a box
        greeting = text(16, "sans-serif-medium", 0xB3FFFFFF);
        greeting.setLetterSpacing(0.25f);
        greeting.setSingleLine(true);
        greeting.setPadding(0, 0, dp(16), 0);
        left.addView(greeting);

        LinearLayout clockRow = new LinearLayout(this);
        clockRow.setGravity(Gravity.BOTTOM);
        clock = text(54, "sans-serif-thin", Color.WHITE);
        clock.setIncludeFontPadding(false);
        clockRow.addView(clock);
        ampm = text(12, "sans-serif-light", 0xCCFFFFFF);
        clockRow.addView(ampm, llp(-2, -2, dp(6), 0, 0, dp(8)));
        left.addView(clockRow, llp(-2, -2, 0, dp(2), 0, dp(2)));

        date = text(16, "sans-serif-medium", 0xF2FFFFFF);
        date.setSingleLine(true);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        date.setLetterSpacing(0.12f);
        left.addView(date);

        weather = text(23, "sans-serif-light", Color.WHITE);
        weather.setSingleLine(true);
        weather.setVisibility(View.GONE);
        left.addView(weather, llp(-2, -2, 0, dp(10), 0, 0));
        for (TextView t : new TextView[]{greeting, clock, ampm, date, weather}) t.setShadowLayer(dp(10), 0, dp(1), 0xB3000000);
        // Small text over bright photos: a tight, dark shadow keeps the letters' edges readable.
        date.setShadowLayer(dp(3), 0, dp(1), 0xF2000000);
        header.addView(left, new FrameLayout.LayoutParams(dp(420), -2, Gravity.START | Gravity.TOP));

        LinearLayout right = new LinearLayout(this);
        right.setGravity(Gravity.CENTER_VERTICAL);
        right.setClipChildren(false);
        TextView inputs = pill("Источники", "⇥");
        inputs.setOnClickListener(v -> showInputs(v));
        // Streaming boxes (Chromecast) have no HDMI inputs or tuner: nothing to switch to, so no pill.
        if (!hasInputs()) inputs.setVisibility(View.GONE);
        wifiPill = pill("Wi-Fi", "●");
        wifiPill.setOnClickListener(v -> launch(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        // Only shown while something is hidden; lists the hidden apps so they can be brought back.
        hiddenPill = pill("Скрытые", "◌");
        hiddenPill.setOnClickListener(v -> showHidden(v));
        hiddenPill.setVisibility(View.GONE);
        TextView settings = pill("Настройки", "⚙");
        settings.setOnClickListener(v -> showSettingsMenu(v));
        for (TextView p : new TextView[]{inputs, wifiPill, hiddenPill, settings}) {
            right.addView(p, llp(-2, dp(30), dp(10), 0, 0, 0));
            pills.add(p);
        }
        header.addView(right, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
        return header;
    }

    private TextView pill(String label, String glyph) {
        TextView p = text(11, "sans-serif-medium", 0xE6FFFFFF);
        p.setText(glyph + "  " + label);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(14), 0, dp(14), 0);
        p.setFocusable(true);
        p.setId(View.generateViewId());
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(15));
        bg.setColor(0x66000000);
        bg.setStroke(dp(1), 0x33FFFFFF);
        p.setBackground(bg);
        p.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : 0x66000000);
            ((TextView) v).setTextColor(has ? 0xFF111111 : 0xE6FFFFFF);
            v.animate().scaleX(has ? 1.08f : 1f).scaleY(has ? 1.08f : 1f)
                    .setInterpolator(new OvershootInterpolator(2f)).setDuration(220).start();
            if (has && !relayout) { lastFocusedId = v.getId(); focusedPkg = null; scroll.smoothScrollTo(0, 0); fadeGlow(1f); }
        });
        return p;
    }

    // ---------------------------------------------------------------- apps

    /** Re-read the launchable apps and merge them into the saved order; new apps go to the end. */
    private void loadApps() {
        PackageManager pm = getPackageManager();
        android.content.pm.ChangedPackages changed = pm.getChangedPackages(packageSeq);
        if (changed != null) packageSeq = changed.getSequenceNumber();
        Intent q = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        Map<String, App> byPkg = apps;
        byPkg.clear();
        for (ResolveInfo ri : pm.queryIntentActivities(q, 0)) {
            ActivityInfo ai = ri.activityInfo;
            if (hidden.contains(ai.packageName) || byPkg.containsKey(ai.packageName)) continue;
            App a = new App();
            a.pkg = ai.packageName;
            a.label = String.valueOf(ri.loadLabel(pm));
            a.component = new ComponentName(ai.packageName, ai.name);
            Drawable banner = ai.loadBanner(pm);
            if (banner == null) banner = ai.applicationInfo.loadBanner(pm);
            a.isBanner = banner != null;
            a.art = banner != null ? banner : ri.loadIcon(pm);
            a.color = sampleColor(a.pkg, a.art);
            byPkg.put(a.pkg, a);
        }

        // Saved order first (the config's dock apps on first run), then anything new, alphabetically.
        SharedPreferences prefs = getPreferences(MODE_PRIVATE);
        userHidden.clear();
        userHidden.addAll(split(prefs.getString("hidden", "")));
        userHidden.retainAll(byPkg.keySet());
        List<String> saved = split(prefs.getString("order", ""));
        if (saved.isEmpty()) saved = Arrays.asList(dockApps);
        order.clear();
        for (String p : saved) if (byPkg.containsKey(p) && !userHidden.contains(p) && !order.contains(p)) order.add(p);
        List<App> fresh = new ArrayList<>();
        for (App a : byPkg.values()) if (!order.contains(a.pkg) && !userHidden.contains(a.pkg)) fresh.add(a);
        Collections.sort(fresh, (x, y) -> x.label.compareToIgnoreCase(y.label));
        for (App a : fresh) order.add(a.pkg);
        saveOrder();
        layoutApps();
    }

    /** Lay `order` out: the first `cols` apps in the dock, the rest in the grid below. */
    private void layoutApps() {
        // Removing the focused tile hands focus to the first pill for a moment; don't let that count.
        String keepPkg = focusedPkg;
        int keepId = lastFocusedId;
        relayout = true;
        dockRow.removeAllViews();
        grid.removeAllViews();
        dockCards.clear();
        gridCards.clear();

        int d = Math.min(cols, order.size());
        for (int i = 0; i < d; i++) {
            View tile = tile(apps.get(order.get(i)), true);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i == 0 ? 0 : dp(10), 0, i == d - 1 ? 0 : dp(10), 0);
            dockRow.addView(tile, p);
        }

        int rest = order.size() - d;
        LinearLayout row = null;
        for (int i = 0; i < rest; i++) {
            if (i % cols == 0) {
                row = new LinearLayout(this);
                row.setClipChildren(false);
                row.setClipToPadding(false);
                grid.addView(row, llp(-1, -2, 0, 0, 0, dp(26)));
            }
            View tile = tile(apps.get(order.get(d + i)), false);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -2, 1f);
            p.setMargins(i % cols == 0 ? 0 : dp(10), 0, i % cols == cols - 1 ? 0 : dp(10), 0);
            row.addView(tile, p);
        }
        // Pad the last row so tiles keep their width.
        if (row != null) for (int i = rest % cols; i != 0 && i < cols; i++) {
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, 1, 1f);
            p.setMargins(dp(10), 0, i == cols - 1 ? 0 : dp(10), 0);
            row.addView(new View(this), p);
        }
        appsLabel.setVisibility(rest == 0 ? View.GONE : View.VISIBLE);
        hiddenPill.setText("◌  Скрытые (" + userHidden.size() + ")");
        hiddenPill.setVisibility(userHidden.isEmpty() ? View.GONE : View.VISIBLE);
        wireFocus();

        if (d > 0) { App first = apps.get(order.get(0)); title.setText(first.label); setGlow(first.color); }
        relayout = false;
        focusedPkg = keepPkg;
        lastFocusedId = keepId;
        restoreFocus();
    }

    /** Focus the last focused app (it may have moved) or pill, else the first dock app. */
    private void restoreFocus() {
        View v = null;
        if (focusedPkg != null) for (View c : allCards()) if (((App) c.getTag()).pkg.equals(focusedPkg)) v = c;
        if (v == null && focusedPkg == null && lastFocusedId != View.NO_ID) v = findViewById(lastFocusedId);
        if ((v == null || v.getVisibility() != View.VISIBLE) && !dockCards.isEmpty()) v = dockCards.get(0);
        if (v != null) v.requestFocus();
    }

    private List<View> allCards() {
        List<View> all = new ArrayList<>(dockCards);
        all.addAll(gridCards);
        return all;
    }

    private void saveOrder() {
        getPreferences(MODE_PRIVATE).edit()
                .putString("order", String.join(",", order))
                .putString("hidden", String.join(",", userHidden))
                .apply();
    }

    private static List<String> split(String s) {
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) if (!p.isEmpty()) out.add(p);
        return out;
    }

    // ---------------------------------------------------------------- app menu

    private void showAppMenu(View anchor, App app) {
        Uri uri = Uri.parse("package:" + app.pkg);
        List<TextView> rows = new ArrayList<>();
        rows.add(menuRow("Переместить", () -> startMove(app.pkg)));
        rows.add(menuRow("Скрыть", () -> hideApp(app.pkg)));
        rows.add(menuRow("Удалить", () -> launch(new Intent(Intent.ACTION_DELETE, uri))));
        rows.add(menuRow("О приложении", () -> launch(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, uri))));
        showMenu(anchor, rows);
    }

    private void startMove(String pkg) {
        movingPkg = pkg;
        focusedPkg = pkg;
        layoutApps();
        Toast.makeText(this, "Двигайте стрелками, OK — готово", Toast.LENGTH_LONG).show();
    }

    /** Swap the moving app with its neighbour: ±1 along the row, ±cols to the row above/below. */
    private void moveApp(int step) {
        int i = order.indexOf(movingPkg), j = i + step;
        if (i < 0 || j < 0 || j >= order.size()) return;
        Collections.swap(order, i, j);
        saveOrder();
        layoutApps();
    }

    private void hideApp(String pkg) {
        userHidden.add(pkg);
        order.remove(pkg);
        if (pkg.equals(focusedPkg)) focusedPkg = null;
        saveOrder();
        layoutApps();
    }

    private void showHidden(View anchor) {
        List<App> list = new ArrayList<>();
        for (String p : userHidden) if (apps.containsKey(p)) list.add(apps.get(p));
        Collections.sort(list, (x, y) -> x.label.compareToIgnoreCase(y.label));
        List<TextView> rows = new ArrayList<>();
        for (App a : list) rows.add(menuRow(a.label, () -> {
            userHidden.remove(a.pkg);
            order.add(a.pkg);
            saveOrder();
            layoutApps();
        }));
        if (!rows.isEmpty()) showMenu(anchor, rows);
    }

    /** A 16:9 card with the app's banner; the name shows under it while focused. */
    private View tile(App app, boolean inDock) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setClipChildren(false);
        box.setClipToPadding(false);

        FrameLayout card = new AspectFrame(this);
        card.setId(View.generateViewId());
        card.setFocusable(true);
        card.setClickable(true);
        card.setTag(app);
        card.setClipToOutline(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(10));
            }
        });
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(app.isBanner ? 0xFF1C1C1E : darken(app.color, 0.45f));
        card.setBackground(bg);

        ImageView img = new ImageView(this);
        img.setImageDrawable(app.art);
        if (app.isBanner) {
            img.setScaleType(ImageView.ScaleType.CENTER_CROP);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
        } else {
            img.setScaleType(ImageView.ScaleType.FIT_CENTER);
            card.addView(img, new FrameLayout.LayoutParams(-1, -1));
            img.setPadding(dp(22), dp(14), dp(22), dp(14));
        }

        // Focus ring and sheen, drawn above the art.
        GradientDrawable ring = new GradientDrawable();
        ring.setCornerRadius(dp(10));
        ring.setStroke(dp(2), 0xE6FFFFFF);
        ring.setOrientation(GradientDrawable.Orientation.TL_BR);
        ring.setColors(new int[]{0x26FFFFFF, 0x00FFFFFF, 0x00FFFFFF});
        if (app.pkg.equals(movingPkg)) ring.setStroke(dp(4), 0xFF4DA3FF);
        View shine = new View(this);
        shine.setBackground(ring);
        shine.setAlpha(0f);
        card.addView(shine, new FrameLayout.LayoutParams(-1, -1));

        box.addView(card, new LinearLayout.LayoutParams(-1, -2));

        TextView label = text(11, "sans-serif-medium", Color.WHITE);
        label.setText(app.label);
        label.setSingleLine(true);
        label.setGravity(Gravity.CENTER);
        label.setAlpha(0f);
        if (!inDock) box.addView(label, llp(-1, -2, 0, dp(10), 0, 0));

        card.setOnClickListener(v -> launch(new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
                .setComponent(app.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)));
        card.setOnLongClickListener(v -> { showAppMenu(v, app); return true; });
        card.setOnFocusChangeListener((v, has) -> {
            float s = has ? (inDock ? 1.12f : 1.1f) : 1f;
            // No elevation shadow: on the Chromecast's GPU it cost ~25% of the frames while scrolling.
            v.animate().scaleX(s).scaleY(s)
                    .setInterpolator(has ? new OvershootInterpolator(1.8f) : new DecelerateInterpolator())
                    .setDuration(has ? 320 : 200).start();
            shine.animate().alpha(has ? 1f : 0f).setDuration(160).start();
            label.animate().alpha(has ? 1f : 0f).translationY(has ? dp(4) : 0).setDuration(200).start();
            if (!has) return;
            lastFocusedId = v.getId();
            focusedPkg = app.pkg;
            if (inDock) {
                title.setText(app.label);
                title.animate().alpha(1f).setDuration(200).start();
                animateGlow(app.color);
                fadeGlow(1f);
                scroll.smoothScrollTo(0, 0);
            } else {
                fadeGlow(0f);
                title.animate().alpha(0f).setDuration(200).start();
                scrollToRow(box);
            }
        });

        (inDock ? dockCards : gridCards).add(card);
        return box;
    }

    /** Explicit D-pad targets: one row per press, ends of rows and of the top bar don't wrap. */
    private void wireFocus() {
        int d = dockCards.size(), g = gridCards.size();
        List<View> top = new ArrayList<>();
        for (View p : pills) if (p.getVisibility() == View.VISIBLE) top.add(p);
        for (int i = 0; i < top.size(); i++) {
            View p = top.get(i);
            p.setNextFocusLeftId(i == 0 ? p.getId() : top.get(i - 1).getId());
            p.setNextFocusRightId(i == top.size() - 1 ? p.getId() : top.get(i + 1).getId());
            p.setNextFocusUpId(p.getId());
            p.setNextFocusDownId(d > 0 ? dockCards.get(Math.min(d - 1, d / 2)).getId() : p.getId());
        }
        int topId = top.get(0).getId();
        for (int i = 0; i < d; i++) {
            View c = dockCards.get(i);
            c.setNextFocusUpId(topId);
            c.setNextFocusLeftId(i == 0 ? c.getId() : dockCards.get(i - 1).getId());
            c.setNextFocusRightId(i == d - 1 ? c.getId() : dockCards.get(i + 1).getId());
            c.setNextFocusDownId(g > 0 ? gridCards.get(Math.min(i, g - 1)).getId() : c.getId());
        }
        int lastRow = (g - 1) / cols;
        for (int j = 0; j < g; j++) {
            View c = gridCards.get(j);
            int r = j / cols, col = j % cols;
            c.setNextFocusUpId(r == 0
                    ? (d > 0 ? dockCards.get(Math.min(col, d - 1)).getId() : topId)
                    : gridCards.get(j - cols).getId());
            c.setNextFocusDownId(j + cols < g ? gridCards.get(j + cols).getId()
                    : r < lastRow ? gridCards.get(g - 1).getId() : c.getId());
            c.setNextFocusLeftId(col == 0 ? c.getId() : gridCards.get(j - 1).getId());
            c.setNextFocusRightId(col == cols - 1 || j == g - 1 ? c.getId() : gridCards.get(j + 1).getId());
        }
    }

    private void scrollToRow(View tileBox) {
        View row = (View) tileBox.getParent();
        int y = grid.getTop() + row.getTop();
        int screenH = getResources().getDisplayMetrics().heightPixels;
        // First grid row: slide the dock band up to the top edge; later rows centre on screen.
        View band = (View) dockRow.getParent();
        int target = grid.indexOfChild(row) == 0
                ? hero.getTop() + band.getTop() + dockRow.getTop() - dp(22)
                : y - (int) (screenH * 0.42f);
        int max = Math.max(0, content.getHeight() - scroll.getHeight());
        scroll.smoothScrollTo(0, Math.max(0, Math.min(target, max)));
    }

    // ---------------------------------------------------------------- inputs

    private List<TvInputInfo> passthroughInputs() {
        TvInputManager tim = (TvInputManager) getSystemService(TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) for (TvInputInfo t : tim.getTvInputList()) {
            if (t.isPassthroughInput() && !t.isHidden(this)) inputs.add(t);
        }
        return inputs;
    }

    private boolean hasInputs() {
        return !passthroughInputs().isEmpty() || isInstalled(liveTvPkg) || isInstalled(inputPickerPkg);
    }

    private void showInputs(View anchor) {
        List<TextView> rows = new ArrayList<>();

        List<TvInputInfo> inputs = passthroughInputs();
        Collections.sort(inputs, (x, y) -> inputLabel(x).compareToIgnoreCase(inputLabel(y)));
        for (TvInputInfo t : inputs) {
            rows.add(menuRow(inputLabel(t), () -> {
                Intent i = new Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(t.getId()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                if (!launch(i)) openPackage(inputPickerPkg);
            }));
        }
        if (isInstalled(liveTvPkg)) rows.add(menuRow("Эфирное ТВ", () -> openPackage(liveTvPkg)));
        if (inputs.isEmpty() && isInstalled(inputPickerPkg)) rows.add(menuRow("Все источники…", () -> openPackage(inputPickerPkg)));

        if (rows.isEmpty()) rows.add(menuRow("Настройки", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        showMenu(anchor, rows);
    }

    /** A dropdown of rows under `anchor`; picking a row closes it and runs the row's action. */
    private void showMenu(View anchor, List<TextView> rows) {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(16));
        bg.setColor(0xF02A2A2E);
        bg.setStroke(dp(1), 0x26FFFFFF);
        list.setBackground(bg);

        PopupWindow pw = new PopupWindow(list, dp(200), -2, true);
        for (TextView r : rows) {
            r.setOnClickListener(v -> { pw.dismiss(); ((Runnable) v.getTag()).run(); });
            list.addView(r, new LinearLayout.LayoutParams(-1, dp(36)));
        }
        for (int i = 0; i < rows.size(); i++) {
            TextView r = rows.get(i);
            r.setNextFocusUpId(rows.get(Math.max(0, i - 1)).getId());
            r.setNextFocusDownId(rows.get(Math.min(rows.size() - 1, i + 1)).getId());
            r.setNextFocusLeftId(r.getId());
            r.setNextFocusRightId(r.getId());
        }
        pw.setOutsideTouchable(true);
        pw.setElevation(dp(16));
        pw.setOnDismissListener(() -> { menu = null; anchor.requestFocus(); });
        menu = pw;
        // Below the anchor, or above it when it sits too low (the dock) for the menu to fit.
        list.measure(View.MeasureSpec.makeMeasureSpec(dp(200), View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        int below = at[1] + anchor.getHeight() + dp(8);
        boolean fits = below + list.getMeasuredHeight() <= getResources().getDisplayMetrics().heightPixels;
        pw.showAtLocation(anchor, Gravity.NO_GRAVITY, at[0], fits ? below : at[1] - list.getMeasuredHeight() - dp(8));
        rows.get(0).requestFocus();
    }

    private String inputLabel(TvInputInfo t) {
        CharSequence c = t.loadCustomLabel(this);
        if (c == null || c.length() == 0) c = t.loadLabel(this);
        return String.valueOf(c);
    }

    private TextView menuRow(CharSequence label, Runnable action) {
        TextView r = text(13, "sans-serif", Color.WHITE);
        r.setText(label);
        r.setTag(action);
        r.setId(View.generateViewId());
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), 0, dp(14), 0);
        r.setFocusable(true);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(10));
        bg.setColor(Color.TRANSPARENT);
        r.setBackground(bg);
        r.setOnFocusChangeListener((v, has) -> {
            bg.setColor(has ? 0xF2FFFFFF : Color.TRANSPARENT);
            r.setTextColor(has ? 0xFF111111 : Color.WHITE);
        });
        return r;
    }

    // ---------------------------------------------------------------- status

    private void updateClock() {
        Calendar now = Calendar.getInstance();
        int h = now.get(Calendar.HOUR_OF_DAY);
        greeting.setText(h < 5 ? "ДОБРОЙ НОЧИ" : h < 12 ? "ДОБРОЕ УТРО" : h < 17 ? "ДОБРЫЙ ДЕНЬ" : "ДОБРЫЙ ВЕЧЕР");
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        ampm.setText(is24 ? "" : (h < 12 ? "AM" : "PM"));
        date.setText(new SimpleDateFormat("EEEE  d MMMM", Locale.getDefault()).format(now.getTime()).toUpperCase(Locale.getDefault()));
    }

    private void updateWifi() {
        // Connectivity decides online/offline: with location off the SSID and network id are hidden.
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String name = "Нет сети";
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) name = "Ethernet";
        else if (nc != null) {
            name = "Wi-Fi";
            try {
                WifiManager wm = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
                String s = wm == null || wm.getConnectionInfo() == null ? null : wm.getConnectionInfo().getSSID();
                if (s != null && !s.contains("unknown ssid")) name = s.replace("\"", "");
            } catch (SecurityException ignored) { }
        }
        // Green dot while connected; the span keeps its colour when focus recolours the pill text.
        SpannableString label = new SpannableString("●  " + name);
        if (nc != null) label.setSpan(new ForegroundColorSpan(0xFF34C759), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        wifiPill.setText(label);
    }

    // ---------------------------------------------------------------- screensaver

    private static final int[] SAVER_MINUTES = {5, 10, 15, 30, 60};

    /** Minutes of idle before the screensaver: the system screen timeout. */
    private int screensaverMinutes() {
        return Settings.System.getInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, 0) / 60000;
    }

    /** Minutes of idle before the TV goes to sleep by itself (about 19 on our TCL), or 0 for never. */
    private int sleepMinutes() {
        int ms = Settings.Secure.getInt(getContentResolver(), "sleep_timeout", -1);
        return ms <= 0 ? 0 : (ms + 59999) / 60000;
    }

    /** "Настройки": the TV's settings, plus the screensaver's idle time and a way to see it now. */
    private void showSettingsMenu(View anchor) {
        List<TextView> rows = new ArrayList<>();
        rows.add(menuRow("Настройки ТВ", () -> launch(new Intent(Settings.ACTION_SETTINGS))));
        rows.add(menuRow("Заставка · " + shortMinutes(screensaverMinutes()), () -> showScreensaverMenu(anchor)));
        rows.add(menuRow("Показать заставку", () -> launch(new Intent(this, ScreensaverActivity.class))));
        showMenu(anchor, rows);
    }

    private void showScreensaverMenu(View anchor) {
        int current = screensaverMinutes();
        List<TextView> rows = new ArrayList<>();
        TextView selected = null;
        for (int m : SAVER_MINUTES) {
            // The tick is always there, invisible on the other rows, so the labels line up.
            SpannableString label = new SpannableString("✓   через " + longMinutes(m));
            if (m != current) label.setSpan(new ForegroundColorSpan(Color.TRANSPARENT), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView r = menuRow(label, () -> setScreensaverMinutes(m));
            rows.add(r);
            if (m == current) selected = r;
        }
        showMenu(anchor, rows);
        if (selected != null) selected.requestFocus();
    }

    private void setScreensaverMinutes(int m) {
        // WRITE_SETTINGS is granted over ADB (install-launcher.ps1); TVs have no screen to grant it on.
        if (!Settings.System.canWrite(this)) {
            Toast.makeText(this, "Нет разрешения менять время заставки (см. README)", Toast.LENGTH_LONG).show();
            return;
        }
        Settings.System.putInt(getContentResolver(), Settings.System.SCREEN_OFF_TIMEOUT, m * 60000);
        int sleep = sleepMinutes();
        Toast.makeText(this, sleep > 0 && sleep <= m
                ? "Телевизор сам засыпает через " + longMinutes(sleep) + " простоя — заставка не успеет появиться"
                : "Заставка будет через " + longMinutes(m) + " простоя", Toast.LENGTH_LONG).show();
    }

    private static String shortMinutes(int m) {
        return m >= 60 && m % 60 == 0 ? m / 60 + " ч" : m + " мин";
    }

    /** "5 минут", "21 минуту", "1 час". */
    private static String longMinutes(int m) {
        if (m >= 60 && m % 60 == 0) { int h = m / 60; return h == 1 ? "1 час" : h + (h < 5 ? " часа" : " часов"); }
        int t = m % 100, o = m % 10;
        return m + (t >= 11 && t <= 14 ? " минут" : o == 1 ? " минуту" : o >= 2 && o <= 4 ? " минуты" : " минут");
    }

    // ---------------------------------------------------------------- weather

    private void updateWeather() {
        handler.removeCallbacks(refreshWeather);
        // Until the first reading arrives (e.g. location just switched on), retry every minute.
        handler.postDelayed(refreshWeather, weather.getVisibility() == View.VISIBLE ? Weather.INTERVAL_MS : 60 * 1000);
        weatherAt = SystemClock.elapsedRealtime();
        Weather.refresh(this, () -> { if (!isDestroyed()) showWeather(); });
    }

    /** "Иркутск   ❄️  −15°" from the last saved reading. */
    private void showWeather() {
        Weather w = Weather.load(this);
        if (w == null) { weather.setVisibility(View.GONE); return; }
        weather.setText((w.city.isEmpty() ? "" : w.city + "   ") + Weather.emoji(w.code, w.day) + "  " + Weather.degrees(w.temp));
        weather.setVisibility(View.VISIBLE);
    }

    // ---------------------------------------------------------------- glow

    private void setGlow(int color) {
        glowColor = color;
        GradientDrawable g = new GradientDrawable();
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientCenter(0.5f, 0.78f);
        g.setGradientRadius(glowBitmap.getWidth() * 0.62f);
        g.setColors(new int[]{withAlpha(color, 0.5f), withAlpha(color, 0.14f), Color.TRANSPARENT});
        g.setBounds(0, 0, glowBitmap.getWidth(), glowBitmap.getHeight());
        glowBitmap.eraseColor(Color.TRANSPARENT);
        g.draw(glowCanvas);
        glow.invalidate();
    }

    private void animateGlow(int to) {
        if (glowAnim != null) glowAnim.cancel();
        glowAnim = ValueAnimator.ofObject(new ArgbEvaluator(), glowColor, to);
        glowAnim.setDuration(450);
        glowAnim.addUpdateListener(a -> setGlow((int) a.getAnimatedValue()));
        glowAnim.start();
    }

    /** 1 on the dock and top bar, 0 in the grid: the glow fades out (and stops being drawn). */
    private void fadeGlow(float alpha) {
        // Fading out quickly takes this full-screen layer off the GPU for most of the scroll down.
        glow.animate().alpha(alpha).setDuration(alpha < 1f ? 120 : 300).start();
        // The grid sits over the photo's busiest middle part, so dim it while browsing apps.
        float target = alpha < 1f ? 1f : 0f;
        if (shadeAnim != null) shadeAnim.cancel();
        shadeAnim = ValueAnimator.ofFloat(shade, target);
        shadeAnim.setDuration(300);
        shadeAnim.addUpdateListener(a -> setShade((float) a.getAnimatedValue()));
        shadeAnim.start();
    }

    /** Dim the photo by drawing it through a black colour filter: no extra full-screen layer. */
    private void setShade(float f) {
        shade = f;
        for (ImageView w : new ImageView[]{wallBack, wallFront}) {
            if (f == 0f) w.clearColorFilter();
            else w.setColorFilter(Color.argb(Math.round(f * 0xB3), 0, 0, 0), android.graphics.PorterDuff.Mode.SRC_ATOP);
        }
    }

    // ---------------------------------------------------------------- wallpaper

    private ImageView wallpaperLayer() {
        ImageView v = new ImageView(this);
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        return v;
    }

    /** A single-fill view: non-overlapping, so fading it needs no full-screen offscreen buffer. */
    private View flatView() {
        return new View(this) {
            @Override public boolean hasOverlappingRendering() { return false; }
        };
    }

    /** Decode the next photo off the main thread, then crossfade it in over the current one. */
    private void nextWallpaper() {
        handler.removeCallbacks(rotateWallpaper);
        if (wallpapers.length == 0) return;
        if (wallIndex < 0) wallIndex = getPreferences(MODE_PRIVATE).getInt("wallpaper", -1);
        wallIndex = (wallIndex + 1) % wallpapers.length;
        getPreferences(MODE_PRIVATE).edit().putInt("wallpaper", wallIndex).apply();
        wallChangedAt = SystemClock.elapsedRealtime();
        String name = "wallpapers/" + wallpapers[wallIndex];
        new Thread(() -> {
            Bitmap bm = null;
            try (java.io.InputStream in = getAssets().open(name)) {
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inPreferredConfig = Bitmap.Config.RGB_565;
                o.inMutable = true;
                bm = BitmapFactory.decodeStream(in, null, o);
                // Scrim, baked into the photo: darker at the top (clock, pills) and bottom (dock).
                if (bm != null) {
                    GradientDrawable scrim = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                            new int[]{0xB3000000, 0x40000000, 0x26000000, 0x8C000000, 0xE6000000});
                    scrim.setDither(true);
                    scrim.setBounds(0, 0, bm.getWidth(), bm.getHeight());
                    scrim.draw(new Canvas(bm));
                }
            } catch (java.io.IOException ignored) { }
            Bitmap result = bm;
            runOnUiThread(() -> showWallpaper(result));
        }).start();
        scheduleWallpaper(WALLPAPER_INTERVAL_MS);
    }

    private void showWallpaper(Bitmap bm) {
        if (bm == null || isDestroyed()) return;
        // Swap roles: the old front becomes the back, the new photo fades in on top.
        ImageView old = wallFront;
        wallFront = wallBack;
        wallBack = old;
        ViewGroup root = (ViewGroup) wallFront.getParent();
        root.removeView(wallFront);
        root.addView(wallFront, 1, new FrameLayout.LayoutParams(-1, -1));
        wallFront.setImageBitmap(bm);
        wallFront.setAlpha(0f);
        wallFront.animate().alpha(1f).setDuration(firstWallpaper ? 600 : 2000).withEndAction(() -> {
            wallBack.setImageDrawable(null); // release the previous photo
            root.setBackground(null);       // the photo covers the screen; skip the black fill under it
        }).start();
        firstWallpaper = false;
    }

    private boolean firstWallpaper = true;

    private void scheduleWallpaper(long delay) {
        handler.removeCallbacks(rotateWallpaper);
        handler.postDelayed(rotateWallpaper, Math.max(1000, delay));
    }

    /** Average the banner's vivid pixels, then push saturation so the glow reads as a colour. */
    private int sampleColor(String pkg, Drawable d) {
        Integer cached = colorCache.get(pkg);
        if (cached != null) return cached;
        int color = 0xFF3A3A3A;
        try {
            Bitmap bm = Bitmap.createBitmap(24, 14, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(bm);
            d.setBounds(0, 0, 24, 14);
            d.draw(c);
            float[] hsv = new float[3];
            double r = 0, g = 0, b = 0, w = 0;
            for (int y = 0; y < 14; y++) for (int x = 0; x < 24; x++) {
                int p = bm.getPixel(x, y);
                if (Color.alpha(p) < 128) continue;
                Color.colorToHSV(p, hsv);
                double weight = 0.05 + hsv[1] * hsv[2];
                r += Color.red(p) * weight; g += Color.green(p) * weight; b += Color.blue(p) * weight; w += weight;
            }
            bm.recycle();
            if (w > 0) {
                Color.colorToHSV(Color.rgb((int) (r / w), (int) (g / w), (int) (b / w)), hsv);
                if (hsv[1] > 0.12f) { hsv[1] = Math.max(hsv[1], 0.75f); hsv[2] = 0.85f; }
                else { hsv[1] = 0f; hsv[2] = 0.45f; }
                color = Color.HSVToColor(hsv);
            }
        } catch (RuntimeException ignored) { }
        colorCache.put(pkg, color);
        return color;
    }

    // ---------------------------------------------------------------- entrance

    private void playEntrance() {
        View header = hero.getChildAt(0);
        header.setAlpha(0f);
        header.setTranslationY(-dp(12));
        header.animate().alpha(1f).translationY(0).setDuration(500).setStartDelay(80).start();
        title.setAlpha(0f);
        title.animate().alpha(1f).setDuration(500).setStartDelay(200).start();
        for (int i = 0; i < dockRow.getChildCount(); i++) {
            View t = dockRow.getChildAt(i);
            t.setAlpha(0f);
            t.setTranslationY(dp(24));
            t.animate().alpha(1f).translationY(0).setInterpolator(new DecelerateInterpolator(2f))
                    .setDuration(450).setStartDelay(160 + i * 60L).start();
        }
    }

    // ---------------------------------------------------------------- helpers

    private boolean launch(Intent i) {
        try {
            if (!i.hasCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean isInstalled(String pkg) {
        if (pkg == null || pkg.isEmpty()) return false;
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void openPackage(String pkg) {
        Intent i = getPackageManager().getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = getPackageManager().getLaunchIntentForPackage(pkg);
        if (i != null) launch(i);
    }

    private TextView text(float sp, String family, int color) {
        TextView t = new TextView(this);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setTextColor(color);
        return t;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static FrameLayout.LayoutParams lp(int w, int h, int gravity, int l, int t, int r, int b) {
        FrameLayout.LayoutParams p = new FrameLayout.LayoutParams(w, h, gravity);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static LinearLayout.LayoutParams llp(int w, int h, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.setMargins(l, t, r, b);
        return p;
    }

    private static int withAlpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static int darken(int c, float f) {
        return Color.rgb((int) (Color.red(c) * f), (int) (Color.green(c) * f), (int) (Color.blue(c) * f));
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        // Moving an app: arrows shift it through dock and grid, OK or Back finishes.
        if (movingPkg != null) {
            int k = e.getKeyCode();
            int step = k == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : k == KeyEvent.KEYCODE_DPAD_RIGHT ? 1
                    : k == KeyEvent.KEYCODE_DPAD_UP ? -cols : k == KeyEvent.KEYCODE_DPAD_DOWN ? cols : 0;
            boolean done = k == KeyEvent.KEYCODE_DPAD_CENTER || k == KeyEvent.KEYCODE_ENTER || k == KeyEvent.KEYCODE_BACK;
            if (step != 0 || done) {
                if (e.getAction() == KeyEvent.ACTION_DOWN) {
                    if (step != 0) moveApp(step);
                    else { movingPkg = null; layoutApps(); }
                }
                return true;
            }
        }
        // Menu key on the remote opens the Inputs dropdown.
        if (e.getKeyCode() == KeyEvent.KEYCODE_MENU && e.getAction() == KeyEvent.ACTION_UP
                && !pills.isEmpty() && pills.get(0).getVisibility() == View.VISIBLE) {
            showInputs(pills.get(0));
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** 16:9 container. */
    static final class AspectFrame extends FrameLayout {
        AspectFrame(Context c) { super(c); }
        @Override protected void onMeasure(int w, int h) {
            int width = MeasureSpec.getSize(w);
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY));
        }
    }

    /** ScrollView that never auto-scrolls to the focused child; the activity scrolls it by row. */
    static final class HomeScrollView extends android.widget.ScrollView {
        HomeScrollView(Context c) { super(c); }
        @Override protected int computeScrollDeltaToGetChildRectOnScreen(android.graphics.Rect r) { return 0; }
    }
}
