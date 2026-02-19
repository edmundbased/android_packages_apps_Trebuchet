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
import android.util.Log;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Native bridge exposed to mini-app WebViews via {@code addJavascriptInterface}.
 *
 * All methods annotated with @JavascriptInterface are callable from JavaScript
 * as {@code NativeBridge.methodName(args)}.
 *
 * Synchronous methods return JSON strings directly.
 * Asynchronous operations use a callback ID pattern:
 *   1. JS calls bridge method, passing a callbackId
 *   2. Bridge performs async work on a background thread
 *   3. Bridge calls back into JS via evaluateJavascript with the callbackId and result
 */
public class NativeBridge {

    private static final String TAG = "NativeBridge";

    private final MiniAppActivity mActivity;
    private final String mAppId;
    private final MiniAppRegistry mRegistry;
    private final MiniAppPermissionManager mPermissionManager;
    private final BridgeRateLimiter mRateLimiter;

    // Pending async callbacks (for camera, biometric, etc.)
    private final AtomicInteger mCallbackCounter = new AtomicInteger(0);
    private final Map<Integer, PendingCallback> mPendingCallbacks = new ConcurrentHashMap<>();

    // Activity result tracking
    private static final int REQUEST_CODE_BASE = 10000;
    private final AtomicInteger mRequestCodeCounter = new AtomicInteger(REQUEST_CODE_BASE);

    public NativeBridge(@NonNull MiniAppActivity activity, @NonNull String appId) {
        mActivity = activity;
        mAppId = appId;
        mRegistry = MiniAppRegistry.getInstance(activity);
        mPermissionManager = MiniAppPermissionManager.getInstance(activity);
        mRateLimiter = new BridgeRateLimiter();
    }

    // --- Async callback infrastructure ---

    /**
     * Resolve an async callback with a success result.
     */
    private void resolveCallback(int callbackId, String jsonResult) {
        mActivity.runOnUiThread(() -> {
            String js = "if(window.__miniapp_callbacks && window.__miniapp_callbacks["
                    + callbackId + "]){window.__miniapp_callbacks[" + callbackId
                    + "].resolve(" + jsonResult + ");delete window.__miniapp_callbacks["
                    + callbackId + "];}";
            mActivity.getWebView().evaluateJavascript(js, null);
        });
    }

    /**
     * Reject an async callback with an error.
     */
    private void rejectCallback(int callbackId, String errorCode, String message) {
        mActivity.runOnUiThread(() -> {
            String errorJson = "{\"error\":\"" + escapeJs(errorCode)
                    + "\",\"message\":\"" + escapeJs(message) + "\"}";
            String js = "if(window.__miniapp_callbacks && window.__miniapp_callbacks["
                    + callbackId + "]){window.__miniapp_callbacks[" + callbackId
                    + "].reject(" + errorJson + ");delete window.__miniapp_callbacks["
                    + callbackId + "];}";
            mActivity.getWebView().evaluateJavascript(js, null);
        });
    }

    // --- Storage API (Tier 1 — auto-granted) ---

    @JavascriptInterface
    public String storageGet(@NonNull String key) {
        return MiniAppStorage.getInstance(mActivity).get(mAppId, key);
    }

    @JavascriptInterface
    public void storageSet(@NonNull String key, @NonNull String valueJson) {
        MiniAppStorage.getInstance(mActivity).set(mAppId, key, valueJson);
    }

    @JavascriptInterface
    public void storageDelete(@NonNull String key) {
        MiniAppStorage.getInstance(mActivity).delete(mAppId, key);
    }

    // --- Permission request ---

