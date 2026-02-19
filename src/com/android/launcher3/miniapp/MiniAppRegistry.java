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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * SQLite-backed registry for installed mini-apps.
 * Stores manifest data, installation state, and permission grants.
 */
public class MiniAppRegistry {

    private static final String TAG = "MiniAppRegistry";
    private static final String DB_NAME = "miniapps.db";
    private static final int DB_VERSION = 1;

    private static MiniAppRegistry sInstance;

    private final DbHelper mDbHelper;

    // --- Table: apps ---
    private static final String TABLE_APPS = "miniapps";
    private static final String COL_APP_ID = "app_id";
    private static final String COL_MANIFEST_URL = "manifest_url";
    private static final String COL_START_URL = "start_url";
    private static final String COL_NAME = "name";
    private static final String COL_SHORT_NAME = "short_name";
    private static final String COL_MANIFEST_JSON = "manifest_json";
    private static final String COL_ICON_PATH = "icon_path";
    private static final String COL_INSTALLED_AT = "installed_at";
    private static final String COL_LAST_UPDATED = "last_updated";
    private static final String COL_LAST_LAUNCHED = "last_launched";
    private static final String COL_LAUNCH_COUNT = "launch_count";
    private static final String COL_OFFLINE_AVAILABLE = "offline_available";

    // --- Table: permissions ---
    private static final String TABLE_PERMISSIONS = "miniapp_permissions";
    private static final String COL_PERM_APP_ID = "app_id";
    private static final String COL_PERMISSION = "permission";
    private static final String COL_STATE = "state"; // "granted", "denied", "not_requested"
    private static final String COL_GRANTED_AT = "granted_at";

