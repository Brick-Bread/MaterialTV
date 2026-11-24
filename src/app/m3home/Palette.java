package app.m3home;

import android.content.Context;

/** Accent colours and the Material 3 colour scheme made from one seed colour. (1.9.0 contract stub; WP2 fills in.) */
final class Palette {
    private Palette() {}

    static final String[] ACCENTS = { "purple", "blue", "teal", "green", "yellow", "orange", "red", "pink", "background" };
    static final String[] ACCENT_NAMES = { "Purple", "Blue", "Teal", "Green", "Yellow", "Orange", "Red", "Pink", "From background" };

    /** The seed colour for an accent id. */
    static int seedOf(Context c, String accent) { return 0xFF6750A4; }

    /** The scheme for a seed. */
    static M3.Scheme scheme(int seed, boolean light) { return M3.dark(); }
}
