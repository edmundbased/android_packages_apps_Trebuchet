/*
 * Copyright (C) 2026 The GrandiOS Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.miniapp;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.webkit.ConsoleMessage;
import android.webkit.ServiceWorkerClient;
import android.webkit.ServiceWorkerController;
import android.webkit.WebChromeClient;
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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.R;

/**
 * Activity that hosts an isolated WebView for a single mini-app.
 * Each mini-app launch creates a new instance of this activity.
 *
 * The activity:
 * - Creates and configures a sandboxed WebView
 * - Injects the miniapp-sdk.js bridge
 * - Registers NativeBridge as a JavaScript interface
 * - Manages mini-app lifecycle (foreground/background/terminate)
 * - Shows a persistent "mini-app bar" at the top to prevent UI spoofing
 */
public class MiniAppActivity extends Activity {

    private static final String TAG = "MiniAppActivity";

    public static final String ACTION_LAUNCH_MINIAPP =
            "com.android.launcher3.miniapp.action.LAUNCH";

    public static final String EXTRA_APP_ID = "miniapp_app_id";
    public static final String EXTRA_START_URL = "miniapp_start_url";
    public static final String EXTRA_MANIFEST_URL = "miniapp_manifest_url";

    private String mAppId;
    private String mStartUrl;
    private String mManifestUrl;

    private WebView mWebView;
    private ProgressBar mProgressBar;
    private TextView mAppNameView;
    private TextView mAppDomainView;
    private ImageView mCloseButton;
    private View mMiniAppBar;
    private FrameLayout mWebViewContainer;

    private NativeBridge mNativeBridge;
    private MiniAppInfo mMiniAppInfo;
    private ConnectivityManager.NetworkCallback mNetworkCallback;
    private View mSplashView;

    // File chooser callback for <input type="file">
    private static final int FILE_CHOOSER_REQUEST_CODE = 9999;
    private android.webkit.ValueCallback<Uri[]> mFileChooserCallback;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Parse intent
        Intent intent = getIntent();
        mAppId = intent.getStringExtra(EXTRA_APP_ID);
        mStartUrl = intent.getStringExtra(EXTRA_START_URL);
        mManifestUrl = intent.getStringExtra(EXTRA_MANIFEST_URL);

        if (TextUtils.isEmpty(mAppId) || TextUtils.isEmpty(mStartUrl)) {
            Log.e(TAG, "Missing required extras (app_id, start_url)");
            finish();
            return;
        }

        // Load MiniAppInfo from registry
        MiniAppRegistry registry = MiniAppRegistry.getInstance(this);
        mMiniAppInfo = registry.getApp(mAppId);

        // Record launch
        registry.recordLaunch(mAppId);

        // Set up window
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        setupWindow();

        // Build the view hierarchy programmatically (no XML dependency)
        buildUi();

        // Set up the WebView
        setupWebView();

        // Set up service worker support
        setupServiceWorker();

        // Monitor connectivity for offline detection
        setupConnectivityMonitor();

        // Register with IPC router
        MiniAppIpcRouter.getInstance().register(mAppId, this);

        // Load the mini-app
        mWebView.loadUrl(mStartUrl);