    /**
     * Request a native permission. Returns JSON with the result.
     * Called from JS: NativeBridge.requestPermission("camera", callbackId)
     */
    @JavascriptInterface
    public void requestPermission(@NonNull String permission, int callbackId) {
        mActivity.runOnUiThread(() -> {
            mPermissionManager.requestPermission(mActivity, mAppId, permission,
                    new MiniAppPermissionManager.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            resolveCallback(callbackId,
                                    "{\"status\":\"granted\",\"permission\":\""
                                            + escapeJs(permission) + "\"}");
                        }

                        @Override
                        public void onDenied() {
                            rejectCallback(callbackId, "PERMISSION_DENIED",
                                    permission + " access denied by user");
                        }
                    });
        });
    }

    /**
     * Check if a permission is currently granted (sync).
     */
    @JavascriptInterface
    public String checkPermission(@NonNull String permission) {
        MiniAppRegistry.PermissionState state =
                mRegistry.getPermissionState(mAppId, permission);
        try {
            JSONObject result = new JSONObject();
            result.put("permission", permission);
            result.put("state", state.value);
            return result.toString();
        } catch (JSONException e) {
            return errorJson("INTERNAL_ERROR", "Failed to check permission");
        }
    }

    // --- Camera API (Tier 2 — prompt-on-use) ---

    @JavascriptInterface
    public void requestCamera(@NonNull String optionsJson, int callbackId) {
        if (!checkPermissionSync("camera")) {
            // Need to request permission first
            mActivity.runOnUiThread(() -> {
                mPermissionManager.requestPermission(mActivity, mAppId, "camera",
                        new MiniAppPermissionManager.PermissionCallback() {
                            @Override
                            public void onGranted() {
                                launchCamera(optionsJson, callbackId);
                            }

                            @Override
                            public void onDenied() {
                                rejectCallback(callbackId, "PERMISSION_DENIED",
                                        "Camera access not granted");
                            }
                        });
            });
            return;
        }
        launchCamera(optionsJson, callbackId);
    }

    private void launchCamera(String optionsJson, int callbackId) {
        int requestCode = mRequestCodeCounter.getAndIncrement();
        mPendingCallbacks.put(requestCode, new PendingCallback(callbackId, "camera"));

        mActivity.runOnUiThread(() -> {
            Intent intent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
            if (intent.resolveActivity(mActivity.getPackageManager()) != null) {
                mActivity.startActivityForResult(intent, requestCode);
            } else {
                rejectCallback(callbackId, "UNAVAILABLE", "Camera not available");
                mPendingCallbacks.remove(requestCode);
            }
        });
    }

    // --- Notifications API (Tier 2 — prompt-on-use) ---

    @JavascriptInterface
    public void sendNotification(@NonNull String title, @NonNull String body,
            @NonNull String channelId, int callbackId) {
        if (!checkRateLimit("sendNotification", callbackId)) return;
        if (!checkPermissionSync("notifications.push")) {
            mActivity.runOnUiThread(() -> {
                mPermissionManager.requestPermission(mActivity, mAppId, "notifications.push",
                        new MiniAppPermissionManager.PermissionCallback() {
                            @Override
                            public void onGranted() {
                                doSendNotification(title, body, channelId, callbackId);
                            }

                            @Override
                            public void onDenied() {
                                rejectCallback(callbackId, "PERMISSION_DENIED",
                                        "Notification access not granted");
                            }
                        });
            });
            return;
        }
        doSendNotification(title, body, channelId, callbackId);
    }

    private void doSendNotification(String title, String body, String channelId,
            int callbackId) {
        MiniAppNotificationHelper.show(mActivity, mAppId, title, body, channelId);
        resolveCallback(callbackId, "{\"status\":\"sent\"}");
    }

    // --- NFC API (Tier 2 read, Tier 3 write) ---

    @JavascriptInterface
    public void readNFC(int callbackId) {
        requestAndDo("nfc.read", callbackId, () -> {
            android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(mActivity);
            if (nfc == null || !nfc.isEnabled()) {
                rejectCallback(callbackId, "UNAVAILABLE", "NFC is not available or disabled");
                return;
            }
            // Register pending NFC read — actual tag data comes via onNewIntent
            int requestCode = mRequestCodeCounter.getAndIncrement();
            mPendingCallbacks.put(requestCode, new PendingCallback(callbackId, "nfc_read"));
            // Enable foreground dispatch handled by MiniAppActivity
            resolveCallback(callbackId,
                    "{\"status\":\"listening\",\"message\":\"Hold device near NFC tag\"}");
        });
    }

    @JavascriptInterface
    public void writeNFC(@NonNull String dataJson, int callbackId) {
        requestAndDo("nfc.write", callbackId, () -> {
            android.nfc.NfcAdapter nfc = android.nfc.NfcAdapter.getDefaultAdapter(mActivity);
            if (nfc == null || !nfc.isEnabled()) {
                rejectCallback(callbackId, "UNAVAILABLE", "NFC is not available or disabled");
                return;
            }
            // NFC write requires tag proximity — store pending write data
            int requestCode = mRequestCodeCounter.getAndIncrement();
            mPendingCallbacks.put(requestCode, new PendingCallback(callbackId, "nfc_write"));
            resolveCallback(callbackId,
                    "{\"status\":\"ready\",\"message\":\"Hold device near NFC tag to write\"}");
        });
    }

    // --- Biometric API (Tier 2) ---

    @JavascriptInterface
    public void authenticateBiometric(@NonNull String reason, int callbackId) {
        requestAndDo("biometric.auth", callbackId, () -> {
            mActivity.runOnUiThread(() -> {
                try {
                    android.hardware.biometrics.BiometricPrompt.Builder builder =
                            new android.hardware.biometrics.BiometricPrompt.Builder(mActivity);
                    builder.setTitle("Authentication Required");
                    builder.setDescription(reason);
                    builder.setNegativeButton("Cancel",
                            mActivity.getMainExecutor(),
                            (dialog, which) -> rejectCallback(callbackId,
                                    "CANCELLED", "User cancelled biometric"));
                    builder.setAllowedAuthenticators(
                            android.hardware.biometrics.BiometricManager
                                    .Authenticators.BIOMETRIC_STRONG
                            | android.hardware.biometrics.BiometricManager
                                    .Authenticators.DEVICE_CREDENTIAL);
                    android.hardware.biometrics.BiometricPrompt prompt = builder.build();
                    prompt.authenticate(
                            new android.os.CancellationSignal(),
                            mActivity.getMainExecutor(),
                            new android.hardware.biometrics.BiometricPrompt
                                    .AuthenticationCallback() {
                                @Override
                                public void onAuthenticationSucceeded(
                                        android.hardware.biometrics.BiometricPrompt
                                                .AuthenticationResult result) {
                                    resolveCallback(callbackId,
                                            "{\"status\":\"authenticated\"}");
                                }

                                @Override
                                public void onAuthenticationFailed() {
                                    rejectCallback(callbackId,
                                            "AUTH_FAILED", "Biometric not recognized");
                                }

                                @Override
                                public void onAuthenticationError(
                                        int errorCode, CharSequence errString) {
                                    rejectCallback(callbackId,
                                            "AUTH_ERROR", errString.toString());
                                }
                            });
                } catch (Exception e) {
                    rejectCallback(callbackId, "UNAVAILABLE",
                            "Biometric authentication not available: " + e.getMessage());
                }
            });
        });
    }

    // --- Contacts API (Tier 2, read-only) ---

    @JavascriptInterface
    public void readContacts(@NonNull String query, int callbackId) {
        if (!checkRateLimit("readContacts", callbackId)) return;
        requestAndDo("contacts.read", callbackId, () -> {
            try {
                org.json.JSONArray results = new org.json.JSONArray();
                String selection = android.provider.ContactsContract.Contacts.DISPLAY_NAME
                        + " LIKE ?";
                String[] args = new String[]{"%" + query + "%"};
                android.database.Cursor cursor = mActivity.getContentResolver().query(
                        android.provider.ContactsContract.Contacts.CONTENT_URI,
                        new String[]{
                                android.provider.ContactsContract.Contacts._ID,
                                android.provider.ContactsContract.Contacts.DISPLAY_NAME,
                                android.provider.ContactsContract.Contacts.HAS_PHONE_NUMBER
                        },
                        selection, args,
                        android.provider.ContactsContract.Contacts.DISPLAY_NAME + " ASC");

                if (cursor != null) {
                    int limit = 50; // Safety limit
                    while (cursor.moveToNext() && limit-- > 0) {
                        JSONObject contact = new JSONObject();
                        contact.put("id", cursor.getString(0));
                        contact.put("name", cursor.getString(1));
                        contact.put("hasPhone", cursor.getInt(2) > 0);
                        results.put(contact);
                    }
                    cursor.close();
                }
                resolveCallback(callbackId, results.toString());
            } catch (Exception e) {
                rejectCallback(callbackId, "ERROR",
                        "Failed to read contacts: " + e.getMessage());
            }
        });
    }

    // --- SMS API (Tier 3 — privileged) ---

    @JavascriptInterface
    public void sendSMS(@NonNull String number, @NonNull String body, int callbackId) {
        if (!checkRateLimit("sendSMS", callbackId)) return;
        requestAndDo("sms.send", callbackId, () -> {
            try {
                android.telephony.SmsManager sms =
                        android.telephony.SmsManager.getDefault();
                sms.sendTextMessage(number, null, body, null, null);
                resolveCallback(callbackId,
                        "{\"status\":\"sent\",\"number\":\"" + escapeJs(number) + "\"}");
            } catch (Exception e) {
                rejectCallback(callbackId, "SEND_FAILED",
                        "Failed to send SMS: " + e.getMessage());
            }
        });
    }

    // --- Telephony API (Tier 3) ---

    @JavascriptInterface
    public String getCarrierInfo(int callbackId) {
        if (!checkPermissionSync("telephony.read")) {
            return errorJson("PERMISSION_DENIED", "Telephony access not granted");
        }
        try {
            android.telephony.TelephonyManager tm = mActivity.getSystemService(
                    android.telephony.TelephonyManager.class);
            JSONObject result = new JSONObject();
            result.put("carrier", tm != null ? tm.getNetworkOperatorName() : "unknown");
            result.put("countryIso", tm != null ? tm.getNetworkCountryIso() : "");
            return result.toString();
        } catch (Exception e) {
            return errorJson("ERROR", "Failed to get carrier info");
        }
    }

    // --- Sensor API (Tier 1 — auto-granted) ---

    private android.hardware.SensorEventListener mAccelListener;
    private android.hardware.SensorEventListener mGyroListener;

    @JavascriptInterface
    public void subscribeSensor(@NonNull String type, int callbackId) {
        android.hardware.SensorManager sm = mActivity.getSystemService(
                android.hardware.SensorManager.class);
        if (sm == null) {
            rejectCallback(callbackId, "UNAVAILABLE", "Sensors not available");
            return;
        }

        int sensorType;
        switch (type) {
            case "accel":
                sensorType = android.hardware.Sensor.TYPE_ACCELEROMETER;
                break;
            case "gyro":
                sensorType = android.hardware.Sensor.TYPE_GYROSCOPE;
                break;
            default:
                rejectCallback(callbackId, "UNKNOWN_SENSOR", "Unknown sensor: " + type);
                return;
        }

        android.hardware.Sensor sensor = sm.getDefaultSensor(sensorType);
        if (sensor == null) {
            rejectCallback(callbackId, "UNAVAILABLE", type + " sensor not available");
            return;
        }

        android.hardware.SensorEventListener listener =
                new android.hardware.SensorEventListener() {
            @Override
            public void onSensorChanged(android.hardware.SensorEvent event) {
                String json = "{\"x\":" + event.values[0]
                        + ",\"y\":" + event.values[1]
                        + ",\"z\":" + event.values[2]
                        + ",\"timestamp\":" + event.timestamp + "}";
                mActivity.runOnUiThread(() ->
                        mActivity.getWebView().evaluateJavascript(
                                "if(window.__miniapp_sensor_" + type + ")"
                                        + "window.__miniapp_sensor_" + type + "(" + json + ");",
                                null));
            }

            @Override
            public void onAccuracyChanged(android.hardware.Sensor s, int accuracy) {}
        };

        sm.registerListener(listener, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI);

        if ("accel".equals(type)) {
            mAccelListener = listener;
        } else {
            mGyroListener = listener;
        }

        resolveCallback(callbackId, "{\"status\":\"subscribed\",\"sensor\":\"" + type + "\"}");
    }

    @JavascriptInterface
    public void unsubscribeSensor(@NonNull String type) {
        android.hardware.SensorManager sm = mActivity.getSystemService(
                android.hardware.SensorManager.class);
        if (sm == null) return;

        android.hardware.SensorEventListener listener =
                "accel".equals(type) ? mAccelListener : mGyroListener;
        if (listener != null) {
            sm.unregisterListener(listener);
            if ("accel".equals(type)) mAccelListener = null;
            else mGyroListener = null;
        }
    }

    // --- Geolocation API (Tier 2) ---

    @JavascriptInterface
    public void getCurrentPosition(int callbackId) {
        requestAndDo("geolocation.fine", callbackId, () -> {
            android.location.LocationManager lm = mActivity.getSystemService(
                    android.location.LocationManager.class);
            if (lm == null) {
                rejectCallback(callbackId, "UNAVAILABLE", "Location services not available");
                return;
            }

            // Try GPS first, fall back to network
            String provider = lm.isProviderEnabled(
                    android.location.LocationManager.GPS_PROVIDER)
                    ? android.location.LocationManager.GPS_PROVIDER
                    : android.location.LocationManager.NETWORK_PROVIDER;

            try {
                android.location.Location last = lm.getLastKnownLocation(provider);
                if (last != null) {
                    resolveLocationCallback(callbackId, last);
                    return;
                }

                // Request a fresh fix
                lm.requestSingleUpdate(provider,
                        new android.location.LocationListener() {
                            @Override
                            public void onLocationChanged(android.location.Location loc) {
                                resolveLocationCallback(callbackId, loc);
                            }

                            @Override
                            public void onProviderDisabled(String p) {
                                rejectCallback(callbackId, "DISABLED",
                                        "Location provider disabled");
                            }

                            @Override
                            public void onProviderEnabled(String p) {}

                            @Override
                            public void onStatusChanged(String p, int s,
                                    android.os.Bundle e) {}
                        }, mActivity.getMainLooper());
            } catch (SecurityException e) {
                rejectCallback(callbackId, "PERMISSION_DENIED",
                        "Location permission not granted at OS level");
            }
        });
    }

    private void resolveLocationCallback(int callbackId, android.location.Location loc) {
        try {
            JSONObject result = new JSONObject();
            result.put("latitude", loc.getLatitude());
            result.put("longitude", loc.getLongitude());
            result.put("accuracy", loc.getAccuracy());
            result.put("altitude", loc.getAltitude());
            result.put("speed", loc.getSpeed());
            result.put("timestamp", loc.getTime());
            resolveCallback(callbackId, result.toString());
        } catch (JSONException e) {
            resolveCallback(callbackId, "{\"latitude\":0,\"longitude\":0}");
        }
    }

    // --- File Picker API (Tier 2) ---

    @JavascriptInterface
    public void pickFile(@NonNull String mimeType, int callbackId) {
        requestAndDo("filesystem.broad", callbackId, () -> {
            int requestCode = mRequestCodeCounter.getAndIncrement();
            mPendingCallbacks.put(requestCode,
                    new PendingCallback(callbackId, "file_pick"));

            mActivity.runOnUiThread(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(mimeType != null && !mimeType.isEmpty()
                        ? mimeType : "*/*");
                try {
                    mActivity.startActivityForResult(intent, requestCode);
                } catch (android.content.ActivityNotFoundException e) {
                    mPendingCallbacks.remove(requestCode);
                    rejectCallback(callbackId, "UNAVAILABLE",
                            "No file picker available");
                }
            });
        });
    }

    // --- Clipboard API ---

    @JavascriptInterface
    public void clipboardWrite(@NonNull String text) {
        // Tier 1 — auto-granted (writing to clipboard is low-risk)
        mActivity.runOnUiThread(() -> {
            android.content.ClipboardManager cm = mActivity.getSystemService(
                    android.content.ClipboardManager.class);
            if (cm != null) {
                cm.setPrimaryClip(android.content.ClipData.newPlainText("miniapp", text));
            }
        });
    }

    @JavascriptInterface
    public void clipboardRead(int callbackId) {
        if (!checkRateLimit("clipboardRead", callbackId)) return;
        requestAndDo("clipboard.read", callbackId, () -> {
            mActivity.runOnUiThread(() -> {
                android.content.ClipboardManager cm = mActivity.getSystemService(
                        android.content.ClipboardManager.class);
                if (cm != null && cm.hasPrimaryClip()) {
                    android.content.ClipData clip = cm.getPrimaryClip();
                    if (clip != null && clip.getItemCount() > 0) {
                        CharSequence text = clip.getItemAt(0).getText();
                        resolveCallback(callbackId,
                                "{\"text\":\"" + escapeJs(
                                        text != null ? text.toString() : "") + "\"}");
                        return;
                    }
                }
                resolveCallback(callbackId, "{\"text\":null}");
            });
        });
    }

    // --- Agent API ---

    @JavascriptInterface
    public void agentEvent(@NonNull String eventType, @NonNull String dataJson) {
        Log.d(TAG, "[" + mAppId + "] Agent event: " + eventType + " → " + dataJson);
        // TODO: Forward to AgentCapabilityRegistry when Phase 4 is implemented
    }

    // --- IPC API ---

    @JavascriptInterface
    public String ipcSend(@NonNull String targetApp, @NonNull String action,
            @NonNull String dataJson) {
        MiniAppIpcRouter router = MiniAppIpcRouter.getInstance();
        if (!router.isRunning(targetApp)) {
            return errorJson("TARGET_NOT_RUNNING",
                    "Target app is not currently running: " + targetApp);
        }
        boolean sent = router.sendMessage(mAppId, targetApp, action, dataJson);
        if (sent) {
            return "{\"status\":\"sent\",\"target\":\"" + escapeJs(targetApp) + "\"}";
        }
        return errorJson("SEND_FAILED", "Failed to deliver message to " + targetApp);
    }

    // --- Activity result handling ---

    void onActivityResult(int requestCode, int resultCode, Intent data) {
        PendingCallback pending = mPendingCallbacks.remove(requestCode);
        if (pending == null) return;

        if (resultCode == Activity.RESULT_OK) {
            switch (pending.type) {
                case "camera":
                    String resultJson = "{}";
                    if (data != null && data.getExtras() != null) {
                        resultJson = "{\"status\":\"captured\",\"hasData\":true}";
                    }
                    resolveCallback(pending.callbackId, resultJson);
                    break;
                case "file_pick":
                    if (data != null && data.getData() != null) {
                        android.net.Uri uri = data.getData();
                        String name = getFileName(uri);
                        String type = mActivity.getContentResolver().getType(uri);
                        long size = getFileSize(uri);
                        resolveCallback(pending.callbackId,
                                "{\"uri\":\"" + escapeJs(uri.toString()) + "\""
                                + ",\"name\":\"" + escapeJs(name) + "\""
                                + ",\"type\":\"" + escapeJs(type != null ? type : "") + "\""
                                + ",\"size\":" + size + "}");
                    } else {
                        rejectCallback(pending.callbackId, "NO_FILE", "No file selected");
                    }
                    break;
                default:
                    resolveCallback(pending.callbackId, "{\"status\":\"ok\"}");
                    break;
            }
        } else {
            rejectCallback(pending.callbackId, "CANCELLED", "User cancelled the operation");
        }
    }

    // --- File helpers ---

    private String getFileName(android.net.Uri uri) {
        String name = "unknown";
        try (android.database.Cursor c = mActivity.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                name = c.getString(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get file name", e);
        }
        return name;
    }

    private long getFileSize(android.net.Uri uri) {
        try (android.database.Cursor c = mActivity.getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.SIZE},
                null, null, null)) {
            if (c != null && c.moveToFirst()) {
                return c.getLong(0);
            }
        } catch (Exception e) {
            Log.w(TAG, "Could not get file size", e);
        }
        return -1;
    }

    // --- Lifecycle ---

    void destroy() {
        mPendingCallbacks.clear();
    }

    // --- Helpers ---

    /**
     * Check rate limit and reject with RATE_LIMITED if exceeded.
     * Returns true if the call is allowed, false if rejected.
     */
    private boolean checkRateLimit(String method, int callbackId) {
        if (!mRateLimiter.allowCall(mAppId, method)) {
            rejectCallback(callbackId, "RATE_LIMITED",
                    "Too many " + method + " calls. Try again later.");
            return false;
        }
        return true;
    }

    private boolean checkPermissionSync(String permission) {
        MiniAppRegistry.PermissionState state =
                mRegistry.getPermissionState(mAppId, permission);
        return state == MiniAppRegistry.PermissionState.GRANTED;
    }

    /**
     * Check permission and execute action if granted.
     * If not yet granted, prompts the user first.
     */
    private void requestAndDo(String permission, int callbackId, Runnable action) {
        if (checkPermissionSync(permission)) {
            action.run();
            return;
        }
        mActivity.runOnUiThread(() -> {
            mPermissionManager.requestPermission(mActivity, mAppId, permission,
                    new MiniAppPermissionManager.PermissionCallback() {
                        @Override
                        public void onGranted() {
                            action.run();
                        }

                        @Override
                        public void onDenied() {
                            rejectCallback(callbackId, "PERMISSION_DENIED",
                                    permission + " access denied by user");
                        }
                    });
        });
    }

    static String errorJson(String code, String message) {
        return "{\"error\":\"" + escapeJs(code)
                + "\",\"message\":\"" + escapeJs(message) + "\"}";
    }

    private static String escapeJs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    private static class PendingCallback {
        final int callbackId;
        final String type;

        PendingCallback(int callbackId, String type) {
            this.callbackId = callbackId;
            this.type = type;
        }
    }
}