    public static synchronized MiniAppRegistry getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MiniAppRegistry(context.getApplicationContext());
        }
        return sInstance;
    }

    private MiniAppRegistry(@NonNull Context context) {
        mDbHelper = new DbHelper(context);
    }

    // --- App CRUD ---

    /**
     * Install (register) a mini-app from a parsed manifest.
     * If already installed, updates the manifest data.
     */
    public void installApp(@NonNull MiniAppManifest manifest, @NonNull String manifestUrl,
            @Nullable String iconPath) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        String appId = manifest.getAppId();

        values.put(COL_APP_ID, appId);
        values.put(COL_MANIFEST_URL, manifestUrl);
        values.put(COL_START_URL, manifest.startUrl);
        values.put(COL_NAME, manifest.name);
        values.put(COL_SHORT_NAME, manifest.shortName);
        values.put(COL_ICON_PATH, iconPath);
        values.put(COL_OFFLINE_AVAILABLE, manifest.offlineCapable ? 1 : 0);
        values.put(COL_LAST_UPDATED, System.currentTimeMillis());

        try {
            values.put(COL_MANIFEST_JSON, manifest.toJson().toString());
        } catch (Exception e) {
            Log.e(TAG, "Failed to serialize manifest for " + appId, e);
        }

        // Check if already installed
        if (getApp(appId) != null) {
            db.update(TABLE_APPS, values, COL_APP_ID + "=?", new String[]{appId});
            Log.i(TAG, "Updated mini-app: " + appId);
        } else {
            values.put(COL_INSTALLED_AT, System.currentTimeMillis());
            values.put(COL_LAUNCH_COUNT, 0);
            db.insert(TABLE_APPS, null, values);
            Log.i(TAG, "Installed mini-app: " + appId);

            // Initialize permissions as "not_requested"
            for (String perm : manifest.nativePermissions) {
                setPermissionState(appId, perm, PermissionState.NOT_REQUESTED);
            }
        }
    }

    /**
     * Uninstall a mini-app by ID.
     */
    public void uninstallApp(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.delete(TABLE_APPS, COL_APP_ID + "=?", new String[]{appId});
        db.delete(TABLE_PERMISSIONS, COL_PERM_APP_ID + "=?", new String[]{appId});
        Log.i(TAG, "Uninstalled mini-app: " + appId);
    }

    /**
     * Get a single mini-app by ID.
     */
    @Nullable
    public MiniAppInfo getApp(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.query(TABLE_APPS, null,
                COL_APP_ID + "=?", new String[]{appId},
                null, null, null)) {
            if (c.moveToFirst()) {
                return cursorToMiniAppInfo(c);
            }
        }
        return null;
    }

    /**
     * Get all installed mini-apps, ordered by name.
     */
    @NonNull
    public List<MiniAppInfo> getAllApps() {
        List<MiniAppInfo> apps = new ArrayList<>();
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.query(TABLE_APPS, null,
                null, null, null, null, COL_NAME + " ASC")) {
            while (c.moveToNext()) {
                MiniAppInfo info = cursorToMiniAppInfo(c);
                if (info != null) {
                    apps.add(info);
                }
            }
        }
        return apps;
    }

    /**
     * Record a launch event for a mini-app.
     */
    public void recordLaunch(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.execSQL("UPDATE " + TABLE_APPS
                + " SET " + COL_LAUNCH_COUNT + " = " + COL_LAUNCH_COUNT + " + 1, "
                + COL_LAST_LAUNCHED + " = ? WHERE " + COL_APP_ID + " = ?",
                new Object[]{System.currentTimeMillis(), appId});
    }

    /**
     * Check if a mini-app is installed.
     */
    public boolean isInstalled(@NonNull String appId) {
        return getApp(appId) != null;
    }

    // --- Permission management ---

    public enum PermissionState {
        NOT_REQUESTED("not_requested"),
        GRANTED("granted"),
        DENIED("denied");

        public final String value;

        PermissionState(String value) {
            this.value = value;
        }

        static PermissionState fromString(String s) {
            for (PermissionState state : values()) {
                if (state.value.equals(s)) return state;
            }
            return NOT_REQUESTED;
        }
    }

    /**
     * Set the permission state for a given app + permission pair.
     */
    public void setPermissionState(@NonNull String appId, @NonNull String permission,
            @NonNull PermissionState state) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_PERM_APP_ID, appId);
        values.put(COL_PERMISSION, permission);
        values.put(COL_STATE, state.value);
        if (state == PermissionState.GRANTED) {
            values.put(COL_GRANTED_AT, System.currentTimeMillis());
        }

        // Upsert
        int updated = db.update(TABLE_PERMISSIONS, values,
                COL_PERM_APP_ID + "=? AND " + COL_PERMISSION + "=?",
                new String[]{appId, permission});
        if (updated == 0) {
            db.insert(TABLE_PERMISSIONS, null, values);
        }
    }

    /**
     * Get the permission state for a given app + permission pair.
     */
    @NonNull
    public PermissionState getPermissionState(@NonNull String appId,
            @NonNull String permission) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.query(TABLE_PERMISSIONS, new String[]{COL_STATE},
                COL_PERM_APP_ID + "=? AND " + COL_PERMISSION + "=?",
                new String[]{appId, permission},
                null, null, null)) {
            if (c.moveToFirst()) {
                return PermissionState.fromString(c.getString(0));
            }
        }
        return PermissionState.NOT_REQUESTED;
    }

    /**
     * Get all permission states for a given app.
     */
    @NonNull
    public List<PermissionEntry> getPermissions(@NonNull String appId) {
        List<PermissionEntry> entries = new ArrayList<>();
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.query(TABLE_PERMISSIONS, null,
                COL_PERM_APP_ID + "=?", new String[]{appId},
                null, null, COL_PERMISSION + " ASC")) {
            while (c.moveToNext()) {
                entries.add(new PermissionEntry(
                        c.getString(c.getColumnIndexOrThrow(COL_PERMISSION)),
                        PermissionState.fromString(
                                c.getString(c.getColumnIndexOrThrow(COL_STATE)))
                ));
            }
        }
        return entries;
    }

    /**
     * Revoke all permissions for a given app (resets to NOT_REQUESTED).
     */
    public void revokeAllPermissions(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put(COL_STATE, PermissionState.NOT_REQUESTED.value);
        values.putNull(COL_GRANTED_AT);
        db.update(TABLE_PERMISSIONS, values,
                COL_PERM_APP_ID + "=?", new String[]{appId});
    }

    public static class PermissionEntry {
        @NonNull public final String permission;
        @NonNull public final PermissionState state;

        public PermissionEntry(@NonNull String permission, @NonNull PermissionState state) {
            this.permission = permission;
            this.state = state;
        }
    }

    // --- Helpers ---

    @Nullable
    private MiniAppInfo cursorToMiniAppInfo(Cursor c) {
        try {
            String manifestJson = c.getString(c.getColumnIndexOrThrow(COL_MANIFEST_JSON));
            MiniAppManifest manifest = null;
            if (manifestJson != null) {
                try {
                    manifest = MiniAppManifest.fromJson(manifestJson);
                } catch (MiniAppManifestException e) {
                    Log.w(TAG, "Failed to parse stored manifest", e);
                }
            }

            String manifestUrl = c.getString(c.getColumnIndexOrThrow(COL_MANIFEST_URL));
            MiniAppInfo info;
            if (manifest != null) {
                info = new MiniAppInfo(manifest, manifestUrl);
            } else {
                info = new MiniAppInfo();
                info.manifestUrl = manifestUrl;
                info.appId = c.getString(c.getColumnIndexOrThrow(COL_APP_ID));
                info.startUrl = c.getString(c.getColumnIndexOrThrow(COL_START_URL));
                info.title = c.getString(c.getColumnIndexOrThrow(COL_NAME));
            }

            info.lastUpdated = c.getLong(c.getColumnIndexOrThrow(COL_LAST_UPDATED));
            info.offlineAvailable = c.getInt(
                    c.getColumnIndexOrThrow(COL_OFFLINE_AVAILABLE)) == 1;

            String iconPath = c.getString(c.getColumnIndexOrThrow(COL_ICON_PATH));
            // Icon bitmap loading is handled by the caller (MiniAppIconCache)

            return info;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read mini-app from cursor", e);
            return null;
        }
    }

    // --- Database helper ---

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(@NonNull Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_APPS + " ("
                    + COL_APP_ID + " TEXT PRIMARY KEY, "
                    + COL_MANIFEST_URL + " TEXT NOT NULL, "
                    + COL_START_URL + " TEXT NOT NULL, "
                    + COL_NAME + " TEXT NOT NULL, "
                    + COL_SHORT_NAME + " TEXT, "
                    + COL_MANIFEST_JSON + " TEXT, "
                    + COL_ICON_PATH + " TEXT, "
                    + COL_INSTALLED_AT + " INTEGER, "
                    + COL_LAST_UPDATED + " INTEGER, "
                    + COL_LAST_LAUNCHED + " INTEGER, "
                    + COL_LAUNCH_COUNT + " INTEGER DEFAULT 0, "
                    + COL_OFFLINE_AVAILABLE + " INTEGER DEFAULT 0"
                    + ")");

            db.execSQL("CREATE TABLE " + TABLE_PERMISSIONS + " ("
                    + COL_PERM_APP_ID + " TEXT NOT NULL, "
                    + COL_PERMISSION + " TEXT NOT NULL, "
                    + COL_STATE + " TEXT NOT NULL DEFAULT 'not_requested', "
                    + COL_GRANTED_AT + " INTEGER, "
                    + "PRIMARY KEY (" + COL_PERM_APP_ID + ", " + COL_PERMISSION + "), "
                    + "FOREIGN KEY (" + COL_PERM_APP_ID + ") REFERENCES "
                    + TABLE_APPS + "(" + COL_APP_ID + ") ON DELETE CASCADE"
                    + ")");

            db.execSQL("CREATE INDEX idx_perms_app ON " + TABLE_PERMISSIONS
                    + " (" + COL_PERM_APP_ID + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Future schema migrations go here
            Log.i(TAG, "Upgrading miniapps DB from " + oldVersion + " to " + newVersion);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
        }
    }
}
