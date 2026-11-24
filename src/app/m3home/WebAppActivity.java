package app.m3home;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Runs one MaterialTV web app full screen: an immersive WebView with no browser chrome.
 *
 * Safety: only the app's own https origins load in the main frame (anything else asks first and
 * opens in the browser); no file or content access; no mixed content; third-party cookies off;
 * camera, microphone and location are refused. window.materialtv (the bridge) answers only while
 * the page is on one of the app's origins. Runs in its own process (":webapp") with its own
 * WebView data directory, which is closed when you leave the app.
 *
 * Remote: the arrows move focus (assets/webapp/bridge.js); Back goes back in the app and closes
 * it at its first page; holding Back, or pressing Home, always closes it.
 */
public class WebAppActivity extends Activity {
    static final String EXTRA_ID = "id", EXTRA_APP = "app", EXTRA_THEME = "theme";
    /** Pages can load the theme, font and helper from here before their first paint (never resolves on the net). */
    static final String ASSET_HOST = "materialtv.invalid";
    private static final Pattern PKG = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+");
    private static final long LOAD_TIMEOUT_MS = 30_000, HOLD_BACK_MS = 700;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebApps.App app;
    private JSONObject theme;
    private String css, helperJs, versionName = "";
    private WebView web;
    private Chrome chrome;
    private FrameLayout root;
    private View customView; // a video gone full screen
    private WebChromeClient.CustomViewCallback customCallback;
    private LinearLayout cover, coverButtons;
    private TextView coverText;
    private ProgressBar spinner;
    private volatile String pageUrl = ""; // the main frame's address, for the bridge's origin check
    private boolean shown, failed, backHeld, asking;
    private long lastToast;