        Log.i(TAG, "Launching mini-app: " + mAppId + " → " + mStartUrl);
    }

    private void setupWindow() {
        Window window = getWindow();
        // Apply theme color from manifest if available
        if (mMiniAppInfo != null && mMiniAppInfo.manifest != null
                && mMiniAppInfo.manifest.themeColor != null) {
            try {
                int color = Color.parseColor(mMiniAppInfo.manifest.themeColor);
                window.setStatusBarColor(color);
            } catch (IllegalArgumentException e) {
                Log.w(TAG, "Invalid theme color: " + mMiniAppInfo.manifest.themeColor);
            }
        }
    }

    private void buildUi() {
        // Root layout
        FrameLayout root = new FrameLayout(this);
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Mini-app bar (persistent, prevents UI spoofing)
        mMiniAppBar = buildMiniAppBar();

        // WebView container (below the bar)
        mWebViewContainer = new FrameLayout(this);
        FrameLayout.LayoutParams webParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        int barHeight = dpToPx(48);
        webParams.topMargin = barHeight;
        mWebViewContainer.setLayoutParams(webParams);

        // Progress bar (at top of WebView area)
        mProgressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        mProgressBar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(3)));
        mProgressBar.setMax(100);
        mProgressBar.setVisibility(View.GONE);

        // Splash overlay (shown while page loads)
        mSplashView = buildSplashView();

        root.addView(mWebViewContainer);
        root.addView(mMiniAppBar);
        root.addView(mSplashView);
        mWebViewContainer.addView(mProgressBar);

        setContentView(root);
    }

    private View buildSplashView() {
        int barHeight = dpToPx(48);

        LinearLayout splash = new LinearLayout(this);
        splash.setOrientation(LinearLayout.VERTICAL);
        splash.setGravity(Gravity.CENTER);
        FrameLayout.LayoutParams splashParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        splashParams.topMargin = barHeight;
        splash.setLayoutParams(splashParams);

        // Background color from manifest, or resolve materialColorSurface
        int bgColor = resolveColorAttr(com.android.internal.R.attr.materialColorSurface,
                0xFFFFFFFF);
        if (mMiniAppInfo != null && mMiniAppInfo.manifest != null
                && mMiniAppInfo.manifest.backgroundColor != null) {
            try {
                bgColor = Color.parseColor(mMiniAppInfo.manifest.backgroundColor);
            } catch (IllegalArgumentException ignored) {}
        }
        splash.setBackgroundColor(bgColor);

        // App icon
        if (mMiniAppInfo != null && mMiniAppInfo.cachedIcon != null) {
            ImageView icon = new ImageView(this);
            icon.setImageBitmap(mMiniAppInfo.cachedIcon);
            LinearLayout.LayoutParams iconParams =
                    new LinearLayout.LayoutParams(dpToPx(72), dpToPx(72));
            iconParams.bottomMargin = dpToPx(16);
            icon.setLayoutParams(iconParams);
            splash.addView(icon);
        }

        // App name
        String displayName = mMiniAppInfo != null
                ? mMiniAppInfo.getDisplayName() : mAppId;
        TextView nameView = new TextView(this);
        nameView.setText(displayName);
        nameView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        nameView.setTextColor(resolveColorAttr(android.R.attr.textColorPrimary, 0xFF212121));
        nameView.setGravity(Gravity.CENTER);
        splash.addView(nameView);

        // Domain
        TextView domainView = new TextView(this);
        domainView.setText(extractDomain(mStartUrl));
        domainView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        domainView.setTextColor(resolveColorAttr(android.R.attr.textColorSecondary, 0xFF757575));
        domainView.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams domainParams =
                new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        domainParams.topMargin = dpToPx(4);
        domainView.setLayoutParams(domainParams);
        splash.addView(domainView);

        // Loading spinner
        ProgressBar spinner = new ProgressBar(this);
        LinearLayout.LayoutParams spinnerParams =
                new LinearLayout.LayoutParams(dpToPx(32), dpToPx(32));
        spinnerParams.topMargin = dpToPx(24);
        spinner.setLayoutParams(spinnerParams);
        splash.addView(spinner);

        return splash;
    }

    private void dismissSplash() {
        if (mSplashView != null && mSplashView.getVisibility() == View.VISIBLE) {
            mSplashView.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> {
                        mSplashView.setVisibility(View.GONE);
                    })
                    .start();
        }
    }

    private View buildMiniAppBar() {
        int barHeight = dpToPx(48);
        int padding = dpToPx(12);

        int barBgColor = resolveColorAttr(
                com.android.internal.R.attr.materialColorSurfaceContainerLow, 0xFFF5F5F5);
        int textPrimary = resolveColorAttr(android.R.attr.textColorPrimary, 0xFF212121);
        int textSecondary = resolveColorAttr(android.R.attr.textColorSecondary, 0xFF757575);

        FrameLayout bar = new FrameLayout(this);
        bar.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, barHeight));
        bar.setBackgroundColor(barBgColor);
        bar.setPadding(padding, 0, padding, 0);
        bar.setElevation(dpToPx(2));

        // App name
        mAppNameView = new TextView(this);
        mAppNameView.setTextSize(14);
        mAppNameView.setTextColor(textPrimary);
        mAppNameView.setSingleLine(true);
        FrameLayout.LayoutParams nameParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.gravity = Gravity.CENTER_VERTICAL;
        nameParams.leftMargin = dpToPx(4);
        mAppNameView.setLayoutParams(nameParams);

        // Domain indicator
        mAppDomainView = new TextView(this);
        mAppDomainView.setTextSize(11);
        mAppDomainView.setTextColor(textSecondary);
        mAppDomainView.setSingleLine(true);
        FrameLayout.LayoutParams domainParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        domainParams.gravity = Gravity.CENTER_VERTICAL;
        domainParams.leftMargin = dpToPx(4);
        domainParams.topMargin = dpToPx(14);
        mAppDomainView.setLayoutParams(domainParams);

        // Close button
        mCloseButton = new ImageView(this);
        mCloseButton.setImageResource(android.R.drawable.ic_menu_close_clear_cancel);
        mCloseButton.setContentDescription(getString(R.string.miniapp_close));
        mCloseButton.setPadding(dpToPx(8), dpToPx(8), dpToPx(8), dpToPx(8));
        FrameLayout.LayoutParams closeParams = new FrameLayout.LayoutParams(
                dpToPx(40), dpToPx(40));
        closeParams.gravity = Gravity.CENTER_VERTICAL | Gravity.END;
        mCloseButton.setLayoutParams(closeParams);
        mCloseButton.setOnClickListener(v -> finish());

        // Set text
        String displayName = mAppId;
        if (mMiniAppInfo != null) {
            displayName = mMiniAppInfo.getDisplayName();
        }
        mAppNameView.setText(displayName);
        mAppDomainView.setText(extractDomain(mStartUrl));

        bar.addView(mAppNameView);
        bar.addView(mAppDomainView);
        bar.addView(mCloseButton);

        return bar;
    }

    @SuppressWarnings("SetJavaScriptEnabled")
    private void setupWebView() {
        mWebView = new WebView(this);
        mWebView.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // WebView settings for mini-app runtime
        WebSettings settings = mWebView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setUserAgentString(settings.getUserAgentString() + " MiniAppRuntime/1.0");

        // Set data directory suffix for process isolation
        // Each mini-app gets its own data directory
        try {
            WebView.setDataDirectorySuffix("miniapp_" + sanitizeForPath(mAppId));
        } catch (IllegalStateException e) {
            // Already set or WebView already initialized — fine for now
            Log.w(TAG, "Could not set WebView data directory suffix", e);
        }

        // Register the native bridge
        mNativeBridge = new NativeBridge(this, mAppId);
        mWebView.addJavascriptInterface(mNativeBridge, "NativeBridge");

        // WebViewClient — handle navigation, inject SDK
        mWebView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                mProgressBar.setVisibility(View.VISIBLE);
                mAppDomainView.setText(extractDomain(url));
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                mProgressBar.setVisibility(View.GONE);
                // Dismiss splash screen
                dismissSplash();
                // Inject the miniapp SDK after page loads
                injectMiniAppSdk();
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                String host = uri.getHost();
                String startHost = Uri.parse(mStartUrl).getHost();

                // Allow navigation within the same domain (or scope)
                if (host != null && host.equals(startHost)) {
                    return false; // Let WebView handle it
                }

                // External links — open in system browser
                Intent browserIntent = new Intent(Intent.ACTION_VIEW, uri);
                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(browserIntent);
                return true;
            }
        });

        // WebChromeClient — progress, console, title
        mWebView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                mProgressBar.setProgress(newProgress);
                if (newProgress >= 100) {
                    mProgressBar.setVisibility(View.GONE);
                }
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage consoleMessage) {
                Log.d(TAG, "[" + mAppId + "] " + consoleMessage.messageLevel()
                        + ": " + consoleMessage.message()
                        + " (" + consoleMessage.sourceId()
                        + ":" + consoleMessage.lineNumber() + ")");
                return true;
            }

            @Override
            public void onReceivedTitle(WebView view, String title) {
                // Don't override — keep showing manifest name in the bar
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin,
                    android.webkit.GeolocationPermissions.Callback callback) {
                // Route through mini-app permission system
                String permKey = "geolocation.fine";
                MiniAppPermissionManager pm = MiniAppPermissionManager.getInstance(
                        MiniAppActivity.this);
                pm.requestPermission(MiniAppActivity.this, mAppId, permKey,
                        new MiniAppPermissionManager.PermissionCallback() {
                            @Override
                            public void onGranted() {
                                callback.invoke(origin, true, false);
                            }

                            @Override
                            public void onDenied() {
                                callback.invoke(origin, false, false);
                            }
                        });
            }

            @Override
            public boolean onShowFileChooser(WebView webView,
                    android.webkit.ValueCallback<Uri[]> filePathCallback,
                    FileChooserParams fileChooserParams) {
                // Cancel any pending callback
                if (mFileChooserCallback != null) {
                    mFileChooserCallback.onReceiveValue(null);
                }
                mFileChooserCallback = filePathCallback;

                Intent intent = fileChooserParams.createIntent();
                try {
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST_CODE);
                } catch (android.content.ActivityNotFoundException e) {
                    mFileChooserCallback = null;
                    return false;
                }
                return true;
            }
        });

        mWebViewContainer.addView(mWebView, 0);
    }

    /**
     * Inject the miniapp-sdk.js bridge code into the WebView.
     * This provides window.miniapp with native.*, storage.*, lifecycle.*, agent.*, ipc.*
     */
    private void injectMiniAppSdk() {
        String sdk = MiniAppSdkProvider.getSdkScript();
        mWebView.evaluateJavascript(sdk, null);
        Log.d(TAG, "Injected miniapp-sdk.js for " + mAppId);
    }

    // --- Lifecycle ---

    @Override
    protected void onResume() {
        super.onResume();
        if (mWebView != null) {
            mWebView.onResume();
            mWebView.evaluateJavascript(
                    "if(window.__miniapp_lifecycle_foreground)"
                            + "window.__miniapp_lifecycle_foreground();", null);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (mWebView != null) {
            mWebView.evaluateJavascript(
                    "if(window.__miniapp_lifecycle_background)"
                            + "window.__miniapp_lifecycle_background();", null);
            mWebView.onPause();
        }
    }

    @Override
    protected void onDestroy() {
        // Unregister from IPC router
        MiniAppIpcRouter.getInstance().unregister(mAppId);

        // Unregister connectivity monitor
        if (mNetworkCallback != null) {
            ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            if (cm != null) {
                cm.unregisterNetworkCallback(mNetworkCallback);
            }
            mNetworkCallback = null;
        }

        if (mWebView != null) {
            mWebView.evaluateJavascript(
                    "if(window.__miniapp_lifecycle_terminate)"
                            + "window.__miniapp_lifecycle_terminate();", null);
            mWebViewContainer.removeView(mWebView);
            mWebView.stopLoading();
            mWebView.destroy();
            mWebView = null;
        }
        if (mNativeBridge != null) {
            mNativeBridge.destroy();
            mNativeBridge = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (mWebView != null && mWebView.canGoBack()) {
            mWebView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    // --- Activity result forwarding (for camera, file picker, etc.) ---

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // Handle <input type="file"> chooser result
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (mFileChooserCallback != null) {
                Uri[] results = null;
                if (resultCode == Activity.RESULT_OK && data != null) {
                    String dataString = data.getDataString();
                    if (dataString != null) {
                        results = new Uri[]{Uri.parse(dataString)};
                    }
                }
                mFileChooserCallback.onReceiveValue(results);
                mFileChooserCallback = null;
            }
            return;
        }

        // Forward to NativeBridge for camera, file picker, etc.
        if (mNativeBridge != null) {
            mNativeBridge.onActivityResult(requestCode, resultCode, data);
        }
    }

    // --- Service worker support ---

    private void setupServiceWorker() {
        try {
            ServiceWorkerController swController = ServiceWorkerController.getInstance();
            swController.setServiceWorkerClient(new ServiceWorkerClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(WebResourceRequest request) {
                    // Let the WebView handle service worker requests normally
                    return null;
                }
            });
            // Allow service workers to use the same cache/DB settings
            swController.getServiceWorkerWebSettings().setAllowContentAccess(false);
            swController.getServiceWorkerWebSettings().setAllowFileAccess(false);
            swController.getServiceWorkerWebSettings().setCacheMode(WebSettings.LOAD_DEFAULT);
            Log.d(TAG, "Service worker controller configured for " + mAppId);
        } catch (Exception e) {
            Log.w(TAG, "Could not configure service worker controller", e);
        }
    }

    // --- Connectivity monitoring ---

    private void setupConnectivityMonitor() {
        ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        if (cm == null) return;

        // Configure WebView cache for offline-capable apps
        boolean offlineCapable = mMiniAppInfo != null && mMiniAppInfo.offlineAvailable;
        if (offlineCapable) {
            mWebView.getSettings().setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        }

        mNetworkCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(@NonNull Network network) {
                dispatchConnectivityEvent(true);
                // Switch back to normal cache mode when online
                if (offlineCapable && mWebView != null) {
                    runOnUiThread(() ->
                            mWebView.getSettings().setCacheMode(WebSettings.LOAD_DEFAULT));
                }
            }

            @Override
            public void onLost(@NonNull Network network) {
                dispatchConnectivityEvent(false);
                // Switch to cache-only when offline
                if (offlineCapable && mWebView != null) {
                    runOnUiThread(() ->
                            mWebView.getSettings().setCacheMode(
                                    WebSettings.LOAD_CACHE_ONLY));
                }
            }
        };

        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        cm.registerNetworkCallback(request, mNetworkCallback);
    }

    private void dispatchConnectivityEvent(boolean online) {
        if (mWebView == null) return;
        String js = "if(window.__miniapp_connectivity_handler){"
                + "window.__miniapp_connectivity_handler(" + online + ");}";
        runOnUiThread(() -> mWebView.evaluateJavascript(js, null));
    }

    // --- Helpers ---

    @NonNull
    WebView getWebView() {
        return mWebView;
    }

    @NonNull
    String getAppId() {
        return mAppId;
    }

    private int resolveColorAttr(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    private static String extractDomain(String url) {
        try {
            Uri uri = Uri.parse(url);
            String host = uri.getHost();
            return host != null ? host : url;
        } catch (Exception e) {
            return url;
        }
    }

    private static String sanitizeForPath(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
