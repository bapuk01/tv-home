package com.mihir.tvhome;

import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;

import java.util.Calendar;
import java.util.Random;
import java.util.TimeZone;

/**
 * The screensaver's painted landscape: sky, sun or moon, mountain ridges and forest in muted warm
 * colours. The palette follows the season, the sun's real height over the TV's location and the
 * weather. Painted once into a bitmap; the screensaver draws falling snow and rain on top.
 */
final class Scene {
    static final int CLEAR = 0, CLOUDS = 1, OVERCAST = 2, FOG = 3, RAIN = 4, SNOW = 5, STORM = 6;
    static final int WINTER = 0, SPRING = 1, SUMMER = 2, AUTUMN = 3;

    final int kind, season;
    final boolean snowy;
    final double elev, hourAngle, noonElev, moonPhase; // degrees; phase 0 new .. 0.5 full .. 1
    final long seed;

    /** WMO weather code to the kind of sky painted for it. */
    static int kind(int code) {
        if (code <= 0) return CLEAR; // also "no reading yet"
        if (code <= 2) return CLOUDS;
        if (code == 3) return OVERCAST;
        if (code == 45 || code == 48) return FOG;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return SNOW;
        if (code >= 95) return STORM;
        return RAIN; // drizzle, rain, sleet, showers
    }

    Scene(int kind, int season, boolean snowy, double elev, double hourAngle, double noonElev, double moonPhase, long seed) {
        this.kind = kind;
        this.season = season;
        this.snowy = snowy;
        this.elev = elev;
        this.hourAngle = hourAngle;
        this.noonElev = noonElev;
        this.moonPhase = moonPhase;
        this.seed = seed;
    }

    /** The scene for the last weather reading (or none) at this moment. */
    static Scene of(Weather w, long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        boolean located = w != null && (w.lat != 0 || w.lon != 0);
        double lat = located ? w.lat : 50;
        double lon = located ? w.lon : TimeZone.getDefault().getOffset(now) / 3600000.0 * 15;
        int kind = kind(w == null ? -1 : w.code);
        int m = c.get(Calendar.MONTH);
        if (lat < 0) m = (m + 6) % 12;
        int season = m == 11 || m <= 1 ? WINTER : m <= 4 ? SPRING : m <= 7 ? SUMMER : AUTUMN;
        // Snow on the ground in winter, while it snows, or once it's properly cold (Irkutsk in November).
        boolean snowy = (season == WINTER && (w == null || w.temp < 8)) || kind == SNOW || (w != null && w.temp <= -3);
        double[] sun = sun(lat, lon, now);
        double moon = (((now - 947182440000L) / 86400000.0 / 29.530588853) % 1 + 1) % 1; // from the new moon of 6 Jan 2000
        long seed = c.get(Calendar.YEAR) * 1000L + c.get(Calendar.DAY_OF_YEAR); // a new landscape every day
        return new Scene(kind, season, snowy, sun[0], sun[1], sun[2], moon, seed);
    }

    /** Changes when the picture would visibly change: the sun moves a few degrees, the weather turns. */
    String key() {
        return season + "," + snowy + "," + kind + "," + Math.round(elev / 2) + "," + Math.round(hourAngle / 8) + "," + seed;
    }

