package app.m3home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;

/**
 * M3 Home's accessibility service. It is told (res/xml/home_guard.xml) only about window changes
 * in Amazon's launcher and Amazon's settings app, and reads no screen content. It does three things:
 *  - Home: when Amazon's home screen appears, M3 Home is opened on top.
 *  - Hold Home: when Amazon's quick menu (HUD) appears, it is closed and M3 Home's settings open.
 *  - Display filter: draws the brightness dim and blue-light (warm) layer over everything.
 */
public class HomeGuard extends AccessibilityService {
    private static final String AMAZON_LAUNCHER = "com.amazon.tv.launcher";
    private static final String AMAZON_HOME = "com.amazon.tv.launcher.ui.HomeActivity_vNext";
    private static final String AMAZON_SETTINGS = "com.amazon.tv.settings.v2";
    private static final String AMAZON_HUD = "com.amazon.tv.settings.v2.hud.HudActivity";

    private static HomeGuard instance;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private FilterView filter;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        applyFilter();
        scheduleSleep();
    }

    // ---- sleep timer -------------------------------------------------------------------

    /** Timer's up: show the one-minute wind-down, which then calls sleepNow(). */
    private final Runnable timerEnded = () -> {
        Prefs.of(this).edit().putLong(Prefs.SLEEP_AT, 0).apply();
        open(new Intent(this, WindDownActivity.class));
    };

    /** Puts the stick to sleep (on Fire TV, "lock screen" is standby). */
    static boolean sleepNow() {
        HomeGuard g = instance;
        return g != null && g.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
    }

    /** Re-reads the sleep time. False if the service isn't running. */
    static boolean refreshSleep() {
        HomeGuard g = instance;
        if (g == null) return false;
        g.ui.post(g::scheduleSleep);
        return true;
    }

    private void scheduleSleep() {
        ui.removeCallbacks(timerEnded);
        long at = Prefs.sleepAt(this);
        if (at == 0) return;
        ui.postDelayed(timerEnded, Math.max(0, at - System.currentTimeMillis()));
    }

    @Override
    public boolean onUnbind(Intent intent) {
        removeFilter();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        removeFilter();
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent e) {
        if (e == null || e.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        CharSequence pkg = e.getPackageName();
        CharSequence cls = e.getClassName();
        if (pkg == null || cls == null) return;

        if (AMAZON_LAUNCHER.contentEquals(pkg) && AMAZON_HOME.contentEquals(cls)) {
            open(new Intent(this, Home.class)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } else if (AMAZON_SETTINGS.contentEquals(pkg) && AMAZON_HUD.contentEquals(cls)) {
            performGlobalAction(GLOBAL_ACTION_BACK); // close Amazon's quick menu first
            ui.postDelayed(() -> open(new Intent(this, SettingsActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(SettingsActivity.EXTRA_QUICK, true)), 150);
        }
    }

    private void open(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException ignored) {
            // Never crash the service; the next press tries again.
        }
    }

    @Override
    public void onInterrupt() { }

    // ---- display filter ----------------------------------------------------------------

    /** Re-reads the brightness / blue-light settings. False if the service isn't running. */
    static boolean refreshFilter() {
        HomeGuard g = instance;
        if (g == null) return false;
        g.ui.post(g::applyFilter);
        return true;
    }

    static boolean isRunning() {
        return instance != null;
    }

    private void applyFilter() {
        float dim = (100 - Prefs.brightness(this)) / 100f * 0.8f;          // up to 64% black
        float warm = Prefs.warm(this) ? Prefs.warmStrength(this) / 100f * 0.42f : 0f; // up to 42% amber
        if (dim < 0.005f && warm < 0.005f) {
            removeFilter();
            return;
        }
        if (filter == null) {
            filter = new FilterView(this);
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            try {
                ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).addView(filter, lp);
            } catch (RuntimeException e) {
                filter = null;
                return;
            }
        }
        filter.set(dim, warm);
    }

    private void removeFilter() {
        if (filter == null) return;
        try {
            ((WindowManager) getSystemService(Context.WINDOW_SERVICE)).removeView(filter);
        } catch (RuntimeException ignored) { }
        filter = null;
    }

    /** A see-through layer: warm amber first, then black on top for dimming. */
    private static final class FilterView extends View {
        private float dim, warm;

        FilterView(Context c) { super(c); }

        void set(float dim, float warm) {
            this.dim = dim;
            this.warm = warm;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            if (warm > 0) c.drawColor(Color.argb(Math.round(warm * 255), 255, 138, 20));
            if (dim > 0) c.drawColor(Color.argb(Math.round(dim * 255), 0, 0, 0));
        }
    }
}
