package app.m3home;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.VideoView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * The screensaver, shared by the Fire TV screensaver (Screensaver) and the preview
 * (ScreensaverActivity). Modes: an expressive clock over floating shapes, Material shapes, and a
 * slideshow of pictures and live wallpapers from the app's "screensaver" folder (sub-folders are
 * themes). Shapes and slideshow show a glass card with the time and widgets; "Rotate" cycles modes.
 */
final class ScreensaverView extends FrameLayout {
    private static final long ROTATE_MS = 5 * 60_000;
    private static final long VIDEO_MIN_MS = 20_000, VIDEO_MAX_MS = 45_000;
    private static final long DRIFT_MS = 60_000, INFO_MS = 20_000;

    private static final List<String> IMAGE_EXT = Arrays.asList("jpg", "jpeg", "png", "webp");
    private static final List<String> VIDEO_EXT = Arrays.asList("mp4", "m4v", "webm", "mkv", "3gp");

    private final Handler h = new Handler(Looper.getMainLooper());
    private final Random rnd = new Random();
    private final List<String> modes = new ArrayList<>();
    private int modeIndex;
    private boolean running;

    private final FrameLayout stage;  // the current mode
    private View veil;                // black layer for soft transitions
    private ShapesView shapes;

    // info (time + widgets), rebuilt per mode
    private TextView infoTime, infoDate, infoCaption;
    private LinearLayout infoWidgets;
    private boolean widgetsAsChips;

    // slideshow
    private final List<File> media = new ArrayList<>();
    private int mediaIndex;
    private ImageView imgA, imgB;
    private boolean useA = true;
    private VideoView video;
    private long videoStarted;
    private int generation;

    ScreensaverView(Context c) {
        super(c);
        M3.init(c);
        setBackground(new Glow());
        setKeepScreenOn(true);
        stage = new FrameLayout(c);
        addView(stage, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        veil = new View(c);
        veil.setBackgroundColor(0xFF000000);
        veil.setAlpha(1f);
        addView(veil, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    }

    // ---- media folder ---------------------------------------------------------------------

    static File mediaDir(Context c) {
        File d = c.getExternalFilesDir("screensaver");
        if (d != null && !d.exists()) d.mkdirs();
        return d;
    }

    /** Everything in the screensaver folder and its theme sub-folders. */
    static List<File> listMedia(Context c) {
        List<File> out = new ArrayList<>();
        collect(mediaDir(c), out, 0);
        Collections.sort(out);
        return out;
    }

    private static void collect(File dir, List<File> out, int depth) {
        File[] files = dir == null ? null : dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && depth == 0) collect(f, out, 1);
            else if (f.isFile() && (isImage(f) || isVideo(f))) out.add(f);
        }
    }

    /** The media the settings allow: stills / live / both, and only the themes switched on. */
    static List<File> chosenMedia(Context c) {
        String kind = Prefs.screensaverMedia(c);
        File root = mediaDir(c);
        List<File> out = new ArrayList<>();
        for (File f : listMedia(c)) {
            if ("stills".equals(kind) && !isImage(f)) continue;
            if ("live".equals(kind) && !isVideo(f)) continue;
            File parent = f.getParentFile();
            if (parent != null && root != null && !parent.equals(root)) {
                String theme = parent.getName().toLowerCase(Locale.ROOT);
                if (!Prefs.of(c).getBoolean(Prefs.SS_THEME + theme, true)) continue;
            }
            out.add(f);
        }
        return out;
    }

