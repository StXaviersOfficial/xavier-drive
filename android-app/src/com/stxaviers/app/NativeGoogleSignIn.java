package com.stxaviers.app;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.auth.api.signin.GoogleSignInAccount;
import com.google.android.gms.auth.api.signin.GoogleSignInOptions;
import com.google.android.gms.common.api.ApiException;
import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

/**
 * Native Google sign-in — v1.0.5 THREE-step chain.
 *
 * The v1.0.4 build went straight Credential-Manager-or-WebView, and on
 * the owner's device Credential Manager threw
 * GetCredentialProviderConfigurationException (device Play Services
 * can't serve the modern Credential-Manager provider) — so the user
 * only ever saw an error toast + the slow WebView. Fixed:
 *
 *   STEP 1  Credential Manager (modern API): instant bottom-sheet
 *           picker with every Google account on the device.
 *   STEP 2  LEGACY GoogleSignIn API (play-services-auth, in the APK
 *           since v1.0.3): the classic full-screen account chooser —
 *           ALSO lists every Google account on the device and works
 *           with much older Play Services. This is the fallback that
 *           actually fires on devices where step 1 is unsupported.
 *   STEP 3  AuthActivity WebView flow (same as the website) — only if
 *           BOTH native paths are unusable (no Play Services at all).
 *
 * Both native paths mint an ID token for the school's WEB client
 * (GoogleAuth.WEB_CLIENT_ID) — the worker's /api/auth/mobile accepts
 * either, because the token format is identical.
 */
public final class NativeGoogleSignIn {

    public interface Callback {
        /** ID token acquired — hand it to MobileSession next. */
        void onToken(String idToken, String displayName);
        /** User closed the picker — stay on the login page, no fallback. */
        void onCancelled();
        /** Both native paths unusable (no Play Services / OAuth error). */
        void onUnavailable(String reason);
    }

    /** Request code for the legacy picker's startActivityForResult. */
    public static final int RC_LEGACY = 9009;

    /** The pending callback for the legacy picker (activity results
     *  have no closures — one in-flight sign-in at a time is plenty). */
    private static Callback legacyCb;

    private NativeGoogleSignIn() {}

    public static void fetch(final Activity activity, final Callback cb) {
        try {
            GetGoogleIdOption option = new GetGoogleIdOption.Builder()
                    .setServerClientId(GoogleAuth.WEB_CLIENT_ID)
                    .setFilterByAuthorizedAccounts(false)   // every device account
                    .setAutoSelectEnabled(false)            // always let them choose
                    .build();

            GetCredentialRequest request = new GetCredentialRequest.Builder()
                    .addCredentialOption(option)
                    .build();

            CredentialManager cm = CredentialManager.create(activity);

            cm.getCredentialAsync(
                    activity,
                    request,
                    null,                       // no cancellation signal
                    Runnable::run,               // main-thread executor
                    new CredentialManagerCallback<GetCredentialResponse, GetCredentialException>() {
                        @Override
                        public void onResult(GetCredentialResponse result) {
                            try {
                                Credential credential = result.getCredential();
                                Bundle data = credential != null ? credential.getData() : null;
                                if (data == null) {
                                    startLegacy(activity, cb);
                                    return;
                                }
                                GoogleIdTokenCredential gid = GoogleIdTokenCredential.createFrom(data);
                                String token = gid.getIdToken();
                                if (token == null || token.length() < 20) {
                                    startLegacy(activity, cb);
                                    return;
                                }
                                String who = gid.getDisplayName();
                                cb.onToken(token, who == null ? "" : who);
                            } catch (Throwable t) {
                                startLegacy(activity, cb);
                            }
                        }

                        @Override
                        public void onError(GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                cb.onCancelled();
                            } else {
                                // Provider unsupported / misconfigured / Play
                                // Services too old for Credential Manager —
                                // the LEGACY picker is exactly for this case.
                                startLegacy(activity, cb);
                            }
                        }
                    });
        } catch (Throwable t) {
            // Credential Manager itself blew up (no Play Services, old
            // firmware) — straight to the legacy account picker.
            startLegacy(activity, cb);
        }
    }

    /**
     * STEP 2 — the classic Google account chooser. Shows every Google
     * account signed in on the device, works on old Play Services.
     */
    private static void startLegacy(final Activity activity, final Callback cb) {
        try {
            GoogleSignInOptions gso = new GoogleSignInOptions.Builder(
                    GoogleSignInOptions.DEFAULT_SIGN_IN)
                    .requestIdToken(GoogleAuth.WEB_CLIENT_ID)
                    .requestEmail()
                    .build();
            legacyCb = cb;
            Intent picker = GoogleSignIn.getClient(activity, gso).getSignInIntent();
            activity.startActivityForResult(picker, RC_LEGACY);
            try {
                activity.overridePendingTransition(R.anim.zoom_in, R.anim.zoom_out);
            } catch (Throwable ignored) {}
        } catch (Throwable t) {
            legacyCb = null;
            cb.onUnavailable("legacy sign-in: " + t);
        }
    }

    /**
     * Route this from the host activity's onActivityResult(). Returns
     * true if the result belonged to the legacy picker.
     */
    public static boolean onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != RC_LEGACY) return false;
        final Callback cb = legacyCb;
        legacyCb = null;
        if (cb == null) return true;
        try {
            GoogleSignIn.getSignedInAccountFromIntent(data)
                    .addOnSuccessListener(Runnable::run, account -> {
                        try {
                            GoogleSignInAccount acct = (GoogleSignInAccount) account;
                            String token = acct != null ? acct.getIdToken() : null;
                            if (token != null && token.length() > 20) {
                                String who = acct.getDisplayName();
                                cb.onToken(token, who == null ? "" : who);
                            } else {
                                cb.onUnavailable("legacy: no ID token");
                            }
                        } catch (Throwable t) {
                            cb.onUnavailable("legacy result: " + t);
                        }
                    })
                    .addOnFailureListener(Runnable::run, e -> {
                        if (e instanceof ApiException) {
                            int code = ((ApiException) e).getStatusCode();
                            // 12501/12502 = user cancelled / interrupted
                            if (code == 12501 || code == 12502) {
                                cb.onCancelled();
                            } else {
                                cb.onUnavailable("sign-in error " + code);
                            }
                        } else {
                            cb.onUnavailable("legacy: " + e);
                        }
                    });
        } catch (Throwable t) {
            cb.onUnavailable("legacy result: " + t);
        }
        return true;
    }
}
