package com.mihir.tvhome;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;

/** "Показать сейчас" in the launcher's screensaver menu: the screensaver until any key is pressed. */
public class ScreensaverActivity extends Activity {
    private Screensaver saver;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        saver = new Screensaver(this);
        setContentView(saver.build());
    }

    @Override
    protected void onResume() {
        super.onResume();
        saver.start();
    }

    @Override
    protected void onPause() {
        saver.stop();
        super.onPause();
        finish(); // like the real one: gone once anything else comes up
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