    private static String ext(File f) {
        String n = f.getName();
        int i = n.lastIndexOf('.');
        return i < 0 ? "" : n.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isImage(File f) { return IMAGE_EXT.contains(ext(f)); }
    static boolean isVideo(File f) { return VIDEO_EXT.contains(ext(f)); }

    /** "v06-aurora-over-snowy-lake-lofoten.mp4" -> "Aurora over snowy lake lofoten". */
    static String prettyName(File f) {
        String n = f.getName();
        int dot = n.lastIndexOf('.');
        if (dot > 0) n = n.substring(0, dot);
        n = n.replaceFirst("^[a-z]\\d{2}-", "").replace('-', ' ').replace('_', ' ').trim();
        return n.isEmpty() ? f.getName() : Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    // ---- lifecycle --------------------------------------------------------------------------

    void start() {
        if (running) return;
        running = true;
        media.clear();
        media.addAll(chosenMedia(getContext()));
        Collections.shuffle(media, rnd);
        mediaIndex = 0;

        modes.clear();
        String m = Prefs.screensaverMode(getContext());
        sleepMode = forceSleep || Prefs.isBedtime(getContext());
        if (sleepMode) {
            modes.add("sleep"); // bedtime: only the calm night scene
        } else if ("rotate".equals(m)) {
            modes.add("clock");
            modes.add("shapes");
            if (!media.isEmpty()) modes.add("slideshow");
            Collections.shuffle(modes, rnd);
        } else {
            modes.add("slideshow".equals(m) && media.isEmpty() ? "clock" : m);
        }
        modeIndex = 0;
        Weather.refreshIfStale(getContext(), () -> post(this::updateInfo));
        build();
        veil.animate().alpha(0f).setDuration(1200).start();
        h.post(infoTick);
        if (!forceSleep) h.postDelayed(bedtimeCheck, 60_000);
    }

    /** Preview / wind-down: always show the sleep scene. */
    void forceSleep(boolean on) { forceSleep = on; }

    private boolean forceSleep, sleepMode;

    /** Switches between the normal screensaver and the sleep scene at bedtime and wake-up. */
    private final Runnable bedtimeCheck = new Runnable() {
        @Override public void run() {
            if (!running) return;
            if (Prefs.isBedtime(getContext()) != sleepMode) {
                veil.animate().alpha(1f).setDuration(1500).withEndAction(() -> {
                    stop();
                    start();
                }).start();
                return;
            }
            h.postDelayed(this, 60_000);
        }
    };

    void stop() {
        running = false;
        h.removeCallbacksAndMessages(null);
        teardown();
    }

    private final Runnable rotate = () -> {
        modeIndex = (modeIndex + 1) % modes.size();
        veil.animate().alpha(1f).setDuration(700).withEndAction(() -> {
            if (!running) return;
            build();
            veil.animate().alpha(0f).setDuration(1100).start();
        }).start();
    };

    private final Runnable infoTick = new Runnable() {
        @Override public void run() {
            updateInfo();
            h.postDelayed(this, INFO_MS);
        }
    };

    private void build() {
        h.removeCallbacks(rotate);
        teardown();
        String m = modes.get(modeIndex);
        if ("sleep".equals(m)) buildSleep();
        else if ("shapes".equals(m)) buildShapes();
        else if ("slideshow".equals(m)) buildSlideshow();
        else buildClock();
        updateInfo();
        if (modes.size() > 1) h.postDelayed(rotate, ROTATE_MS);
    }

    private void teardown() {
        generation++;
        if (shapes != null) { shapes.stop(); shapes = null; }
        if (sleepScene != null) { sleepScene.stop(); sleepScene = null; }
        if (video != null) { try { video.stopPlayback(); } catch (RuntimeException ignored) { } video = null; }
        if (imgA != null) release(imgA);
        if (imgB != null) release(imgB);
        imgA = imgB = null;
        infoTime = infoDate = infoCaption = null;
        infoWidgets = null;
        stage.removeAllViews();
    }

    private int dp(float v) { return M3.dp(getContext(), v); }
    private boolean showInfo() { return Prefs.on(getContext(), Prefs.SS_INFO); }

    private TextView text(float sp, int color, android.graphics.Typeface face) {
        TextView t = new TextView(getContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(face);
        return t;
    }

    private CharSequence time() {
        return DateFormat.format(DateFormat.is24HourFormat(getContext()) ? "HH:mm" : "h:mm", new Date());
    }

    private static String greeting() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 5 && hour < 12 ? "Good morning" : hour >= 12 && hour < 17 ? "Good afternoon"
                : hour >= 17 && hour < 22 ? "Good evening" : "Good night";
    }

    // ---- sleep: night sky, dim clock ---------------------------------------------------------------

    private SleepView sleepScene;

    /** The bedtime scene. Kept very dim so it doesn't light up the room. */
    private void buildSleep() {
        sleepScene = new SleepView(getContext());
        stage.addView(sleepScene, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        sleepScene.start();

        final LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        infoTime = text(104, 0x80E6E0E9, M3.LIGHT);
        infoTime.setIncludeFontPadding(false);
        box.addView(infoTime);
        sleepLine = text(20, 0x80D0BCFF, M3.REGULAR);
        sleepLine.setPadding(0, dp(6), 0, 0);
        sleepLine.setText(sleepMessage == null ? "Sleep well" : sleepMessage);
        sleepLine.setGravity(Gravity.CENTER);
        box.addView(sleepLine, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        lp.bottomMargin = dp(90);
        stage.addView(box, lp);

        final Runnable drift = new Runnable() {
            @Override public void run() {
                float dx = (rnd.nextFloat() - 0.5f) * getWidth() * 0.35f;
                float dy = -rnd.nextFloat() * getHeight() * 0.25f;
                box.animate().translationX(dx).translationY(dy).setDuration(12_000).start();
                h.postDelayed(this, DRIFT_MS);
            }
        };
        h.postDelayed(drift, DRIFT_MS);
    }

    private TextView sleepLine;
    private String sleepMessage;

    /** Replaces "Sleep well" (the wind-down uses it for its countdown). */
    void setSleepMessage(String s) {
        sleepMessage = s;
        if (sleepLine != null) sleepLine.setText(s);
    }

    // ---- clock: huge expressive digits over drifting shapes --------------------------------------

    private void buildClock() {
        shapes = new ShapesView(getContext());
        shapes.setAlpha(0.45f);
        stage.addView(shapes, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shapes.start();

        final LinearLayout box = new LinearLayout(getContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        TextView hello = text(26, M3.PRIMARY, M3.MEDIUM);
        hello.setText(greeting());
        box.addView(hello);
        infoTime = text(190, M3.ON_SURFACE, M3.LIGHT);
        infoTime.setIncludeFontPadding(false);
        infoTime.setLetterSpacing(-0.03f);
        box.addView(infoTime);
        infoDate = text(30, M3.ON_SURFACE_VAR, M3.REGULAR);
        infoDate.setPadding(0, dp(4), 0, dp(28));
        box.addView(infoDate);
        if (showInfo()) {
            infoWidgets = new LinearLayout(getContext());
            infoWidgets.setOrientation(LinearLayout.HORIZONTAL);
            infoWidgets.setGravity(Gravity.CENTER);
            widgetsAsChips = true;
            box.addView(infoWidgets);
        }
        stage.addView(box, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        // Drift slowly so nothing sits still on the panel for long (avoids burn-in).
        final Runnable drift = new Runnable() {
            @Override public void run() {
                float dx = (rnd.nextFloat() - 0.5f) * getWidth() * 0.22f;
                float dy = (rnd.nextFloat() - 0.5f) * getHeight() * 0.18f;
                box.animate().translationX(dx).translationY(dy).setDuration(9000).start();
                h.postDelayed(this, DRIFT_MS);
            }
        };
        h.postDelayed(drift, DRIFT_MS);
    }

    // ---- shapes -------------------------------------------------------------------------------

    private void buildShapes() {
        shapes = new ShapesView(getContext());
        stage.addView(shapes, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        shapes.start();
        addGlassCard();
    }

    /** The M3 glass card, bottom left: big time, date, the picture's name, then widget lines. */
    private void addGlassCard() {
        if (!showInfo()) return;
        LinearLayout card = new LinearLayout(getContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(M3.outlined(0xB3141218, 0x33FFFFFF, dp(1), dp(32)));
        card.setPadding(dp(32), dp(22), dp(36), dp(24));

        LinearLayout top = new LinearLayout(getContext());
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setBaselineAligned(true);
        infoTime = text(76, M3.ON_SURFACE, M3.LIGHT);
        infoTime.setIncludeFontPadding(false);
        top.addView(infoTime);
        infoDate = text(20, M3.ON_SURFACE_VAR, M3.REGULAR);
        infoDate.setPadding(dp(18), 0, 0, 0);
        top.addView(infoDate);
        card.addView(top);

        infoCaption = text(16, M3.PRIMARY, M3.MEDIUM);
        infoCaption.setPadding(dp(2), dp(4), 0, 0);
        infoCaption.setSingleLine(true);
        infoCaption.setEllipsize(TextUtils.TruncateAt.END);
        infoCaption.setVisibility(View.GONE);
        card.addView(infoCaption);

        infoWidgets = new LinearLayout(getContext());
        infoWidgets.setOrientation(LinearLayout.VERTICAL);
        infoWidgets.setPadding(0, dp(10), 0, 0);
        widgetsAsChips = false;
        card.addView(infoWidgets);

        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.BOTTOM);
        lp.setMargins(dp(56), 0, 0, dp(48));
        stage.addView(card, lp);
        card.setMinimumWidth(dp(460));
    }

    /** Refreshes the time, date and widgets wherever this mode shows them. */
    private void updateInfo() {
        if (infoTime != null) infoTime.setText(time());
        if (infoDate != null) infoDate.setText(DateFormat.format("EEEE, d MMMM", new Date()));
        if (infoWidgets == null) return;
        infoWidgets.removeAllViews();
        Context c = getContext();

        if (Prefs.on(c, Prefs.W_WEATHER)) {
            Weather.Now w = Weather.current(c);
            if (w != null) {
                widget(Weather.icon(w.code, w.day), w.temp + "°  " + Weather.describe(w.code),
                        "H " + w.high + "°  L " + w.low + "°" + (w.rainChance > 0 ? "  ·  " + w.rainChance + "% rain" : ""));
            }
        }
        if (Prefs.on(c, Prefs.W_DEADLINES)) {
            List<Deadlines.Item> ds = Deadlines.upcoming(c);
            for (int i = 0; i < Math.min(widgetsAsChips ? 1 : 3, ds.size()); i++) {
                widget(R.drawable.ic_event, ds.get(i).title, ds.get(i).when());
            }
        }
        if (Prefs.on(c, Prefs.W_COUNTDOWN)) {
            Deadlines.Item cd = Deadlines.countdown(c);
            if (cd != null && cd.daysLeft() >= 0) {
                long d = cd.daysLeft();
                widget(R.drawable.ic_timer, cd.title, d == 0 ? "today" : d == 1 ? "1 day to go" : d + " days to go");
            }
        }
        if (Prefs.on(c, Prefs.W_NOWPLAYING)) {
            String np = NowPlaying.current(c);
            if (np != null) widget(R.drawable.ic_music, np, null);
        }
        if (Prefs.on(c, Prefs.W_TAILSCALE)) {
            String ip = Net.tailscaleIp(c);
            widget(R.drawable.ic_tailnet, ip == null ? "Tailscale off" : "Tailscale", ip == null || ip.isEmpty() ? null : Net.show(c, ip));
        }
    }

    /** One widget: a line in the glass card, or a tonal chip under the big clock. */
    private void widget(int icon, String title, String detail) {
        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ImageView iv = new ImageView(getContext());
        iv.setImageResource(icon);
        iv.setImageTintList(ColorStateList.valueOf(M3.PRIMARY));
        int is = dp(widgetsAsChips ? 24 : 22);
        row.addView(iv, new LinearLayout.LayoutParams(is, is));
        TextView t = text(widgetsAsChips ? 20 : 19, M3.ON_SURFACE, M3.REGULAR);
        t.setText(title);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setMaxWidth(dp(widgetsAsChips ? 360 : 420));
        t.setPadding(dp(12), 0, 0, 0);
        row.addView(t);
        if (detail != null) {
            TextView d = text(widgetsAsChips ? 18 : 17, M3.ON_SURFACE_VAR, M3.REGULAR);
            d.setText("  ·  " + detail);
            d.setSingleLine(true);
            row.addView(d);
        }
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (widgetsAsChips) {
            row.setBackground(M3.rounded(0x66211F26, dp(24)));
            row.setPadding(dp(18), dp(12), dp(22), dp(12));
            lp.setMargins(dp(8), 0, dp(8), 0);
            if (infoWidgets.getChildCount() >= 3) return; // three chips fit the screen and keep it calm
        } else {
            lp.topMargin = dp(10);
        }
        infoWidgets.addView(row, lp);
    }

    // ---- slideshow ----------------------------------------------------------------------------

    private void buildSlideshow() {
        FrameLayout f = new FrameLayout(getContext());
        f.setBackgroundColor(0xFF000000);
        video = new VideoView(getContext());
        video.setVisibility(View.GONE);
        f.addView(video, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER));
        imgA = image();
        imgB = image();
        f.addView(imgA);
        f.addView(imgB);
        stage.addView(f, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        addGlassCard();
        nextSlide();
    }

    private ImageView image() {
        ImageView v = new ImageView(getContext());
        v.setScaleType(ImageView.ScaleType.CENTER_CROP);
        v.setAlpha(0f);
        v.setLayoutParams(new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        return v;
    }

    private final Runnable next = this::nextSlide;

    private void nextSlide() {
        if (!running || media.isEmpty() || video == null) return;
        h.removeCallbacks(next);
        File f = media.get(mediaIndex % media.size());
        mediaIndex++;
        if (infoCaption != null) {
            infoCaption.setText(prettyName(f));
            infoCaption.setVisibility(View.VISIBLE);
        }
        boolean fromVideo = video.getVisibility() == View.VISIBLE;
        if (isVideo(f)) {
            // Dip to black, then start the video; it fades in once it's ready.
            veil.animate().alpha(1f).setDuration(500).withEndAction(() -> playVideo(f)).start();
        } else if (fromVideo) {
            veil.animate().alpha(1f).setDuration(500).withEndAction(() -> showImage(f, true)).start();
        } else {
            showImage(f, false);
        }
    }

    private void showImage(final File f, final boolean afterVideo) {
        final int gen = generation;
        final int w = Math.max(getWidth(), 1280), hh = Math.max(getHeight(), 720);
        new Thread(() -> {
            final Bitmap bm = decode(f, w, hh);
            post(() -> {
                if (gen != generation || imgA == null) { if (bm != null) bm.recycle(); return; }
                if (bm == null) { h.post(next); return; }
                if (afterVideo || video.getVisibility() == View.VISIBLE) {
                    try { video.stopPlayback(); } catch (RuntimeException ignored) { }
                    video.setVisibility(View.GONE);
                }
                final ImageView in = useA ? imgA : imgB, out = useA ? imgB : imgA;
                useA = !useA;
                in.setImageBitmap(bm);
                in.bringToFront();
                // Ken Burns: a slow zoom and pan while the picture is up.
                long ms = Prefs.screensaverSeconds(getContext()) * 1000L;
                in.setScaleX(1f);
                in.setScaleY(1f);
                in.setTranslationX(0);
                in.setTranslationY(0);
                float dx = (rnd.nextFloat() - 0.5f) * dp(60), dy = (rnd.nextFloat() - 0.5f) * dp(30);
                in.animate().alpha(1f).scaleX(1.12f).scaleY(1.12f).translationX(dx).translationY(dy)
                        .setInterpolator(new LinearInterpolator()).setDuration(ms + 2000).start();
                out.animate().alpha(0f).setDuration(1600).withEndAction(() -> release(out)).start();
                if (afterVideo) veil.animate().alpha(0f).setDuration(900).start();
                h.postDelayed(next, ms);
            });
        }, "m3home-slide").start();
    }

    private void playVideo(File f) {
        if (!running || video == null) return;
        if (imgA != null) { imgA.animate().cancel(); imgA.setAlpha(0f); release(imgA); }
        if (imgB != null) { imgB.animate().cancel(); imgB.setAlpha(0f); release(imgB); }
        video.setVisibility(View.VISIBLE);
        video.setOnPreparedListener(mp -> {
            mp.setVolume(0f, 0f);
            mp.setLooping(false);
            videoStarted = System.currentTimeMillis();
            video.start();
            veil.animate().alpha(0f).setDuration(900).start();
            int d = mp.getDuration();
            h.postDelayed(next, d > 0 && d < VIDEO_MAX_MS && d >= VIDEO_MIN_MS ? d - 600 : VIDEO_MAX_MS);
        });
        // Short clips play again, with a quick dip so the restart doesn't jump.
        video.setOnCompletionListener(mp -> {
            if (System.currentTimeMillis() - videoStarted < VIDEO_MIN_MS) {
                veil.animate().alpha(0.85f).setDuration(220).withEndAction(() -> {
                    if (video == null) return;
                    video.seekTo(0);
                    video.start();
                    veil.animate().alpha(0f).setDuration(450).start();
                }).start();
            } else {
                h.removeCallbacks(next);
                h.post(next);
            }
        });
        video.setOnErrorListener((mp, what, extra) -> {
            h.removeCallbacks(next);
            h.post(next);
            return true;
        });
        video.setVideoPath(f.getAbsolutePath());
    }

    private static void release(ImageView v) {
        v.animate().cancel();
        android.graphics.drawable.Drawable d = v.getDrawable();
        v.setImageDrawable(null);
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) b.recycle();
        }
    }

    /** Decodes at no more than about screen size, in RGB_565 to keep memory low on the stick. */
    private static Bitmap decode(File f, int maxW, int maxH) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= maxW && o.outHeight / (sample * 2) >= maxH) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeFile(f.getAbsolutePath(), o2);
        } catch (OutOfMemoryError | RuntimeException e) {
            return null;
        }
    }
}
