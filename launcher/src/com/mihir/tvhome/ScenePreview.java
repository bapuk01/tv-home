package com.mihir.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.widget.FrameLayout;
import android.widget.ImageView;

/**
 * Shows the screensaver's landscape for any season, sun height and weather, to check the painting
 * without waiting for them. Not in the app list; start it over ADB:
 *   adb shell am start -n com.mihir.tvhome/.ScenePreview --ei code 71 --ei season 0 --ef elev 20 --ef ha -30
 * code: WMO weather code; season: 0 winter, 1 spring, 2 summer, 3 autumn; elev: sun height, degrees;
 * ha: sun hour angle (negative before noon); moon: phase 0..1; seed: landscape.
 */
public class ScenePreview extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Intent i = getIntent();
        int code = i.getIntExtra("code", 0), season = i.getIntExtra("season", Scene.SUMMER);
        int kind = Scene.kind(code);
        Scene s = new Scene(kind, season, i.getBooleanExtra("snowy", season == Scene.WINTER || kind == Scene.SNOW),
                i.getFloatExtra("elev", 20), i.getFloatExtra("ha", -30), i.getFloatExtra("noon", 40),
                i.getFloatExtra("moon", 0.3f), i.getLongExtra("seed", 2026305));
        FrameLayout root = new FrameLayout(this);
        ImageView v = new ImageView(this);
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        v.setImageBitmap(s.paint(w, h));
        root.addView(v, new FrameLayout.LayoutParams(-1, -1));
        Screensaver.Precipitation p = new Screensaver.Precipitation(this);
        root.addView(p, new FrameLayout.LayoutParams(-1, -1));
        setContentView(root);
        p.set(kind, code);
        p.start();
    }
}
