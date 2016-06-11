package com.pierfrancescosoffritti.webbasedoauth;

import android.annotation.SuppressLint;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.support.annotation.Nullable;
import android.support.v7.widget.LinearLayoutCompat;
import android.util.Log;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.concurrent.Semaphore;

/**
 * This class represents the entry point for the OAuth process.
 * Each {@link Authenticator} is associated with, at most, one user.
 * <br/><br/>
 * Use the {@link #getAccessToken()} method to get an access token.
 * <br/><br/>
 * Use the {@link #logout()} method to delete the user information.
 * <br/><br/>
 * Use the {@link #handleException(Exception)} method each time you make an API call using the access token.
 * Is necessary to handle the case in which the users revokes the access while an access token is still valid.
 */
public class Authenticator  {

    private Context context;

    private String OAuthURL;
    private String scopes;
    private String redirectURL;
    private String responseType;
    private String clientID;
    private String accessType;

    private String tokenURL;
    private String clientSecret;

    private AuthenticatedUser authenticatedUser;

    private final Semaphore available = new Semaphore(0, true);

    public Authenticator(Context context,
                         String OAuthURL, String[] scopes, String redirectURL, String responseType, String clientID, String accessType,
                         String tokenURL, String clientSecret) {
        this.context = context;

        this.OAuthURL = OAuthURL;
        this.scopes = buildScopesString(scopes);
        this.redirectURL = redirectURL;
        this.responseType = responseType;
        this.clientID = clientID;
        this.accessType = accessType;

        this.tokenURL = tokenURL;
        this.clientSecret = clientSecret;

        this.authenticatedUser = new AuthenticatedUser(new SharedPreferencesAuthenticatedUserPersister(context));
    }

    private String buildScopesString(String[] scopes) {
        StringBuilder scopesSB = new StringBuilder();
        for(int i=0; i<scopes.length; i++) {
            if(scopes.length == 1 ||  i == scopes.length-1)
                scopesSB.append(scopes[i]);
            else
                scopesSB.append(scopes[i]).append("%20");
        }

        return scopesSB.toString();
    }

    /**
     * Use this method to get an access token.
     * <br/><br/>
     * First of all this method checks the authentication status and: <br/>
     * <ol>
     * <li>If the user isn't authenticated: starts the auth process</li>
     * <li>If the user is authenticated, but the access token has expired: starts the refresh process</li>
     * <li>If the user is authenticated and the token hasn't expired: returns the token</li>
     * </ol>
     *
     * <br/>
     * This method is thread safe. One thread at a time can call it.
     * If the user isn't authenticated or the access token is expired, the thread is blocked and point 1. or 2. are executed.
     * Only when 1. or 2. terminate a new thread can call this method.
     * <br/>
     * This method block the calling thread. <b>Don't call it from the main thread.</b>
     * <br/><br/>
     * @return
     * <li>a valid access token if case 3. or if 1. or 2. are executed and have terminated successfully</li>
     * <li>null if 1. or 2. are executed and have terminated unsuccessfully</li>
     *
     * @throws InterruptedException see {@link Semaphore#acquire()}
     */
    public synchronized @Nullable
    String getAccessToken() throws InterruptedException {
        @AuthenticatedUser.AuthStatus int status = authenticatedUser.getAuthStatus();
        switch (status) {
            case AuthenticatedUser.NOT_AUTHENTICATED:
                authenticate();
                available.acquire();
                break;
            case AuthenticatedUser.TOKEN_EXPIRED:
                refreshToken();
                available.acquire();
                break;
            case AuthenticatedUser.AUTHENTICATED:
                break;
        }

        return authenticatedUser.getAccessToken();
    }

    /**
     * Delete the current {@link AuthenticatedUser}.
     * <br/>
     * The user info is removed both from memory and from the persistent location.
     */
    public void logout() {
        authenticatedUser.remove();
    }

    /* TODO must find a better solution to this problem. The only way to find out if the user has revoked access to the app (while the access token is valid) is by making an http request. But I can't make an http request every time getAccessToken is called.
    /**
     * Call this method for every API call you make using the access token.
     * <br/>
     * If the server responds with a 401 error code, the API call was not authenticated.
     * <br/>
     * With this class the only scenario in which that may happen, is when the user revokes the access to the client app while the current access token is still valid.
     */
    public void handleException(Exception e)  {
        if(e.getMessage().contains("401")) {
            Log.e(Authenticator.class.getSimpleName(), "401 Unauthorized, probably the user has revoked the authorization");
            authenticatedUser.remove();
        }
    }

    /**
     * Start the authentication process.
     */
    private void authenticate() {
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                showDialog(authenticatedUser);
            }
        });
    }

    /**
     * Start the refresh token process.
     */
    private void refreshToken() {
        new RefreshTokenTask(this, authenticatedUser).execute(tokenURL, clientID, clientSecret, "refresh_token");
    }

    /**
     * Show a WebView in a dialog, for the web-based OAuth authentication.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private void showDialog(final AuthenticatedUser authenticatedUser) {
        final Dialog authDialog = new Dialog(context);
        authDialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        // build the layout for the dialog
        LinearLayoutCompat root = new LinearLayoutCompat(authDialog.getContext());
        WebView webView = new WebView(authDialog.getContext());
        webView.setLayoutParams(new LinearLayoutCompat.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(webView);

        authDialog.setContentView(root);

        // must unlock the blocked thread if the dialog is closed
        authDialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                unlock();
            }
        });

        webView.getSettings().setJavaScriptEnabled(true);

        webView.loadUrl(
                OAuthURL +"?" +
                        "redirect_uri=" +redirectURL +
                        "&response_type=" +responseType +
                        "&client_id=" +clientID +
                        "&scope=" +scopes +
                        "&access_type=" +accessType
        );

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon){
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (url.contains("?code=")) {

                    // Now the blocked thread must be unlocked the GetTokensTask
                    authDialog.setOnDismissListener(null);

                    String authorizationCode = Uri.parse(url).getQueryParameter("code");

                    new GetTokensTask(context, Authenticator.this, authenticatedUser).execute(tokenURL, authorizationCode, clientID, clientSecret, redirectURL, "authorization_code");

                    authDialog.dismiss();
                } else if(url.contains("error=access_denied"))
                    authDialog.dismiss();
            }
        });

        authDialog.setCancelable(true);
        authDialog.show();
    }

    /**
     * this class is thread safe, blocked threads must be unlocked when {@link GetTokensTask} and {@link RefreshTokenTask} terminate.
     */
    protected void unlock() {
        available.release();
    }
}