    private final Runnable loadTimeout = () -> {
        if (!shown) showError("This is taking too long. Check the internet connection.");
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        M3.init(this);
        app = readApp();
        if (app == null) {
            Toast.makeText(this, "This web app isn't installed", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        theme = readTheme();
        css = buildCss(theme);
        helperJs = buildHelper();
        try {
            versionName = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // Stays "".
        }
        try {
            // Web apps keep their cookies and storage apart from anything else in MaterialTV.
            WebView.setDataDirectorySuffix("webapps");
        } catch (IllegalStateException | NoSuchMethodError ignored) {
            // Already set in this process.
        }
        immersive();
        root = new FrameLayout(this);
        root.setBackgroundColor(M3.BACKGROUND);
        try {
            web = buildWebView();
        } catch (RuntimeException e) { // no WebView installed or it failed to start
            web = null;
        }
        if (web != null) root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        cover = buildCover();
        root.addView(cover, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        if (web == null) {
            showError("This Fire TV's web engine (Android System WebView) isn't available.");
            return;
        }
        start();
    }

    private WebApps.App readApp() {
        String json = getIntent().getStringExtra(EXTRA_APP);
        if (json != null) {
            try {
                WebApps.App a = WebApps.App.fromJson(new JSONObject(json));
                if (a != null) return a;
            } catch (JSONException ignored) {
                // Fall back to the settings.
            }
        }
        String id = getIntent().getStringExtra(EXTRA_ID);
        return id == null ? null : WebApps.get(this, id);
    }

    private JSONObject readTheme() {
        String json = getIntent().getStringExtra(EXTRA_THEME);
        try {
            return json != null ? new JSONObject(json) : WebApps.theme(this);
        } catch (JSONException e) {
            try { return WebApps.theme(this); } catch (JSONException x) { return new JSONObject(); }
        }
    }

    /** A different web app opened while this one runs: start again with it. */
    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        recreate();
    }

    private void start() {
        shown = false;
        failed = false;
        showLoading();
        ui.removeCallbacks(loadTimeout);
        ui.postDelayed(loadTimeout, LOAD_TIMEOUT_MS);
        pageUrl = app.startUrl;
        web.loadUrl(app.startUrl);
    }

    // ---- the WebView ---------------------------------------------------------------------------

    private WebView buildWebView() {
        WebView w = new WebView(this);
        w.setBackgroundColor(M3.BACKGROUND);
        w.setFocusable(true);
        w.setFocusableInTouchMode(true);
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(app.may("storage"));
        s.setDatabaseEnabled(app.may("storage"));
        s.setMediaPlaybackRequiresUserGesture(!app.may("media"));
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        s.setAllowFileAccessFromFileURLs(false);
        s.setAllowUniversalAccessFromFileURLs(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setGeolocationEnabled(false);
        s.setSupportMultipleWindows(false); // window.open() comes through shouldOverrideUrlLoading
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        s.setSupportZoom(false);
        s.setTextZoom(100);
        s.setSafeBrowsingEnabled(true);
        s.setUserAgentString(s.getUserAgentString() + " MaterialTV/" + versionName + " MaterialTV-WebApp/" + WebApps.API);
        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(w, false);
        // With adb debugging on (developers), chrome://inspect can debug web apps.
        WebView.setWebContentsDebuggingEnabled(
                Settings.Global.getInt(getContentResolver(), Settings.Global.ADB_ENABLED, 0) == 1);
        w.addJavascriptInterface(new Bridge(), "materialtv");
        w.setWebViewClient(new Client());
        chrome = new Chrome();
        w.setWebChromeClient(chrome);
        return w;
    }

    private final class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
            String url = req.getUrl().toString();
            String scheme = req.getUrl().getScheme() == null ? "" : req.getUrl().getScheme().toLowerCase(Locale.ROOT);
            if (!req.isForMainFrame()) return !scheme.equals("https"); // frames: https only
            if (app.allows(url)) return false;
            offerOutside(url, scheme);
            return true;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            if (!app.allows(url)) { // belt and braces: nothing else may run in the main frame
                view.stopLoading();
                pageUrl = "";
                if (!shown) showError("This app tried to open a page that isn't part of it.");
                return;
            }
            pageUrl = url;
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            pageUrl = url == null ? "" : url;
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            inject(view, url);
            if (!failed) reveal();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            inject(view, url);
            if (!failed) reveal();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest req, WebResourceError err) {
            if (req.isForMainFrame()) showError("Couldn't load the app (" + err.getDescription() + ").");
        }

        @Override
        public void onReceivedHttpError(WebView view, WebResourceRequest req, WebResourceResponse res) {
            if (req.isForMainFrame() && !shown) showError("The app's server answered " + res.getStatusCode() + ".");
        }

        @Override
        public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
            handler.cancel(); // never load past a certificate problem
            if (!shown) showError("The app's connection isn't secure.");
        }

        @Override
        public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            // The page crashed or was stopped to save memory: close cleanly instead of with MaterialTV.
            root.removeView(view);
            view.destroy();
            if (view == web) web = null;
            Toast.makeText(WebAppActivity.this, app.name + " stopped", Toast.LENGTH_SHORT).show();
            finish();
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
            Uri u = req.getUrl();
            if (ASSET_HOST.equalsIgnoreCase(u.getHost())) return asset(u.getPath());
            // Without the "network" permission an app may only load from its own origins.
            if (!app.may("network") && !app.allows(u.toString())) return blocked();
            return null;
        }
    }

    private final class Chrome extends WebChromeClient {
        @Override
        public void onPermissionRequest(PermissionRequest request) {
            request.deny(); // no camera or microphone for web apps
        }

        @Override
        public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
            callback.invoke(origin, false, false);
        }

        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
            return false;
        }

        @Override
        public Bitmap getDefaultVideoPoster() {
            return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888); // no grey play icon before videos start
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) { callback.onCustomViewHidden(); return; }
            customView = view;
            customCallback = callback;
            view.setBackgroundColor(Color.BLACK);
            root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.requestFocus();
        }

        @Override
        public void onHideCustomView() {
            if (customView == null) return;
            root.removeView(customView);
            customView = null;
            if (customCallback != null) customCallback.onCustomViewHidden();
            customCallback = null;
            if (web != null) web.requestFocus();
        }
    }

    /** Adds the theme and spatial navigation to a page on one of the app's origins. Idempotent. */
    private void inject(WebView view, String url) {
        if (view != null && app.allows(url)) view.evaluateJavascript(helperJs, null);
    }

    /** A link to somewhere outside the app: ask, then hand it to the browser. */
    private void offerOutside(final String url, String scheme) {
        if (!scheme.equals("https") && !scheme.equals("http")) {
            Toast.makeText(this, "Blocked: " + app.name + " can't open " + scheme + ": links", Toast.LENGTH_SHORT).show();
            return;
        }
        if (asking) return;
        asking = true;
        String host = Uri.parse(url).getHost();
        List<CharSequence> items = new ArrayList<>();
        items.add("Stay in " + app.name);
        items.add("Open in the browser");
        M3Menu.show(this, "Leave " + app.name + "?", (host == null ? "That page" : host) + " isn't part of this app.",
                items, which -> {
                    asking = false;
                    if (which != 1) return;
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(this, "There's no browser on this Fire TV", Toast.LENGTH_SHORT).show();
                    }
                });
        ui.postDelayed(() -> asking = false, 1500); // a dismissed dialog (Back) doesn't call back
    }

    // ---- the reserved host: theme.css, font.ttf, sdk.js -------------------------------------------

    private WebResourceResponse asset(String path) {
        try {
            if ("/theme.css".equals(path)) return response("text/css", css.getBytes(StandardCharsets.UTF_8));
            if ("/sdk.js".equals(path)) return response("application/javascript", helperJs.getBytes(StandardCharsets.UTF_8));
            if ("/font.ttf".equals(path)) {
                InputStream in = getAssets().open("fonts/GoogleSansFlex.ttf");
                return withHeaders(new WebResourceResponse("font/ttf", null, 200, "OK", null, in), true);
            }
        } catch (IOException ignored) {
            // 404 below.
        }
        return withHeaders(new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
                new ByteArrayInputStream(new byte[0])), false);
    }

    private static WebResourceResponse response(String mime, byte[] body) {
        return withHeaders(new WebResourceResponse(mime, "utf-8", 200, "OK", null, new ByteArrayInputStream(body)), false);
    }

    private static WebResourceResponse withHeaders(WebResourceResponse r, boolean cache) {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", cache ? "max-age=86400" : "no-store");
        h.put("X-Content-Type-Options", "nosniff");
        r.setResponseHeaders(h);
        return r;
    }

    private static WebResourceResponse blocked() {
        return withHeaders(new WebResourceResponse("text/plain", "utf-8", 403, "Forbidden", null,
                new ByteArrayInputStream(new byte[0])), false);
    }

    /** The --mtv-* variables, the font, and a default focus ring the page can override. */
    private static String buildCss(JSONObject t) {
        String[][] roles = {
                { "background", "background" }, { "surface-low", "surfaceLow" }, { "surface", "surface" },
                { "surface-high", "surfaceHigh" }, { "surface-highest", "surfaceHighest" },
                { "on-surface", "onSurface" }, { "on-surface-variant", "onSurfaceVariant" },
                { "outline", "outline" }, { "outline-variant", "outlineVariant" }, { "primary", "primary" },
                { "on-primary", "onPrimary" }, { "primary-container", "primaryContainer" },
                { "on-primary-container", "onPrimaryContainer" }, { "secondary-container", "secondaryContainer" },
                { "on-secondary-container", "onSecondaryContainer" }, { "tertiary-container", "tertiaryContainer" },
                { "error", "error" } };
        StringBuilder sb = new StringBuilder();
        sb.append("@font-face{font-family:\"Google Sans Flex\";src:url(\"https://").append(ASSET_HOST)
                .append("/font.ttf\") format(\"truetype\");font-weight:1 1000;font-display:swap}\n:root{");
        for (String[] r : roles) {
            String v = t.optString(r[1], "");
            if (v.matches("#[0-9A-Fa-f]{6}")) sb.append("--mtv-").append(r[0]).append(':').append(v).append(';');
        }
        sb.append("--mtv-is-light:").append(t.optBoolean("isLight") ? 1 : 0).append(';');
        sb.append("--mtv-font:\"Google Sans Flex\",system-ui,sans-serif}\n");
        sb.append(":where(html.mtv) :where(:focus-visible){outline:3px solid var(--mtv-primary);outline-offset:3px}\n");
        return sb.toString();
    }

    private String buildHelper() {
        String js;
        try (InputStream in = getAssets().open("webapp/bridge.js")) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            js = out.toString("UTF-8");
        } catch (IOException e) {
            return "void 0";
        }
        return js.replace("__MTV_THEME__", theme.toString()).replace("__MTV_CSS__", JSONObject.quote(css));
    }

    // ---- window.materialtv -------------------------------------------------------------------

    /**
     * The JavaScript bridge. Every call first checks that the main frame is on one of the app's
     * origins (calls come in on a WebView thread; pageUrl is kept by the WebViewClient).
     */
    private final class Bridge {
        private boolean ok() { return app.allows(pageUrl); }

        @JavascriptInterface public int api() { return ok() ? WebApps.API : 0; }

        @JavascriptInterface public String version() { return ok() ? versionName : null; }

        /** The colour roles as "#RRGGBB" plus isLight, as a JSON string. */
        @JavascriptInterface public String theme() { return ok() ? theme.toString() : null; }

        /** This app as installed: id, name, version, permissions (JSON). */
        @JavascriptInterface public String app() {
            if (!ok()) return null;
            try {
                return new JSONObject().put("id", app.id).put("name", app.name).put("version", app.version)
                        .put("permissions", new org.json.JSONArray(app.permissions)).toString();
            } catch (JSONException e) {
                return null;
            }
        }

        /** What the remote's buttons send (JSON). */
        @JavascriptInterface public String keys() {
            if (!ok()) return null;
            try {
                return new JSONObject().put("up", "ArrowUp").put("down", "ArrowDown").put("left", "ArrowLeft")
                        .put("right", "ArrowRight").put("select", "Enter")
                        .put("back", "materialtv:back event (cancelable), then history.back(), then the app closes")
                        .put("menu", "materialtv:menu event")
                        .put("playPause", "MediaPlayPause").put("rewind", "MediaRewind")
                        .put("fastForward", "MediaFastForward")
                        .put("exit", "hold Back, or press Home").toString();
            } catch (JSONException e) {
                return null;
            }
        }

        @JavascriptInterface public void toast(String text) {
            if (!ok() || text == null) return;
            final String t = text.length() > 120 ? text.substring(0, 120) : text;
            ui.post(() -> {
                long now = System.currentTimeMillis();
                if (now - lastToast < 1000) return; // no toast storms
                lastToast = now;
                Toast.makeText(WebAppActivity.this, t, Toast.LENGTH_SHORT).show();
            });
        }

        @JavascriptInterface public void exit() {
            if (ok()) ui.post(WebAppActivity.this::finish);
        }

        /** Keeps the screen awake (say, while a video plays). Ends when the app closes. */
        @JavascriptInterface public void keepScreenOn(final boolean on) {
            if (!ok()) return;
            ui.post(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Opens an installed Android app, after asking you. Returns false if it isn't installed. */
        @JavascriptInterface public boolean openApp(final String pkg) {
            if (!ok() || pkg == null || !PKG.matcher(pkg).matches() || pkg.equals(getPackageName())) return false;
            final PackageManager pm = getPackageManager();
            Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
            if (i == null) i = pm.getLaunchIntentForPackage(pkg);
            if (i == null) return false;
            final Intent launch = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ui.post(() -> {
                if (asking || isFinishing()) return;
                CharSequence label;
                try {
                    label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
                } catch (PackageManager.NameNotFoundException e) {
                    return;
                }
                asking = true;
                List<CharSequence> items = new ArrayList<>();
                items.add("Open " + label);
                items.add("Cancel");
                M3Menu.show(WebAppActivity.this, "Open " + label + "?", app.name + " wants to open " + label + ".", items, which -> {
                    asking = false;
                    if (which != 0) return;
                    try {
                        startActivity(launch);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(WebAppActivity.this, "Couldn't open " + label, Toast.LENGTH_SHORT).show();
                    }
                });
                ui.postDelayed(() -> asking = false, 1500);
            });
            return true;
        }
    }

    // ---- the remote --------------------------------------------------------------------------

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                if (e.getRepeatCount() == 0) backHeld = false;
                else if (!backHeld && ((e.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0
                        || e.getEventTime() - e.getDownTime() >= HOLD_BACK_MS)) {
                    backHeld = true; // holding Back always closes the app
                    finish();
                }
            } else if (e.getAction() == KeyEvent.ACTION_UP && !backHeld && !e.isCanceled()) {
                back();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_MENU && web != null && shown && cover.getVisibility() != View.VISIBLE) {
            if (e.getAction() == KeyEvent.ACTION_UP) web.evaluateJavascript("window.__mtvMenu && window.__mtvMenu()", null);
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    /** Back: a full-screen video first, then the page's own handler, then history, then close. */
    private void back() {
        if (customView != null && chrome != null) { chrome.onHideCustomView(); return; }
        if (web == null || failed || !shown) { finish(); return; }
        if (!app.allows(pageUrl)) { finish(); return; }
        web.evaluateJavascript("!!(window.__mtvBack && window.__mtvBack())", handled -> {
            if ("true".equals(handled) || isFinishing() || web == null) return;
            if (web.canGoBack()) web.goBack();
            else finish();
        });
    }

    /** Home (or opening another app) closes the web app: one at a time, nothing left running. */
    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        if (web != null) {
            root.removeView(web);
            web.stopLoading();
            web.destroy();
            web = null;
        }
        boolean closing = isFinishing();
        super.onDestroy();
        // The process exists only for this screen: end it so the page's memory and audio go too.
        if (closing) Process.killProcess(Process.myPid());
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    // ---- loading and error screen --------------------------------------------------------------

    private LinearLayout buildCover() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setBackgroundColor(M3.BACKGROUND);
        box.setClickable(true); // nothing behind it gets touched

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(WebApps.icon(this, app));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setBackground(M3.rounded(M3.SURFACE_HIGH, dp(32)));
        icon.setPadding(dp(18), dp(18), dp(18), dp(18));
        icon.setClipToOutline(true);
        box.addView(icon, new LinearLayout.LayoutParams(dp(120), dp(120)));

        TextView title = text(app.name, 32, M3.ON_SURFACE);
        title.setPadding(0, dp(24), 0, dp(6));
        box.addView(title);
        String by = app.author.isEmpty() ? "Web app" : "Web app by " + app.author;
        box.addView(text(by, 16, M3.ON_SURFACE_VAR));

        spinner = new ProgressBar(this);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(M3.PRIMARY));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.topMargin = dp(32);
        box.addView(spinner, slp);

        coverText = text("", 17, M3.ON_SURFACE_VAR);
        coverText.setPadding(dp(48), dp(20), dp(48), 0);
        coverText.setMaxWidth(dp(640));
        box.addView(coverText);

        coverButtons = new LinearLayout(this);
        coverButtons.setOrientation(LinearLayout.HORIZONTAL);
        coverButtons.setGravity(Gravity.CENTER);
        coverButtons.setPadding(0, dp(28), 0, 0);
        coverButtons.addView(button("Try again", true, v -> { if (web != null) start(); }));
        coverButtons.addView(button("Close", false, v -> finish()));
        box.addView(coverButtons);
        return box;
    }

    private TextView text(String s, float sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(M3.REGULAR);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    /** An M3 pill button: filled (primary) or tonal, with a ring when focused. */
    private TextView button(String label, boolean filled, View.OnClickListener click) {
        final TextView b = text(label, 17, filled ? M3.ON_PRIMARY : M3.ON_SECONDARY_CONT);
        b.setTypeface(M3.MEDIUM);
        b.setFocusable(true);
        b.setClickable(true);
        b.setPadding(dp(28), dp(12), dp(28), dp(12));
        final int fill = filled ? M3.PRIMARY : M3.SECONDARY_CONT;
        b.setBackground(M3.rounded(fill, dp(24)));
        b.setOnFocusChangeListener((v, has) -> {
            b.setBackground(has ? M3.outlined(fill, M3.ON_SURFACE, dp(3), dp(24)) : M3.rounded(fill, dp(24)));
            b.animate().scaleX(has ? 1.06f : 1f).scaleY(has ? 1.06f : 1f).setDuration(150).start();
        });
        b.setOnClickListener(click);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = lp.rightMargin = dp(8);
        b.setLayoutParams(lp);
        return b;
    }

    private void showLoading() {
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.VISIBLE);
        coverText.setText("Loading…");
        coverButtons.setVisibility(View.GONE);
    }

    private void showError(String why) {
        failed = true;
        shown = false;
        ui.removeCallbacks(loadTimeout);
        if (web != null) web.stopLoading();
        cover.animate().cancel();
        cover.setAlpha(1f);
        cover.setVisibility(View.VISIBLE);
        spinner.setVisibility(View.GONE);
        coverText.setText(why);
        coverButtons.setVisibility(View.VISIBLE);
        coverButtons.getChildAt(web != null ? 0 : 1).requestFocus();
        coverButtons.getChildAt(0).setVisibility(web != null ? View.VISIBLE : View.GONE);
    }

    /** The page is drawing: fade the loading screen away and hand the remote to the page. */
    private void reveal() {
        if (shown) return;
        shown = true;
        ui.removeCallbacks(loadTimeout);
        cover.animate().alpha(0f).setDuration(220).withEndAction(() -> {
            if (shown) cover.setVisibility(View.GONE);
        }).start();
        if (web != null) web.requestFocus();
    }

    private int dp(float v) { return M3.dp(this, v); }
}
