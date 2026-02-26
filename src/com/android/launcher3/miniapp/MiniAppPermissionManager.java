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

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Three-tier permission manager for mini-apps.
 *
 * Tier 1 (auto-granted): Standard web APIs — fetch, DOM storage, web audio, coarse geolocation.
 * Tier 2 (prompt-on-use): Camera, microphone, fine geolocation, contacts, push notifications,
 *                          NFC read, bluetooth, biometric.
 * Tier 3 (explicit grant): SMS send, background services, NFC write, broad file system access.
 *
 * Permissions are declared in the manifest and enforced at the bridge layer.
 * Prompt is shown at first use, not at install time.
 */
public class MiniAppPermissionManager {

    private static final String TAG = "MiniAppPermMgr";

    private static MiniAppPermissionManager sInstance;
    private final MiniAppRegistry mRegistry;

    // Tier 1: auto-granted, no prompt
    private static final Set<String> TIER1_PERMISSIONS = new HashSet<>(Arrays.asList(
            "fetch", "dom_storage", "web_audio", "geolocation.coarse"
    ));

    // Tier 2: prompt on first use
    private static final Set<String> TIER2_PERMISSIONS = new HashSet<>(Arrays.asList(
            "camera", "microphone", "geolocation.fine", "contacts.read",
            "notifications.push", "nfc.read", "bluetooth.limited", "biometric.auth",
            "clipboard.read"
    ));

    // Tier 3: explicit grant with extra warning
    private static final Set<String> TIER3_PERMISSIONS = new HashSet<>(Arrays.asList(
            "sms.send", "background.periodic_sync", "nfc.write",
            "filesystem.broad", "telephony.read"
    ));

    public static synchronized MiniAppPermissionManager getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MiniAppPermissionManager(context.getApplicationContext());
        }
        return sInstance;
    }

    private MiniAppPermissionManager(@NonNull Context context) {
        mRegistry = MiniAppRegistry.getInstance(context);
    }

    /**
     * Check if a permission is granted for a given app.
     * Tier 1 permissions always return true.
     */
    public boolean isGranted(@NonNull String appId, @NonNull String permission) {
        if (TIER1_PERMISSIONS.contains(permission)) {
            return true;
        }
        MiniAppRegistry.PermissionState state =
                mRegistry.getPermissionState(appId, permission);
        return state == MiniAppRegistry.PermissionState.GRANTED;
    }

    /**
     * Get the tier for a given permission.
     * Returns 0 if the permission is unknown.
     */
    public int getPermissionTier(@NonNull String permission) {
        if (TIER1_PERMISSIONS.contains(permission)) return 1;
        if (TIER2_PERMISSIONS.contains(permission)) return 2;
        if (TIER3_PERMISSIONS.contains(permission)) return 3;
        return 0;
    }

    /**
     * Request a permission from the user. Shows a dialog for Tier 2/3.
     * Tier 1 permissions are auto-granted without prompting.
     */
    public void requestPermission(@NonNull Activity activity, @NonNull String appId,
            @NonNull String permission, @NonNull PermissionCallback callback) {

        // Tier 1: auto-grant
        if (TIER1_PERMISSIONS.contains(permission)) {
            mRegistry.setPermissionState(appId, permission,
                    MiniAppRegistry.PermissionState.GRANTED);
            callback.onGranted();
            return;
        }

        // Check if already granted
        MiniAppRegistry.PermissionState currentState =
                mRegistry.getPermissionState(appId, permission);
        if (currentState == MiniAppRegistry.PermissionState.GRANTED) {
            callback.onGranted();
            return;
        }

        // Check if previously denied (could show "go to settings" instead)
        if (currentState == MiniAppRegistry.PermissionState.DENIED) {
            callback.onDenied();
            return;
        }

        // Show permission dialog
        showPermissionDialog(activity, appId, permission, callback);
    }

    private void showPermissionDialog(@NonNull Activity activity, @NonNull String appId,
            @NonNull String permission, @NonNull PermissionCallback callback) {

        int tier = getPermissionTier(permission);
        String friendlyName = getFriendlyPermissionName(permission);
        String appName = getAppDisplayName(appId);

        String message;
        if (tier == 3) {
            message = appName + " is requesting privileged access to: "
                    + friendlyName + ".\n\nThis is a sensitive permission. "
                    + "Only grant this if you trust this mini-app.";
        } else {
            message = appName + " wants to access: " + friendlyName;
        }

        new AlertDialog.Builder(activity)
                .setTitle("Permission Request")
                .setMessage(message)
                .setPositiveButton("Allow", (dialog, which) -> {
                    mRegistry.setPermissionState(appId, permission,
                            MiniAppRegistry.PermissionState.GRANTED);
                    Log.i(TAG, "Permission granted: " + appId + " → " + permission);
                    callback.onGranted();
                })
                .setNegativeButton("Deny", (dialog, which) -> {
                    mRegistry.setPermissionState(appId, permission,
                            MiniAppRegistry.PermissionState.DENIED);
                    Log.i(TAG, "Permission denied: " + appId + " → " + permission);
                    callback.onDenied();
                })
                .setCancelable(false)
                .show();
    }

    private String getAppDisplayName(String appId) {
        MiniAppInfo info = mRegistry.getApp(appId);
        if (info != null) {
            return info.getDisplayName();
        }
        return appId;
    }

    static String getFriendlyPermissionName(String permission) {
        switch (permission) {
            case "camera": return "Camera";
            case "microphone": return "Microphone";
            case "geolocation.fine": return "Precise Location";
            case "geolocation.coarse": return "Approximate Location";
            case "contacts.read": return "Contacts (read-only)";
            case "notifications.push": return "Notifications";
            case "nfc.read": return "NFC (read)";
            case "nfc.write": return "NFC (write)";
            case "bluetooth.limited": return "Bluetooth";
            case "biometric.auth": return "Biometric Authentication";
            case "sms.send": return "Send SMS Messages";
            case "background.periodic_sync": return "Background Sync";
            case "filesystem.broad": return "File System Access";
            case "telephony.read": return "Phone Information";
            case "clipboard.read": return "Read Clipboard";
            default: return permission;
        }
    }

    public interface PermissionCallback {
        void onGranted();
        void onDenied();
    }
}
