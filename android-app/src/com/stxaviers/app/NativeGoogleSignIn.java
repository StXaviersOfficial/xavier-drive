package com.stxaviers.app;

import android.app.Activity;
import android.os.Bundle;

import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CredentialManagerCallback;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.GetCredentialResponse;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;

/**
 * Native Google sign-in via Android Credential Manager (the modern,
 * Google-recommended path — the one Gemini's Cloud setup prepared the
 * project for: web client ID as serverClientId + the Android OAuth client
 * with this app's package and release SHA-1).
 *
 * One tap on "Continue with Google":
 *   - the system account picker opens IMMEDIATELY with every Google
 *     account saved on the device (no web page, no "connecting to Google")
 *   - the chosen account returns an ID token minted for the school's web
 *     client (GoogleAuth.WEB_CLIENT_ID)
 *   - MobileSession exchanges it for the school session in the background
 *
 * Every failure mode degrades gracefully (see Callback): cancellation
 * returns to the login page quietly; anything else falls back to the
 * AuthActivity WebView flow, so sign-in always works even on devices
 * without Play Services.
 */
public final class NativeGoogleSignIn {

    public interface Callback {
        /** ID token acquired — hand it to MobileSession next. */
        void onToken(String idToken, String displayName);
        /** User closed the picker — stay on the login page, no fallback. */
        void onCancelled();
        /** Native path unusable (no Play Services / old OS / error). */
        void onUnavailable(String reason);
    }

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
                                    cb.onUnavailable("empty credential");
                                    return;
                                }
                                GoogleIdTokenCredential gid = GoogleIdTokenCredential.createFrom(data);
                                String token = gid.getIdToken();
                                if (token == null || token.length() < 20) {
                                    cb.onUnavailable("empty token");
                                    return;
                                }
                                String who = gid.getDisplayName();
                                cb.onToken(token, who == null ? "" : who);
                            } catch (Throwable t) {
                                cb.onUnavailable("credential parse: " + t);
                            }
                        }

                        @Override
                        public void onError(GetCredentialException e) {
                            if (e instanceof GetCredentialCancellationException) {
                                cb.onCancelled();
                            } else {
                                cb.onUnavailable(String.valueOf(e));
                            }
                        }
                    });
        } catch (Throwable t) {
            // No Play Services, old firmware, anything at all — the WebView
            // flow still signs in exactly like the website does.
            cb.onUnavailable(String.valueOf(t));
        }
    }
}
