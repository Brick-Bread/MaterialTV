package app.m3home;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;

/** Preview of the screensaver from M3 Home's settings. Any key closes it. */
public class ScreensaverActivity extends Activity {
    static final String EXTRA_SLEEP = "sleep";
    private ScreensaverView view;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        view = new ScreensaverView(this);
        view.forceSleep(getIntent().getBooleanExtra(EXTRA_SLEEP, false));
        setContentView(view);
    }

    @Override
    protected void onResume() {
        super.onResume();
        view.start();
    }

    @Override
    protected void onPause() {
        view.stop();
        super.onPause();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP) finish();
        return true;
    }
}
