package com.stxaviers.app;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/**
 * Base activity (v1.1.2) — the in-app theme selector's delivery mechanism.
 *
 * Every screen extends this instead of plain Activity. attachBaseContext
 * wraps the context with the user's chosen night mode (Light / Dark /
 * System — see Ui.themed), and onResume recreates the screen if the choice
 * changed while it was alive, so every @color ref re-resolves immediately.
 *
 * Subclasses MUST call super.onCreate / super.onResume (they all do — the
 * compiler-visible convention since v1.1.0).
 */
public class XdActivity extends Activity {

    protected int appliedThemeStamp;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Ui.themed(base));
        appliedThemeStamp = Ui.themeStamp();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (appliedThemeStamp != Ui.themeStamp()) {
            appliedThemeStamp = Ui.themeStamp();
            recreate();
        }
    }

    /** Kept so subclasses inherit the normal lifecycle contract. */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }
}
