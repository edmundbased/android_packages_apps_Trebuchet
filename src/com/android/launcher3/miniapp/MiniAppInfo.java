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

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.LauncherSettings;
import com.android.launcher3.model.data.AppInfo;
import com.android.launcher3.model.data.WorkspaceItemInfo;

/**
 * Represents an installed mini-app in the launcher.
 * Extends AppInfo so it integrates seamlessly into the app drawer and workspace
 * alongside native apps — including sorting, sectioning, and click handling.
 *
 * The synthetic ComponentName uses package "com.grandios.miniapp" with the
 * appId as the class name, ensuring unique identity in the launcher model.
 */
public class MiniAppInfo extends AppInfo {

    /** Synthetic package name used for all mini-app ComponentNames. */
    public static final String MINIAPP_PACKAGE = "com.grandios.miniapp";

    /** Custom item type constant for mini-apps. */
    public static final int ITEM_TYPE_MINIAPP = 100;

    /** Unique app ID derived from manifest start_url (e.g. "quickpay.app/mini"). */
    @NonNull
    public String appId = "";

    /** URL of the manifest JSON. */
    @NonNull
    public String manifestUrl = "";

    /** The start_url from the manifest (the actual app entry point). */
    @NonNull
    public String startUrl = "";

    /** Parsed manifest (may be null if not yet loaded). */
    @Nullable
    public MiniAppManifest manifest;

    /** Cached icon bitmap (loaded from manifest icon URL). */
    @Nullable
    public Bitmap cachedIcon;

    /** Timestamp of last manifest fetch/update. */
    public long lastUpdated;

    /** Whether this mini-app is available offline (has cached service worker). */
    public boolean offlineAvailable;

    public MiniAppInfo() {
        itemType = ITEM_TYPE_MINIAPP;
        user = Process.myUserHandle();
        uid = Process.myUid();
    }

    public MiniAppInfo(@NonNull MiniAppManifest manifest, @NonNull String manifestUrl) {
        this();
        this.manifest = manifest;
        this.manifestUrl = manifestUrl;
        this.appId = manifest.getAppId();
        this.startUrl = manifest.startUrl;
        this.title = manifest.shortName != null ? manifest.shortName : manifest.name;
        this.contentDescription = manifest.name;
        this.lastUpdated = System.currentTimeMillis();
        this.offlineAvailable = manifest.offlineCapable;

        // Set AppInfo-required fields
        this.componentName = new ComponentName(MINIAPP_PACKAGE, appId);
        this.intent = getLaunchIntent();
    }

    /**
     * Creates an Intent that launches MiniAppActivity for this mini-app.
     */
    @NonNull
    public Intent getLaunchIntent() {
        Intent launchIntent = new Intent(MiniAppActivity.ACTION_LAUNCH_MINIAPP);
        launchIntent.putExtra(MiniAppActivity.EXTRA_APP_ID, appId);
        launchIntent.putExtra(MiniAppActivity.EXTRA_START_URL, startUrl);
        launchIntent.putExtra(MiniAppActivity.EXTRA_MANIFEST_URL, manifestUrl);
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // Target MiniAppActivity explicitly so the intent resolves from pinned items
        launchIntent.setClassName("com.android.launcher3",
                "com.android.launcher3.miniapp.MiniAppActivity");
        return launchIntent;
    }

    @NonNull
    public String getDisplayName() {
        if (manifest != null && manifest.shortName != null) {
            return manifest.shortName;
        }
        if (manifest != null) {
            return manifest.name;
        }
        return title != null ? title.toString() : appId;
    }

    @Override
    public WorkspaceItemInfo makeWorkspaceItem(Context context) {
        WorkspaceItemInfo wsi = super.makeWorkspaceItem(context);
        wsi.itemType = ITEM_TYPE_MINIAPP;
        return wsi;
    }

    /**
     * Returns true if this is a mini-app (as opposed to a native app).
     */
    public static boolean isMiniApp(@Nullable AppInfo info) {
        return info instanceof MiniAppInfo;
    }

    @Override
    public MiniAppInfo clone() {
        MiniAppInfo info = new MiniAppInfo();
        info.appId = appId;
        info.manifestUrl = manifestUrl;
        info.startUrl = startUrl;
        info.manifest = manifest;
        info.cachedIcon = cachedIcon;
        info.lastUpdated = lastUpdated;
        info.offlineAvailable = offlineAvailable;
        info.title = title;
        info.contentDescription = contentDescription;
        info.bitmap = bitmap;
        info.user = user;
        info.uid = uid;
        info.componentName = componentName;
        info.intent = intent;
        info.sectionName = sectionName;
        return info;
    }
}
