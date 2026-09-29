package com.stxaviers.app;

import android.app.Activity;
import android.content.Intent;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * Shared bottom navigation (v1.1.2) — three destinations.
 *
 * Home · XavierDrive AI · Profile. Files and Notices now live in the Home
 * school tools (owner order), and the AI experience opens full-screen
 * WITHOUT this bar — entering it plays a slide-up transition; the in-app
 * cross returns to Home.
 *
 * Screens that include layout/include_bottom_nav.xml wire it once through
 * wire(activity, selectedTab). Tapping a tab launches (or re-fronts) the
 * matching activity and finishes the current one when it is NOT Home
 * (Home is the root of the back stack and owns the exit-on-double-back
 * behaviour).
 */
public final class TabNav {

    public static final int TAB_HOME = 0;
    public static final int TAB_AI = 1;
    public static final int TAB_PROFILE = 2;

    private TabNav() {}

    public static void wire(final Activity a, int selected) {
        // IDs come from the shared include — identical in every screen.
        int clickIds[] = {R.id.nav_home, R.id.nav_ai, R.id.nav_profile};
        int iconIds[] = {R.id.nav_home_icon, R.id.nav_ai_icon,
                R.id.nav_profile_icon};
        int labelIds[] = {R.id.nav_home_label, R.id.nav_ai_label,
                R.id.nav_profile_label};
        int pillIds[] = {R.id.nav_home_pill, R.id.nav_ai_pill,
                R.id.nav_profile_pill};
        int accent = Fx.color(a, R.color.home_nav_active);
        int inactive = Fx.color(a, R.color.home_nav_inactive);

        for (int i = 0; i < clickIds.length; i++) {
            ImageView icon = (ImageView) a.findViewById(iconIds[i]);
            TextView label = (TextView) a.findViewById(labelIds[i]);
            View pill = a.findViewById(pillIds[i]);
            boolean on = (i == selected);
            // the AI center button keeps its white-on-gradient icon; only
            // its label joins the accent/inactive rhythm
            if (icon != null && i != TAB_AI) {
                icon.setColorFilter(on ? accent : inactive);
            }
            if (label != null) label.setTextColor(on ? accent : inactive);
            if (pill != null) {
                if (i == TAB_AI) {
                    pill.setVisibility(View.GONE);
                } else {
                    pill.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
                }
            }
            View tab = a.findViewById(clickIds[i]);
            if (tab == null) continue;
            final int idx = i;
            if (on) {
                tab.setOnClickListener(null); // never restart yourself
            } else {
                tab.setOnClickListener(v -> go(a, idx));
            }
        }
    }

    /**
     * Launch the tab's activity. CLEAR_TOP|SINGLE_TOP semantics: if the
     * target is already in the stack it is brought forward with
     * onNewIntent (state kept) and anything above it is finished; if not,
     * it is pushed. Home is never finished — it is the root, and its
     * double-back-to-exit behaviour stays intact. Entering the AI slides
     * it up over the current screen (cool little transition, owner order).
     */
    public static void go(Activity from, int tab) {
        Class<?> cls;
        switch (tab) {
            case TAB_AI:      cls = AiActivity.class;      break;
            case TAB_PROFILE: cls = ProfileActivity.class; break;
            default:          cls = HomeActivity.class;    break;
        }
        try {
            Intent i = new Intent(from, cls);
            i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                    | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            from.startActivity(i);
            if (tab == TAB_AI) {
                from.overridePendingTransition(R.anim.ai_in, 0);
            }
        } catch (Throwable ignored) {}
    }
}
