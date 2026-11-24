package app.m3home;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;

/** A Material 3 dialog with a list of choices (replaces Fire OS's teal AlertDialog). */
final class M3Menu {
    private M3Menu() {}

    interface Pick { void on(int which); }

    static void show(Activity a, CharSequence title, List<CharSequence> items, final Pick pick) {
        show(a, title, null, items, pick);
    }

    /** With a few lines of explanation under the title (the app store's details). */
    static void show(Activity a, CharSequence title, CharSequence subtitle, List<CharSequence> items, final Pick pick) {
        if (items.isEmpty()) return;
        final Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout box = new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(M3.rounded(M3.SURFACE_HIGH, M3.dp(a, 28)));
        box.setPadding(M3.dp(a, 24), M3.dp(a, 26), M3.dp(a, 24), M3.dp(a, 18));

        TextView t = new TextView(a);
        t.setText(title);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        t.setTextColor(M3.ON_SURFACE);
        t.setTypeface(M3.REGULAR);
        t.setPadding(M3.dp(a, 8), 0, M3.dp(a, 8), M3.dp(a, 14));
        box.addView(t);
        if (subtitle != null) {
            t.setPadding(M3.dp(a, 8), 0, M3.dp(a, 8), M3.dp(a, 6));
            TextView s = new TextView(a);
            s.setText(subtitle);
            s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            s.setTextColor(M3.ON_SURFACE_VAR);
            s.setTypeface(M3.REGULAR);
            s.setPadding(M3.dp(a, 8), 0, M3.dp(a, 8), M3.dp(a, 14));
            box.addView(s);
        }

        LinearLayout rows = new LinearLayout(a);
        rows.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < items.size(); i++) {
            final int which = i;
            final TextView row = new TextView(a);
            row.setText(items.get(i));
            row.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            row.setTextColor(M3.ON_SURFACE_VAR);
            row.setTypeface(M3.REGULAR);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setSingleLine(true);
            row.setPadding(M3.dp(a, 20), 0, M3.dp(a, 20), 0);
            row.setFocusable(true);
            row.setClickable(true);
            final GradientDrawable bg = M3.rounded(0x00000000, M3.dp(a, 18));
            row.setBackground(bg);
            row.setOnFocusChangeListener((v, has) -> {
                bg.setColor(has ? M3.SECONDARY_CONT : 0x00000000);
                row.setTextColor(has ? M3.ON_SECONDARY_CONT : M3.ON_SURFACE_VAR);
                row.setTypeface(has ? M3.MEDIUM : M3.REGULAR);
            });
            row.setOnClickListener(v -> {
                d.dismiss();
                pick.on(which);
            });
            rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, M3.dp(a, 56)));
        }

        ScrollView sv = new ScrollView(a);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(rows);
        int visible = Math.min(items.size(), 7);
        box.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                items.size() > 7 ? M3.dp(a, 56 * 7 + 28) : M3.dp(a, 56 * visible)));

        d.setContentView(box, new ViewGroup.LayoutParams(M3.dp(a, 520), ViewGroup.LayoutParams.WRAP_CONTENT));
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0));
            w.setDimAmount(0.6f);
        }
        d.show();
        View first = rows.getChildAt(0);
        if (first != null) first.requestFocus();
    }
}
