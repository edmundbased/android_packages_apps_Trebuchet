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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Per-app sandboxed key-value storage for mini-apps.
 * Each mini-app can only read/write its own namespace.
 * Tier 1 permission (auto-granted, no prompt needed).
 */
public class MiniAppStorage {

    private static final String DB_NAME = "miniapp_storage.db";
    private static final int DB_VERSION = 1;

    private static final String TABLE = "kv_store";
    private static final String COL_APP_ID = "app_id";
    private static final String COL_KEY = "key";
    private static final String COL_VALUE = "value";

    private static MiniAppStorage sInstance;
    private final DbHelper mDbHelper;

    public static synchronized MiniAppStorage getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MiniAppStorage(context.getApplicationContext());
        }
        return sInstance;
    }

    private MiniAppStorage(@NonNull Context context) {
        mDbHelper = new DbHelper(context);
    }

    /**
     * Get a value by key for a specific app. Returns null if not found.
     */
    @Nullable
    public String get(@NonNull String appId, @NonNull String key) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.query(TABLE, new String[]{COL_VALUE},
                COL_APP_ID + "=? AND " + COL_KEY + "=?",
                new String[]{appId, key}, null, null, null)) {
            if (c.moveToFirst()) {
                return c.getString(0);
            }
        }
        return null;
    }

    /**
     * Set a value by key for a specific app (upsert).
     */
    public void set(@NonNull String appId, @NonNull String key, @NonNull String value) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COL_APP_ID, appId);
        cv.put(COL_KEY, key);
        cv.put(COL_VALUE, value);
        db.insertWithOnConflict(TABLE, null, cv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /**
     * Delete a single key for a specific app.
     */
    public void delete(@NonNull String appId, @NonNull String key) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.delete(TABLE, COL_APP_ID + "=? AND " + COL_KEY + "=?",
                new String[]{appId, key});
    }

    /**
     * Delete all storage for a specific app.
     */
    public void clearApp(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        db.delete(TABLE, COL_APP_ID + "=?", new String[]{appId});
    }

    /**
     * Get total storage size in bytes for an app.
     */
    public long getStorageSize(@NonNull String appId) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT SUM(LENGTH(" + COL_VALUE + ")) FROM " + TABLE
                        + " WHERE " + COL_APP_ID + "=?",
                new String[]{appId})) {
            if (c.moveToFirst()) {
                return c.getLong(0);
            }
        }
        return 0;
    }

    private static class DbHelper extends SQLiteOpenHelper {

        DbHelper(@NonNull Context context) {
            super(context, DB_NAME, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE + " ("
                    + COL_APP_ID + " TEXT NOT NULL, "
                    + COL_KEY + " TEXT NOT NULL, "
                    + COL_VALUE + " TEXT, "
                    + "PRIMARY KEY (" + COL_APP_ID + ", " + COL_KEY + ")"
                    + ")");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // Future migrations
        }
    }
}
