package com.mihir.tvhome;

import android.service.dreams.DreamService;

/** The system screensaver; the content is Screensaver. */
public class WeatherDream extends DreamService {
    private Screensaver saver;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false); // any key wakes the TV
        setFullscreen(true);
        setScreenBright(true);
        saver = new Screensaver(this);
        setContentView(saver.build());
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        saver.start();
    }

    @Override
    public void onDreamingStopped() {
        saver.stop();
        super.onDreamingStopped();
    }
}
