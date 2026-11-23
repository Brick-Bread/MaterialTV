package app.m3home;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.Toast;

/**
 * When the sleep timer ends: one minute of the sleep scene, slowly fading darker, then the stick
 * goes to sleep. Any key cancels and keeps the TV on.
 */
public class WindDownActivity extends Activity {
    private static final int SECONDS = 60;

    private final Handler h = new Handler(Looper.getMainLooper());
    private ScreensaverView scene;
    private int left = SECONDS;
    private boolean done;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(this);
        scene = new ScreensaverView(this);
        scene.forceSleep(true);
        root.addView(scene);
        View dark = new View(this);
        dark.setBackgroundColor(0xFF000000);
        dark.setAlpha(0f);
        root.addView(dark);
        setContentView(root);
        dark.animate().alpha(0.9f).setDuration(SECONDS * 1000L).start();
        h.post(tick);
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (done) return;
            if (left <= 0) {
                done = true;
                HomeGuard.sleepNow();
                finish();
                return;
            }
            scene.setSleepMessage("Going to sleep in " + left + " s  ·  press any key to stay awake");
            left--;
            h.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onResume() {
        super.onResume();
        scene.start();
        scene.setSleepMessage("Going to sleep in " + left + " s  ·  press any key to stay awake");
    }

    @Override
    protected void onPause() {
        scene.stop();
        super.onPause();
        if (!done) finish(); // leaving this screen counts as "stay awake"
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() == KeyEvent.ACTION_UP && !done) {
            done = true;
            h.removeCallbacksAndMessages(null);
            Toast.makeText(this, "Staying awake. Sleep timer cancelled.", Toast.LENGTH_SHORT).show();
            finish();
        }
        return true;
    }
}
