/*
 * Copyright (C) 2026 The BasedOS Project
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

import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Routes IPC messages between running mini-app instances.
 *
 * Each MiniAppActivity registers itself on creation and unregisters on destroy.
 * Messages are delivered via evaluateJavascript into the target app's WebView.
 */
public final class MiniAppIpcRouter {

    private static final String TAG = "MiniAppIpcRouter";

    private static final MiniAppIpcRouter sInstance = new MiniAppIpcRouter();

    // Running mini-app activities, keyed by appId
    private final Map<String, MiniAppActivity> mRunningApps = new ConcurrentHashMap<>();

    private MiniAppIpcRouter() {}

    public static MiniAppIpcRouter getInstance() {
        return sInstance;
    }

    /**
     * Register a running mini-app activity for IPC.
     */
    void register(@NonNull String appId, @NonNull MiniAppActivity activity) {
        mRunningApps.put(appId, activity);
        Log.d(TAG, "Registered IPC endpoint: " + appId);
    }

    /**
     * Unregister a mini-app activity (typically on destroy).
     */
    void unregister(@NonNull String appId) {
        mRunningApps.remove(appId);
        Log.d(TAG, "Unregistered IPC endpoint: " + appId);
    }

    /**
     * Send a message from one mini-app to another.
     *
     * @param sourceAppId the sender's app ID
     * @param targetAppId the recipient's app ID
     * @param action      a string action/topic name
     * @param dataJson    JSON-encoded payload
     * @return true if delivered, false if target not running
     */
    public boolean sendMessage(@NonNull String sourceAppId, @NonNull String targetAppId,
            @NonNull String action, @NonNull String dataJson) {
        MiniAppActivity target = mRunningApps.get(targetAppId);
        if (target == null) {
            Log.w(TAG, "IPC target not running: " + targetAppId);
            return false;
        }

        WebView webView = target.getWebView();
        if (webView == null) {
            return false;
        }

        // Deliver via the __miniapp_ipc_handler callback
        String escapedSource = escapeJs(sourceAppId);
        String escapedAction = escapeJs(action);
        String js = "if(window.__miniapp_ipc_handler){"
                + "window.__miniapp_ipc_handler({"
                + "source:\"" + escapedSource + "\","
                + "action:\"" + escapedAction + "\","
                + "data:" + dataJson
                + "});}";

        target.runOnUiThread(() -> webView.evaluateJavascript(js, null));
        Log.d(TAG, "IPC: " + sourceAppId + " → " + targetAppId + " [" + action + "]");
        return true;
    }

    /**
     * Check if a target app is currently running and reachable.
     */
    public boolean isRunning(@NonNull String appId) {
        return mRunningApps.containsKey(appId);
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