    /** {elevation, hour angle (negative before noon), elevation at noon}, degrees. Low-precision solar position. */
    static double[] sun(double lat, double lon, long ms) {
        double d = ms / 86400000.0 - 10957.5; // days since J2000
        double g = Math.toRadians(357.529 + 0.98560028 * d);
        double q = 280.459 + 0.98564736 * d;
        double l = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g));
        double e = Math.toRadians(23.439 - 0.00000036 * d);
        double ra = Math.atan2(Math.cos(e) * Math.sin(l), Math.cos(l));
        double dec = Math.asin(Math.sin(e) * Math.sin(l));
        double gmst = 18.697374558 + 24.06570982441908 * d; // hours
        double ha = Math.toRadians(gmst * 15 + lon) - ra;
        ha = Math.atan2(Math.sin(ha), Math.cos(ha));
        double phi = Math.toRadians(lat);
        double el = Math.asin(Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(ha));
        return new double[]{Math.toDegrees(el), Math.toDegrees(ha), 90 - Math.abs(lat - Math.toDegrees(dec))};
    }

    // ---------------------------------------------------------------- palette

    // Sky {top, middle, horizon} by sun elevation: night, twilight, sunrise/sunset, low sun, day.
    private static final double[] SKY_AT = {-12, -5, 0, 8, 25};
    private static final int[][] SKY = {
            {0xFF1A1720, 0xFF26212B, 0xFF3A3036},
            {0xFF221F31, 0xFF4E3746, 0xFF8E5B4C},
            {0xFF35344C, 0xFF9A5E55, 0xFFE09A6E},
            {0xFF55607A, 0xFFB98C76, 0xFFEDC394},
            {0xFF7D8E9E, 0xFFBFB6A6, 0xFFEAD7B8},
    };
    // Light falling on the land, {r, g, b} multipliers, same elevations.
    private static final float[][] LIGHT = {
            {0.40f, 0.37f, 0.42f}, {0.48f, 0.41f, 0.44f}, {0.70f, 0.52f, 0.46f}, {0.95f, 0.80f, 0.68f}, {1f, 0.95f, 0.88f},
    };
    // Ridges far to near, then pines, snow on branches, leafy trees.
    private static final int[][] LAND = {
            {0xFFD6CDCB, 0xFFC3B9B9, 0xFFAEA4A6, 0xFF9A9093, 0xFF8C8285}, // snow
            {0xFF8C8794, 0xFF8A8F84, 0xFF8C9A72, 0xFF65744A, 0xFF3E4A2E}, // spring
            {0xFF8A8494, 0xFF7E8470, 0xFF7A8754, 0xFF55633A, 0xFF353F24}, // summer
            {0xFF8E8088, 0xFF9C8370, 0xFFAE7E4E, 0xFF87532F, 0xFF4A3022}, // autumn
    };
    private static final int[] PINE = {0xFF2A2729, 0xFF263024, 0xFF20291A, 0xFF2C2A20};
    private static final int[][] LEAVES = {
            {},
            {0xFF7D9450, 0xFF93A564, 0xFFC9AE9C},
            {0xFF5E6E36, 0xFF6F7A3C, 0xFF4E5E30},
            {0xFFA35A2E, 0xFFB98A3E, 0xFF8C4A2A, 0xFFC0703A},
    };
    private static final float[] BASE = {0.50f, 0.565f, 0.645f, 0.735f, 0.86f};
    private static final float[] AMP = {0.16f, 0.11f, 0.055f, 0.04f, 0.03f};

    private int[] sky;
    private float[] light;
    private float[] haze;
    private boolean heavy;
    private float glow;

    // ---------------------------------------------------------------- painting

    Bitmap paint(int w, int h) {
        Bitmap bm = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bm);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        Random r = new Random(seed * 31 + 1);
        heavy = kind == OVERCAST || kind == RAIN || kind == SNOW || kind == STORM;
        float dull = kind == CLEAR ? 0 : kind == CLOUDS ? 0.2f : kind == FOG ? 0.6f : 0.5f;
        float dim = kind == STORM ? 0.7f : heavy || kind == FOG ? 0.85f : 1f;
        float t = stop(elev);
        int i0 = (int) t, i1 = Math.min(i0 + 1, SKY.length - 1);
        float f = t - i0;
        sky = new int[3];
        for (int k = 0; k < 3; k++) sky[k] = scale(dull(mix(SKY[i0][k], SKY[i1][k], f), dull), dim);
        // Under low cloud at night the sky isn't black but faintly warm with the town's light.
        glow = heavy || kind == FOG ? clamp((float) ((-elev - 2) / 8), 0, 1) : 0;
        int[] town = {0xFF2C2427, 0xFF3A2F2D, 0xFF4C3D36};
        for (int k = 0; k < 3; k++) sky[k] = mix(sky[k], town[k], glow * 0.75f);
        light = new float[3];
        for (int k = 0; k < 3; k++) light[k] = (LIGHT[i0][k] + (LIGHT[i1][k] - LIGHT[i0][k]) * f) * dim;
        haze = kind == FOG ? new float[]{0.88f, 0.76f, 0.6f, 0.42f, 0.18f}
                : heavy ? new float[]{0.7f, 0.55f, 0.38f, 0.22f, 0.06f}
                : new float[]{0.6f, 0.44f, 0.28f, 0.14f, 0.03f};
        // ...and that light picks the land out of the dark instead of hiding it in haze.
        for (int k = 0; k < 3; k++) light[k] += glow * 0.12f;
        for (int k = 0; k < haze.length; k++) haze[k] *= 1 - glow * 0.35f;

        // Sky.
        float horizon = h * 0.55f;
        p.setShader(new LinearGradient(0, 0, 0, horizon, sky, new float[]{0, 0.55f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h, p);

        float low = clamp((float) (1 - elev / 15), 0, 1);
        float sunX = w * (0.5f + 0.4f * clamp((float) (hourAngle / 100), -1, 1));
        float sunY = horizon - (float) (elev / Math.max(noonElev, 10)) * (horizon - h * 0.10f);
        // Warm glow along the horizon around sunrise and sunset.
        if (elev > -10 && elev < 10) {
            float a = (float) (1 - Math.abs(elev) / 10) * (heavy ? 0.18f : 0.45f);
            p.setShader(new RadialGradient(sunX, horizon, w * 0.55f, alpha(0xFFF0B07A, a), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
        }
        p.setShader(null);

        float night = clamp((float) ((-elev - 6) / 6), 0, 1);
        if (night > 0 && (kind == CLEAR || kind == CLOUDS)) {
            p.setColor(0xFFF6EBDA);
            float s = w / 1920f;
            for (int i = 0; i < 160; i++) {
                float x = r.nextFloat() * w, y = r.nextFloat() * r.nextFloat() * h * 0.55f;
                p.setAlpha((int) (night * (40 + r.nextInt(150))));
                c.drawCircle(x, y, (0.6f + r.nextFloat() * 1.4f) * s, p);
            }
            p.setAlpha(255);
        }
        if (elev < -3 && !heavy && kind != FOG) moon(c, w * 0.78f, h * 0.19f, h * 0.034f, clamp((float) (-elev - 3) / 4, 0, 1));
        if (elev > -4) {
            boolean seen = !heavy && kind != FOG;
            int disc = mix(0xFFFBF0DA, 0xFFF3A968, low);
            p.setShader(new RadialGradient(sunX, sunY, h * (seen ? 0.5f : 0.7f),
                    alpha(mix(0xFFFFF0D6, 0xFFF2A46A, low), seen ? 0.32f : 0.12f), 0, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h, p);
            p.setShader(null);
            if (seen) {
                p.setColor(disc);
                c.drawCircle(sunX, sunY, h * 0.042f, p);
            }
        }
        if (kind != CLEAR) clouds(c, w, h, low, r);

        // Land, far to near, with mist settling in the valleys between ridges.
        int s = snowy ? 0 : season;
        Random rr = new Random(seed), tr = new Random(seed + 7);
        for (int i = 0; i < 5; i++) {
            float[] y = ridge(i, w, h, rr);
            Path path = new Path();
            path.moveTo(0, h);
            float top = h;
            for (int k = 0; k < y.length; k++) { path.lineTo(k * 4, y[k]); top = Math.min(top, y[k]); }
            path.lineTo(w, h);
            path.close();
            int col = land(LAND[s][i], i);
            p.setShader(new LinearGradient(0, top, 0, h * (BASE[i] + 0.08f), col, mix(col, Color.BLACK, 0.18f), Shader.TileMode.CLAMP));
            c.drawPath(path, p);
            p.setShader(null);
            if (i < 2 && (snowy || season != SUMMER || i == 0)) caps(c, path, w, h, i);
            if (i >= 2) trees(c, y, w, h, i, s, tr);
            if (i < 4) {
                float a = kind == FOG ? 0.6f : heavy ? 0.35f : 0.2f;
                float y0 = h * (BASE[i] - AMP[i] * 0.6f), y1 = h * (BASE[i] + 0.02f), y2 = y1 + h * 0.06f;
                p.setShader(new LinearGradient(0, y0, 0, y2, new int[]{alpha(sky[2], 0), alpha(sky[2], a), alpha(sky[2], 0)},
                        new float[]{0, (y1 - y0) / (y2 - y0), 1}, Shader.TileMode.CLAMP));
                c.drawRect(0, y0, w, y2, p);
                p.setShader(null);
            }
        }
        if (kind == FOG) {
            p.setShader(new LinearGradient(0, h * 0.35f, 0, h, new int[]{alpha(sky[2], 0), alpha(sky[2], 0.35f), alpha(sky[2], 0.1f)},
                    null, Shader.TileMode.CLAMP));
            c.drawRect(0, h * 0.35f, w, h, p);
        }

        // Scrim for the clock, weather and calendar along the bottom; a touch at the top.
        p.setShader(new LinearGradient(0, h * 0.42f, 0, h, new int[]{0, 0x99120C08, 0xD9120C08},
                new float[]{0, 0.65f, 1}, Shader.TileMode.CLAMP));
        c.drawRect(0, h * 0.42f, w, h, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.25f, 0x40120C08, 0, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, w, h * 0.25f, p);
        return bm;
    }

    /** One ridge line, a y per 4 px: sharp peaks far away, rolling hills near. */
    private static float[] ridge(int i, int w, int h, Random r) {
        int n = w / 4 + 2;
        float[] y = new float[n];
        boolean peaks = i < 2;
        double f1 = peaks ? 1.3 + r.nextDouble() * 1.0 : 0.8 + r.nextDouble() * 0.8;
        double f2 = peaks ? f1 * (2.3 + r.nextDouble() * 0.6) : 2 + r.nextDouble() * 2;
        double f3 = 11 + r.nextDouble() * 8, f4 = 27 + r.nextDouble() * 20, f5 = 0.5 + r.nextDouble() * 0.5;
        double p1 = r.nextDouble() * 9, p2 = r.nextDouble() * 9, p3 = r.nextDouble() * 9, p4 = r.nextDouble() * 9;
        double p5 = r.nextDouble() * 9;
        for (int k = 0; k < n; k++) {
            double t = k * 4.0 / w, v;
            if (peaks) {
                // A slow swell over the range so no two peaks stand equally tall.
                double swell = 0.55 + 0.45 * Math.sin(6.283 * f5 * t + p5);
                v = swell * (0.62 * ridged(f1 * t + p1) + 0.28 * ridged(f2 * t + p2)) + 0.1 * ridged(f2 * 1.7 * t + p4)
                        + 0.05 * Math.sin(6.283 * f3 * t + p3) + 0.02 * Math.sin(6.283 * f4 * t + p4);
            } else {
                v = 0.5 + 0.5 * (0.62 * Math.sin(6.283 * f1 * t + p1) + 0.3 * Math.sin(6.283 * f2 * t + p2)
                        + 0.08 * Math.sin(6.283 * f3 * t + p3));
            }
            y[k] = (float) (h * (BASE[i] - AMP[i] * v));
        }
        return y;
    }

    private static double ridged(double x) {
        return 1 - Math.abs(Math.sin(Math.PI * x));
    }

    /** Snow above the snow line of a mountain ridge. */
    private void caps(Canvas c, Path ridge, int w, int h, int i) {
        float line = h * (BASE[i] - AMP[i] * (snowy ? 0.45f : season == SUMMER ? 0.85f : 0.68f));
        Path cap = new Path();
        cap.moveTo(0, 0);
        for (int x = 0; x <= w; x += 8) {
            cap.lineTo(x, line + (float) (Math.sin(x * 0.02 + i) * h * 0.008 + Math.sin(x * 0.071) * h * 0.004));
        }
        cap.lineTo(w, 0);
        cap.close();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(mix(lit(0xFFEDE5DE), sky[2], haze[i]));
        c.save();
        c.clipPath(ridge);
        c.drawPath(cap, p);
        c.restore();
    }

    /** Forest along a ridge: tiny far away, tall pines in front; leafy trees outside winter. */
    private void trees(Canvas c, float[] y, int w, int h, int i, int s, Random r) {
        float minH = i == 2 ? 0.010f : i == 3 ? 0.02f : 0.05f, maxH = i == 2 ? 0.02f : i == 3 ? 0.042f : 0.12f;
        float gapMin = i == 2 ? 0.002f : i == 3 ? 0.004f : 0.008f, gapMax = i == 2 ? 0.006f : i == 3 ? 0.009f : 0.025f;
        Path pines = new Path(), snow = new Path();
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] leaves = LEAVES[s];
        for (float x = -r.nextFloat() * w * gapMax; x < w + h * maxH; x += w * (gapMin + r.nextFloat() * (gapMax - gapMin))) {
            float th = h * (minH + r.nextFloat() * (maxH - minH));
            boolean leafy = leaves.length > 0 && r.nextFloat() < 0.3f;
            int leaf = leafy ? leaves[r.nextInt(leaves.length)] : 0;
            if (i == 2 && Math.sin(x * 0.004 + seed) > 0.3) continue; // meadows between the far woods
            float base = y[Math.max(0, Math.min(y.length - 1, (int) (x / 4)))] + th * 0.08f;
            if (leafy) {
                p.setColor(land(leaf, i));
                c.drawRect(x - th * 0.03f, base - th * 0.35f, x + th * 0.03f, base, p);
                c.drawOval(x - th * 0.26f, base - th * 0.85f, x + th * 0.26f, base - th * 0.25f, p);
                continue;
            }
            float half0 = th * 0.2f;
            for (int k = 0; k < 4; k++) {
                float tt = base - th + k * th * 0.2f, bot = tt + th * 0.4f, half = half0 * (0.5f + 0.25f * k);
                pines.moveTo(x, tt);
                pines.lineTo(x + half, bot);
                pines.lineTo(x - half, bot);
                pines.close();
                if (snowy && i >= 3) {
                    snow.moveTo(x, tt);
                    snow.lineTo(x + half * 0.5f, tt + th * 0.2f);
                    snow.lineTo(x - half * 0.5f, tt + th * 0.2f);
                    snow.close();
                }
            }
            pines.addRect(x - th * 0.025f, base - th * 0.2f, x + th * 0.025f, base, Path.Direction.CW);
        }
        p.setColor(land(mix(PINE[s], LAND[s][i], 0.15f), i));
        c.drawPath(pines, p);
        if (snowy && i >= 3) {
            p.setColor(alpha(land(0xFFE2D9D4, i), 0.6f));
            c.drawPath(snow, p);
        }
    }

    /** Clouds: a few soft banks on a fair day, a low ceiling when it's grey, raining or snowing. */
    private void clouds(Canvas c, int w, int h, float low, Random r) {
        int base = kind == STORM ? 0xFF7A716C : heavy ? 0xFFA79E96 : 0xFFF1E6D8;
        float[] lift = {Math.min(1, light[0] + 0.12f), Math.min(1, light[1] + 0.12f), Math.min(1, light[2] + 0.12f)};
        int col = mix(mul(base, lift), sky[2], low * 0.35f);
        col = mix(col, 0xFF4E4039, glow * 0.6f); // grey nights: cloud lit from below by the town
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        if (heavy || kind == FOG) {
            p.setShader(new LinearGradient(0, 0, 0, h * 0.5f, new int[]{alpha(col, 0.95f), alpha(col, 0.6f), 0},
                    new float[]{0, 0.6f, 1}, Shader.TileMode.CLAMP));
            c.drawRect(0, 0, w, h * 0.5f, p);
            p.setShader(null);
        }
        p.setMaskFilter(new BlurMaskFilter(w * 0.008f, BlurMaskFilter.Blur.NORMAL));
        int groups = heavy ? 9 : 5;
        for (int g = 0; g < groups; g++) {
            float gx = r.nextFloat() * w * 1.1f - w * 0.05f, gy = h * (0.06f + r.nextFloat() * 0.3f);
            float gw = w * (0.10f + r.nextFloat() * 0.16f);
            p.setColor(alpha(heavy ? scale(col, 0.85f + r.nextFloat() * 0.15f) : col, 0.55f + r.nextFloat() * 0.3f));
            // Rounded puffs on top, the bank's flat base along the bottom.
            int puffs = 10 + r.nextInt(7);
            for (int k = 0; k < puffs; k++) {
                float u = r.nextFloat() - 0.5f;
                float rx = gw * (0.08f + r.nextFloat() * 0.14f) * (1 - Math.abs(u)), ry = rx * (0.55f + r.nextFloat() * 0.3f);
                float ex = gx + u * gw, ey = gy - ry * 0.5f + r.nextFloat() * gw * 0.02f;
                c.drawOval(ex - rx, ey - ry, ex + rx, gy + gw * 0.012f, p);
            }
        }
    }

    /** The moon in its current phase, with a faint glow. */
    private void moon(Canvas c, float x, float y, float rad, float a) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        p.setShader(new RadialGradient(x, y, rad * 7, alpha(0xFFF0DDBA, 0.16f * a), 0, Shader.TileMode.CLAMP));
        c.drawCircle(x, y, rad * 7, p);
        p.setShader(null);
        p.setColor(alpha(0xFFEBD3A8, 0.12f * a));
        c.drawCircle(x, y, rad, p);
        // Lit part: half the disc plus or minus half an ellipse whose width follows the phase.
        boolean waxing = moonPhase < 0.5;
        float k = (float) Math.cos(2 * Math.PI * moonPhase) * rad; // terminator half-width
        Path lit = new Path();
        lit.addArc(x - rad, y - rad, x + rad, y + rad, -90, waxing ? 180 : -180);
        lit.arcTo(x - Math.abs(k), y - rad, x + Math.abs(k), y + rad, 90, (waxing == (k > 0)) ? -180 : 180, false);
        lit.close();
        p.setColor(alpha(0xFFEBD3A8, a));
        c.drawPath(lit, p);
    }

    // ---------------------------------------------------------------- colour

    private int land(int c, int layer) {
        return mix(lit(c), sky[2], haze[layer]);
    }

    private int lit(int c) {
        return mul(c, light);
    }

    private static float stop(double e) {
        if (e <= SKY_AT[0]) return 0;
        for (int i = 1; i < SKY_AT.length; i++) {
            if (e <= SKY_AT[i]) return (float) (i - 1 + (e - SKY_AT[i - 1]) / (SKY_AT[i] - SKY_AT[i - 1]));
        }
        return SKY_AT.length - 1;
    }

    static int mix(int a, int b, float t) {
        return Color.argb(Math.round(Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t),
                Math.round(Color.red(a) + (Color.red(b) - Color.red(a)) * t),
                Math.round(Color.green(a) + (Color.green(b) - Color.green(a)) * t),
                Math.round(Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t));
    }

    private static int mul(int c, float[] m) {
        return Color.argb(Color.alpha(c), Math.min(255, Math.round(Color.red(c) * m[0])),
                Math.min(255, Math.round(Color.green(c) * m[1])), Math.min(255, Math.round(Color.blue(c) * m[2])));
    }

    private static int scale(int c, float f) {
        return mul(c, new float[]{f, f, f});
    }

    /** Towards a warm grey of the same brightness: what clouds do to the light. */
    private static int dull(int c, float t) {
        float l = 0.3f * Color.red(c) + 0.59f * Color.green(c) + 0.11f * Color.blue(c);
        int g = Color.rgb(Math.min(255, Math.round(l * 1.03f)), Math.round(l * 0.98f), Math.round(l * 0.93f));
        return mix(c, g, t);
    }

    static int alpha(int c, float a) {
        return Color.argb(Math.round(a * 255), Color.red(c), Color.green(c), Color.blue(c));
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
