package app.m3home.sampleplugin;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.SystemClock;

import java.util.List;

/**
 * A MaterialTV plugin provider. MaterialTV asks for three things (and nothing else):
 *
 *   content://AUTHORITY/widgets    -> cards for the Home header and the screensaver
 *   content://AUTHORITY/rows       -> the rows this plugin offers (columns: id, title)
 *   content://AUTHORITY/rows/ID    -> the tiles in one row
 *
 * Cards and tiles use the columns title, subtitle, image_uri, intent_uri. image_uri is https://,
 * or content:// / android.resource:// from this app. intent_uri is an "intent:" URI that opens an
 * activity (MaterialTV never sends broadcasts or starts services from it).
 */
public class PluginProvider extends ContentProvider {
    private static final String[] CARD = { "title", "subtitle", "image_uri", "intent_uri" };
    private static final String[] ROWS = { "id", "title" };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        List<String> path = uri.getPathSegments();
        if (path.size() == 1 && path.get(0).equals("widgets")) return widgets();
        if (path.size() == 1 && path.get(0).equals("rows")) return rows();
        if (path.size() == 2 && path.get(0).equals("rows") && path.get(1).equals("links")) return links();
        return null;
    }

    /** One widget: how long the TV has been on. MaterialTV asks again every 15 minutes. */
    private Cursor widgets() {
        long minutes = SystemClock.elapsedRealtime() / 60_000;
        String up = minutes < 60 ? minutes + " min" : (minutes / 60) + " h " + (minutes % 60) + " min";
        MatrixCursor c = new MatrixCursor(CARD);
        c.addRow(new Object[] { "On for " + up, "since the last restart", null, null });
        return c;
    }

    private Cursor rows() {
        MatrixCursor c = new MatrixCursor(ROWS);
        c.addRow(new Object[] { "links", "Quick links" });
        return c;
    }

    private Cursor links() {
        MatrixCursor c = new MatrixCursor(CARD);
        c.addRow(new Object[] { "Fire TV settings", "Wi-Fi, display, apps", null,
                "intent:#Intent;action=android.settings.SETTINGS;end" });
        c.addRow(new Object[] { "YouTube", "Opens the app", null,
                "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;"
                        + "package=com.amazon.firetv.youtube;end" });
        c.addRow(new Object[] { "MaterialTV plugins", "The developer docs", null,
                "intent://brick-bread.github.io/MaterialTV/plugins.html#Intent;scheme=https;action=android.intent.action.VIEW;end" });
        return c;
    }

    @Override public String getType(Uri uri) { return null; }
    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
}
