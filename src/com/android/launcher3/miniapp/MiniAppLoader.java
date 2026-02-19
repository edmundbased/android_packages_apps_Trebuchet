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

import android.content.Context;
import android.graphics.BitmapFactory;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.launcher3.icons.BitmapInfo;
import com.android.launcher3.model.AllAppsList;

import java.io.File;
import java.util.List;

/**
 * Loads installed mini-apps from the registry and injects them into the
 * launcher's AllAppsList, so they appear alongside native apps in the drawer.
 *
 * Called from LoaderTask.loadAllApps() after native apps and promise apps
 * have been loaded.
 */
public class MiniAppLoader {

    private static final String TAG = "MiniAppLoader";

    /**
     * Load all installed mini-apps and add them to the AllAppsList.
     * Should be called on the background loader thread.
     *
     * @param context Application context
     * @param allAppsList The all-apps list to inject mini-apps into
     */
    public static void loadMiniApps(@NonNull Context context,
            @NonNull AllAppsList allAppsList) {
        MiniAppRegistry registry = MiniAppRegistry.getInstance(context);
        List<MiniAppInfo> miniApps = registry.getAllApps();

        if (miniApps.isEmpty()) {
            Log.d(TAG, "No mini-apps installed");
            return;
        }

        // Schedule periodic update checks if we have mini-apps
        MiniAppUpdateJob.schedule(context);

        Log.i(TAG, "Loading " + miniApps.size() + " mini-app(s) into app drawer");

        for (MiniAppInfo miniApp : miniApps) {
            // Load cached icon if available
            loadIconIfNeeded(context, miniApp);

            // Compute section name for alphabetical sorting
            if (miniApp.title != null) {
                String firstChar = miniApp.title.toString().trim();
                if (!firstChar.isEmpty()) {
                    firstChar = firstChar.substring(0, 1).toUpperCase();
                    if (Character.isLetter(firstChar.charAt(0))) {
                        miniApp.sectionName = firstChar;
                    } else {
                        miniApp.sectionName = "#";
                    }
                }
            }

            // Add to the all apps list
            allAppsList.data.add(miniApp);
            Log.d(TAG, "Added mini-app to drawer: " + miniApp.appId
                    + " (" + miniApp.title + ")");
        }
    }

    /**
     * Load the cached icon bitmap for a mini-app if it exists on disk.
     */
    private static void loadIconIfNeeded(@NonNull Context context,
            @NonNull MiniAppInfo miniApp) {
        if (miniApp.bitmap != null && miniApp.bitmap != BitmapInfo.LOW_RES_INFO) {
            return; // Already loaded
        }

        File iconFile = new File(context.getCacheDir(),
                "miniapp_icons/" + sanitize(miniApp.appId) + ".png");
        if (iconFile.exists()) {
            android.graphics.Bitmap bmp = BitmapFactory.decodeFile(
                    iconFile.getAbsolutePath());
            if (bmp != null) {
                miniApp.bitmap = BitmapInfo.of(bmp, 0);
                miniApp.cachedIcon = bmp;
            }
        }

        // If still no icon, use a default
        if (miniApp.bitmap == null || miniApp.bitmap == BitmapInfo.LOW_RES_INFO) {
            miniApp.bitmap = BitmapInfo.LOW_RES_INFO;
        }
    }

    private static String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
