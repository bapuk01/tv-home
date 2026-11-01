package com.mihir.tvhome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.service.dreams.DreamService;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Random;

/**
 * Screensaver: the painted landscape for today's season, sun and weather (Scene), falling snow or
 * rain, the time, the weather with a few days ahead, and this month's calendar. Warm, muted colours
 * in the launcher's style. The text slowly drifts so nothing stays on the same pixels for hours.
 */
public class WeatherDream extends DreamService {
    static final int CREAM = 0xFFF4EADF, DIM = 0x99F4EADF, AMBER = 0xFFE2B47C, TERRA = 0xFFD9967C;
    private static final Locale RU = new Locale("ru");

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private FrameLayout root, overlay;
    private ImageView sceneBack, sceneFront;
    private Precipitation precip;
    private TextView clock, date, temp, city, details, monthTitle, holiday;
    private Icon nowIcon;
    private LinearLayout weatherRow, forecast;
    private MonthView month;
    private String sceneKey;
    private boolean painting, firstScene = true;

    private final Runnable refresh = this::refreshWeather;
    private final Runnable drift = this::drift;
    private final BroadcastReceiver tick = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { minute(); }
    };

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes the TV
        setFullscreen(true);
        setScreenBright(true);
        setContentView(buildUi());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        IntentFilter f = new IntentFilter(Intent.ACTION_TIME_TICK);
        f.addAction(Intent.ACTION_TIME_CHANGED);
        f.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        registerReceiver(tick, f);
        showWeather();
        minute();
        long age = System.currentTimeMillis() - Weather.prefs(this).getLong("w_at", 0);
        if (age >= Weather.INTERVAL_MS) refreshWeather();
        else handler.postDelayed(refresh, Weather.INTERVAL_MS - age);
        handler.postDelayed(drift, 60 * 1000);
        precip.start();
    }

    @Override
    public void onDreamingStopped() {
        unregisterReceiver(tick);
        handler.removeCallbacksAndMessages(null);
        precip.stop();
        super.onDreamingStopped();
    }

    private void refreshWeather() {
        handler.removeCallbacks(refresh);
        handler.postDelayed(refresh, Weather.load(this) == null ? 60 * 1000 : Weather.INTERVAL_MS);
        Weather.refresh(this, () -> { showWeather(); updateScene(); });
    }

    private void minute() {
        Calendar now = Calendar.getInstance();
        boolean is24 = android.text.format.DateFormat.is24HourFormat(this);
        clock.setText(new SimpleDateFormat(is24 ? "H:mm" : "h:mm", Locale.getDefault()).format(now.getTime()));
        date.setText(new SimpleDateFormat("EEEE, d MMMM", RU).format(now.getTime()).toUpperCase(RU));
        monthTitle.setText(month(now));
        month.setToday(now);
        String h = MonthView.nextHoliday(now);
        holiday.setText(h);
        holiday.setVisibility(h == null ? View.GONE : View.VISIBLE);
        updateScene();
    }

    // ---------------------------------------------------------------- layout

    private View buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        sceneBack = new ImageView(this);
        sceneFront = new ImageView(this);
        for (ImageView v : new ImageView[]{sceneBack, sceneFront}) {
            v.setScaleType(ImageView.ScaleType.CENTER_CROP);
            root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        }
        precip = new Precipitation(this);
        root.addView(precip, new FrameLayout.LayoutParams(-1, -1));

        overlay = new FrameLayout(this);
        overlay.setClipChildren(false);
        root.addView(overlay, new FrameLayout.LayoutParams(-1, -1));

        // Bottom left: time, date, weather now, the next days.
        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setClipChildren(false);
        clock = text(92, "sans-serif-thin", CREAM);
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        date = text(16, "sans-serif-medium", 0xE6F4EADF);
        date.setLetterSpacing(0.12f);
        date.setPadding(0, 0, dp(16), 0); // letterSpacing is not counted in the measured width
        left.addView(date, llp(-2, -2, dp(4), dp(2), 0, 0));

        weatherRow = new LinearLayout(this);
        weatherRow.setGravity(Gravity.CENTER_VERTICAL);
        weatherRow.setClipChildren(false);
        nowIcon = new Icon(this);
        weatherRow.addView(nowIcon, new LinearLayout.LayoutParams(dp(58), dp(58)));
        temp = text(46, "sans-serif-light", CREAM);
        temp.setIncludeFontPadding(false);
        weatherRow.addView(temp, llp(-2, -2, dp(10), 0, dp(18), 0));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        city = text(18, "sans-serif-medium", CREAM);
        details = text(13, "sans-serif", DIM);
        words.addView(city);
        words.addView(details, llp(-2, -2, 0, dp(2), 0, 0));
        weatherRow.addView(words);
        left.addView(weatherRow, llp(-2, -2, 0, dp(18), 0, 0));

        forecast = new LinearLayout(this);
        forecast.setPadding(dp(8), dp(12), dp(8), dp(12));
        forecast.setBackground(panel());
        left.addView(forecast, llp(-2, -2, 0, dp(16), 0, 0));
        for (TextView t : new TextView[]{clock, date, temp, city, details}) t.setShadowLayer(dp(8), 0, dp(1), 0x99000000);
        left.setLayerType(View.LAYER_TYPE_HARDWARE, null); // redrawn once a minute, not with every snowflake
        overlay.addView(left, lp(-2, -2, Gravity.BOTTOM | Gravity.START, dp(60), 0, 0, dp(46)));

        // Bottom right: the month.
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(22), dp(18), dp(22), dp(16));
        card.setBackground(panel());
        monthTitle = text(13, "sans-serif-medium", CREAM);
        monthTitle.setLetterSpacing(0.22f);
        card.addView(monthTitle, llp(-2, -2, dp(6), 0, 0, 0));
        month = new MonthView(this);
        card.addView(month, llp(-2, -2, 0, dp(12), 0, 0));
        holiday = text(12, "sans-serif", DIM);
        holiday.setMaxWidth(dp(240));
        card.addView(holiday, llp(-2, -2, dp(6), dp(8), 0, 0));
        card.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        overlay.addView(card, lp(-2, -2, Gravity.BOTTOM | Gravity.END, 0, 0, dp(60), dp(46)));
        return root;
    }

    private GradientDrawable panel() {
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(22));
        g.setColor(0x66140E0A);
        g.setStroke(dp(1), 0x1FF4EADF);
        return g;
    }

    private static SpannableString month(Calendar now) {
        String m = new SimpleDateFormat("LLLL", RU).format(now.getTime()).toUpperCase(RU);
        SpannableString s = new SpannableString(m + "  " + now.get(Calendar.YEAR));
        s.setSpan(new ForegroundColorSpan(DIM), m.length(), s.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return s;
    }

    // ---------------------------------------------------------------- weather

    private void showWeather() {
        Weather w = Weather.load(this);
        weatherRow.setVisibility(w == null ? View.GONE : View.VISIBLE);
        forecast.setVisibility(w == null || w.days.length < 2 ? View.GONE : View.VISIBLE);
        if (w == null) return;
        nowIcon.set(w.code, isDay());
        temp.setText(Weather.degrees(w.temp));
        city.setText(w.city.isEmpty() ? Weather.describe(w.code) : w.city);
        StringBuilder d = new StringBuilder(w.city.isEmpty() ? "" : Weather.describe(w.code));
        if (w.wind >= 0) d.append(d.length() > 0 ? "  ·  " : "").append("ветер ").append(w.wind).append(" м/с");
        if (w.humidity >= 0) d.append(d.length() > 0 ? "  ·  " : "").append("влажность ").append(w.humidity).append('%');
        details.setText(d);

        forecast.removeAllViews();
        Calendar day = Calendar.getInstance();
        for (int i = 0; i < Math.min(4, w.days.length); i++) {
            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setGravity(Gravity.CENTER_HORIZONTAL);
            col.setMinimumWidth(dp(64));
            TextView label = text(11, "sans-serif-medium", DIM);
            label.setLetterSpacing(0.16f);
            label.setText(i == 0 ? "СЕГОДНЯ" : i == 1 ? "ЗАВТРА"
                    : new SimpleDateFormat("EE", RU).format(day.getTime()).toUpperCase(RU));
            label.setSingleLine(true);
            col.addView(label, llp(-2, -2, 0, 0, 0, 0)); // a vertical LinearLayout's default is match_parent
            Icon icon = new Icon(this);
            icon.set(w.days[i][0], true);
            col.addView(icon, llp(dp(30), dp(30), 0, dp(6), 0, dp(4)));
            String hi = Weather.degrees(w.days[i][1]), lo = Weather.degrees(w.days[i][2]);
            SpannableString range = new SpannableString(hi + "  " + lo);
            range.setSpan(new ForegroundColorSpan(DIM), hi.length(), range.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView t = text(15, "sans-serif", CREAM);
            t.setText(range);
            t.setSingleLine(true);
            col.addView(t, llp(-2, -2, 0, 0, 0, 0));
            forecast.addView(col, llp(-2, -2, dp(12), 0, dp(12), 0));
            day.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    private boolean isDay() {
        Weather w = Weather.load(this);
        return Scene.of(w, System.currentTimeMillis()).elev > -1;
    }

    // ---------------------------------------------------------------- scene

    /** Repaint the landscape off the main thread when the sun or the weather has moved on enough. */
    private void updateScene() {
        Weather w = Weather.load(this);
        Scene s = Scene.of(w, System.currentTimeMillis());
        precip.set(s.kind, w == null ? 0 : w.code);
        if (w != null) nowIcon.set(w.code, s.elev > -1);
        if (painting || s.key().equals(sceneKey)) return;
        painting = true;
        DisplayMetrics m = getResources().getDisplayMetrics();
        int width = root.getWidth() > 0 ? root.getWidth() : m.widthPixels;
        int height = root.getHeight() > 0 ? root.getHeight() : m.heightPixels;
        String key = s.key();
        new Thread(() -> {
            long t0 = SystemClock.elapsedRealtime();
            Bitmap bm = s.paint(width, height);
            android.util.Log.i("tvhome", "scene " + key + " painted in " + (SystemClock.elapsedRealtime() - t0) + " ms");
            handler.post(() -> { painting = false; sceneKey = key; showScene(bm); });
        }).start();
    }

    private void showScene(Bitmap bm) {
        ImageView old = sceneFront;
        sceneFront = sceneBack;
        sceneBack = old;
        root.removeView(sceneFront);
        root.addView(sceneFront, 1, new FrameLayout.LayoutParams(-1, -1));
        sceneFront.setImageBitmap(bm);
        sceneFront.setAlpha(0f);
        sceneFront.animate().alpha(1f).setDuration(firstScene ? 1200 : 4000).withEndAction(() -> {
            sceneBack.setImageDrawable(null);
            root.setBackground(null); // the picture covers the screen
        }).start();
        firstScene = false;
    }

    /** Nudge the text a little every minute against burn-in. */
    private void drift() {
        overlay.animate().translationX((rnd.nextFloat() * 2 - 1) * dp(18)).translationY((rnd.nextFloat() * 2 - 1) * dp(10))
                .setDuration(4000).start();
        handler.postDelayed(drift, 60 * 1000);
    }

    // ---------------------------------------------------------------- helpers

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

    // ---------------------------------------------------------------- views

    /** Falling snow or rain over the landscape: a few hundred points or lines, one draw call per size. */
    static final class Precipitation extends View {
        private int kind = -1, count;
        private float[] x = new float[0], y, speed, size, phase, buf;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;
        private long last;
        private final Random r = new Random();

        Precipitation(Context c) {
            super(c);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(int kind, int code) {
            int k = kind == Scene.STORM ? Scene.RAIN : kind;
            int n = k == Scene.SNOW ? (code == 75 || code == 86 ? 230 : code == 73 ? 150 : 100)
                    : k == Scene.RAIN ? (code >= 51 && code <= 57 ? 90 : code == 65 || code == 82 || kind == Scene.STORM ? 220 : 150)
                    : 0;
            if (k == this.kind && n == count) return;
            this.kind = k;
            count = n;
            x = new float[n]; y = new float[n]; speed = new float[n]; size = new float[n]; phase = new float[n];
            buf = new float[n * 4];
            for (int i = 0; i < n; i++) spawn(i, true);
            last = 0;
            invalidate();
        }

        void start() { running = true; last = 0; invalidate(); }

        void stop() { running = false; }

        private void spawn(int i, boolean anywhere) {
            int w = Math.max(1, getWidth() > 0 ? getWidth() : 1920), h = Math.max(1, getHeight() > 0 ? getHeight() : 1080);
            float s = w / 1920f;
            size[i] = r.nextFloat();
            x[i] = r.nextFloat() * w * 1.2f - w * 0.1f;
            y[i] = anywhere ? r.nextFloat() * h : -r.nextFloat() * h * 0.1f - 40;
            phase[i] = r.nextFloat() * 6.283f;
            speed[i] = kind == Scene.SNOW ? (35 + 70 * size[i]) * s : (900 + 500 * size[i]) * s;
        }

        @Override public boolean hasOverlappingRendering() { return false; }

        @Override
        protected void onDraw(Canvas c) {
            if (count == 0) return;
            long now = SystemClock.uptimeMillis();
            float dt = last == 0 ? 0 : Math.min(0.05f, (now - last) / 1000f);
            last = now;
            int w = getWidth(), h = getHeight();
            float s = w / 1920f, t = now / 1000f;
            if (kind == Scene.SNOW) {
                for (int i = 0; i < count; i++) {
                    y[i] += speed[i] * dt;
                    x[i] += (float) Math.sin(t * 0.7f + phase[i]) * 18 * s * dt;
                    if (y[i] > h + 10) spawn(i, false);
                }
                // Three sizes: small and faint far away, big and bright up close.
                for (int band = 0; band < 3; band++) {
                    int n = 0;
                    for (int i = 0; i < count; i++) {
                        if ((int) (size[i] * 2.999f) != band) continue;
                        buf[n++] = x[i] + (float) Math.sin(t * 0.9f + phase[i]) * 10 * s;
                        buf[n++] = y[i];
                    }
                    paint.setColor(Scene.alpha(CREAM, band == 0 ? 0.45f : band == 1 ? 0.65f : 0.8f));
                    paint.setStrokeWidth((band == 0 ? 3f : band == 1 ? 5f : 7.5f) * s);
                    c.drawPoints(buf, 0, n, paint);
                }
            } else {
                int n = 0;
                for (int i = 0; i < count; i++) {
                    y[i] += speed[i] * dt;
                    x[i] += speed[i] * 0.12f * dt;
                    if (y[i] > h + 40) spawn(i, false);
                    float len = (22 + 22 * size[i]) * s;
                    buf[n++] = x[i]; buf[n++] = y[i]; buf[n++] = x[i] - len * 0.12f; buf[n++] = y[i] - len;
                }
                paint.setColor(0x55E8DCCF);
                paint.setStrokeWidth(1.6f * s);
                c.drawLines(buf, 0, n, paint);
            }
            if (running) postInvalidateOnAnimation();
        }
    }

    /** Weather glyph in the palette: amber sun, pale-gold moon, cream cloud, drops, flakes, bolt. */
    static final class Icon extends View {
        private int code = -1;
        private boolean day = true;
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), line = new Paint(Paint.ANTI_ALIAS_FLAG);

        Icon(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            line.setStrokeCap(Paint.Cap.ROUND);
        }

        void set(int code, boolean day) {
            if (code == this.code && day == this.day) return;
            this.code = code;
            this.day = day;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f;
            line.setStrokeWidth(s * 0.07f);
            int k = Scene.kind(code);
            if (k == Scene.CLEAR) { body(c, cx, cy, s * 0.3f); return; }
            if (k == Scene.CLOUDS) {
                body(c, cx - s * 0.14f, cy - s * 0.14f, s * 0.22f);
                cloud(c, cx + s * 0.06f, cy + s * 0.06f, s * 0.8f);
                return;
            }
            if (k == Scene.FOG) {
                line.setColor(CREAM);
                for (int i = 0; i < 3; i++) {
                    float yy = cy - s * 0.2f + i * s * 0.2f, off = (i % 2) * s * 0.08f;
                    c.drawLine(cx - s * 0.36f + off, yy, cx + s * 0.32f + off, yy, line);
                }
                return;
            }
            boolean falls = k != Scene.OVERCAST;
            cloud(c, cx, falls ? cy - s * 0.12f : cy, s);
            float y0 = cy + s * 0.2f;
            if (k == Scene.RAIN) {
                line.setColor(0xCCF4EADF);
                for (int i = -1; i <= 1; i++) {
                    float xx = cx + i * s * 0.17f, yy = y0 + (i == 0 ? s * 0.05f : 0);
                    c.drawLine(xx, yy, xx - s * 0.05f, yy + s * 0.15f, line);
                }
            } else if (k == Scene.SNOW) {
                fill.setColor(CREAM);
                for (int i = -1; i <= 1; i++) c.drawCircle(cx + i * s * 0.17f, y0 + s * (i == 0 ? 0.13f : 0.06f), s * 0.045f, fill);
            } else if (k == Scene.STORM) {
                Path bolt = new Path();
                bolt.moveTo(cx + s * 0.04f, y0 - s * 0.04f);
                bolt.lineTo(cx - s * 0.1f, y0 + s * 0.14f);
                bolt.lineTo(cx, y0 + s * 0.14f);
                bolt.lineTo(cx - s * 0.06f, y0 + s * 0.3f);
                bolt.lineTo(cx + s * 0.12f, y0 + s * 0.08f);
                bolt.lineTo(cx + s * 0.02f, y0 + s * 0.08f);
                bolt.close();
                fill.setColor(AMBER);
                c.drawPath(bolt, fill);
            }
        }

        private void body(Canvas c, float x, float y, float r) {
            if (day) {
                fill.setColor(AMBER);
                c.drawCircle(x, y, r * 0.58f, fill);
                line.setColor(AMBER);
                for (int i = 0; i < 8; i++) {
                    double a = i * Math.PI / 4;
                    c.drawLine(x + (float) Math.cos(a) * r * 0.82f, y + (float) Math.sin(a) * r * 0.82f,
                            x + (float) Math.cos(a) * r * 1.12f, y + (float) Math.sin(a) * r * 1.12f, line);
                }
            } else {
                Path moon = new Path(), bite = new Path();
                moon.addCircle(x, y, r * 0.8f, Path.Direction.CW);
                bite.addCircle(x + r * 0.42f, y - r * 0.28f, r * 0.68f, Path.Direction.CW);
                moon.op(bite, Path.Op.DIFFERENCE);
                fill.setColor(0xFFEBD3A8);
                c.drawPath(moon, fill);
            }
        }

        private void cloud(Canvas c, float x, float y, float s) {
            fill.setColor(0xFFE9DED1);
            c.drawCircle(x - s * 0.17f, y + s * 0.03f, s * 0.15f, fill);
            c.drawCircle(x + s * 0.03f, y - s * 0.05f, s * 0.2f, fill);
            c.drawCircle(x + s * 0.2f, y + s * 0.05f, s * 0.13f, fill);
            c.drawRoundRect(x - s * 0.32f, y + s * 0.02f, x + s * 0.33f, y + s * 0.18f, s * 0.08f, s * 0.08f, fill);
        }
    }

    /** This month, Monday first: weekends and public holidays in terracotta, today in an amber circle. */
    static final class MonthView extends View {
        private static final String[] HEAD = {"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};
        // Russian public holidays: {month 1-12, day, name}.
        private static final Object[][] HOLIDAYS = {
                {1, 1, "Новый год"}, {1, 2, null}, {1, 3, null}, {1, 4, null}, {1, 5, null}, {1, 6, null},
                {1, 7, "Рождество"}, {1, 8, null}, {2, 23, "День защитника Отечества"},
                {3, 8, "Международный женский день"}, {5, 1, "Праздник Весны и Труда"}, {5, 9, "День Победы"},
                {6, 12, "День России"}, {11, 4, "День народного единства"},
        };
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        private final float cw, ch, head;
        private Calendar today = Calendar.getInstance();

        MonthView(Context c) {
            super(c);
            float d = c.getResources().getDisplayMetrics().density;
            cw = 36 * d;
            ch = 29 * d;
            head = 24 * d;
            p.setTextAlign(Paint.Align.CENTER);
        }

        void setToday(Calendar now) {
            boolean changed = now.get(Calendar.DAY_OF_YEAR) != today.get(Calendar.DAY_OF_YEAR)
                    || now.get(Calendar.YEAR) != today.get(Calendar.YEAR);
            today = (Calendar) now.clone();
            if (changed) { requestLayout(); invalidate(); }
        }

        private int offset() {
            Calendar first = (Calendar) today.clone();
            first.set(Calendar.DAY_OF_MONTH, 1);
            return (first.get(Calendar.DAY_OF_WEEK) + 5) % 7; // Monday = 0
        }

        private int rows() {
            return (offset() + today.getActualMaximum(Calendar.DAY_OF_MONTH) + 6) / 7;
        }

        static boolean holiday(int month, int day) {
            for (Object[] h : HOLIDAYS) if ((int) h[0] == month && (int) h[1] == day) return true;
            return false;
        }

        /** "4 ноября · День народного единства": the next named holiday this month, today included. */
        static String nextHoliday(Calendar now) {
            int m = now.get(Calendar.MONTH) + 1, d = now.get(Calendar.DAY_OF_MONTH);
            for (Object[] h : HOLIDAYS) {
                if ((int) h[0] == m && (int) h[1] >= d && h[2] != null) {
                    Calendar c = (Calendar) now.clone();
                    c.set(Calendar.DAY_OF_MONTH, (int) h[1]);
                    String when = (int) h[1] == d ? "Сегодня" : new SimpleDateFormat("d MMMM", RU).format(c.getTime());
                    return when + "  ·  " + h[2];
                }
            }
            return null;
        }

        @Override
        protected void onMeasure(int w, int h) {
            setMeasuredDimension(Math.round(cw * 7), Math.round(head + ch * rows()));
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().scaledDensity;
            p.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            p.setTextSize(11 * d);
            p.setLetterSpacing(0.1f);
            for (int i = 0; i < 7; i++) {
                p.setColor(i >= 5 ? Scene.alpha(TERRA, 0.75f) : 0x80F4EADF);
                c.drawText(HEAD[i], cw * i + cw / 2, head / 2 - (p.descent() + p.ascent()) / 2, p);
            }
            p.setLetterSpacing(0);
            p.setTextSize(15 * d);
            int off = offset(), days = today.getActualMaximum(Calendar.DAY_OF_MONTH);
            int now = today.get(Calendar.DAY_OF_MONTH), month = today.get(Calendar.MONTH) + 1;
            for (int day = 1; day <= days; day++) {
                int col = (off + day - 1) % 7, row = (off + day - 1) / 7;
                float x = cw * col + cw / 2, y = head + ch * row + ch / 2;
                float base = y - (p.descent() + p.ascent()) / 2;
                if (day == now) {
                    p.setColor(AMBER);
                    c.drawCircle(x, y, Math.min(cw, ch) * 0.47f, p);
                    p.setColor(0xFF2A1E16);
                    p.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
                    c.drawText(String.valueOf(day), x, base, p);
                    p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                    continue;
                }
                p.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
                int color = col >= 5 || holiday(month, day) ? TERRA : CREAM;
                p.setColor(day < now ? Scene.alpha(color, 0.38f) : color);
                c.drawText(String.valueOf(day), x, base, p);
            }
        }
    }
}
