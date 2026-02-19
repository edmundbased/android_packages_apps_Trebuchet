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
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Handles mini-app installation from a URL.
 *
 * Installation flow:
 * 1. Fetch manifest JSON from the provided URL
 * 2. Parse and validate the manifest
 * 3. Download and cache the app icon
 * 4. Register the app in MiniAppRegistry
 * 5. Notify the launcher to add the icon to the app drawer / home screen
 *
 * Also supports:
 * - manifest:// and https:// URLs with auto-detection of manifest link tag
 * - Uninstallation (remove registry entry, clear cache, remove icon)
 * - Update checks (re-fetch manifest, compare version)
 */
public class MiniAppInstaller {

    private static final String TAG = "MiniAppInstaller";
    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 15000;
    private static final int MAX_MANIFEST_SIZE = 512 * 1024; // 512 KB
    private static final int ICON_SIZE_PX = 192;

    private static MiniAppInstaller sInstance;

    private final Context mContext;
    private final MiniAppRegistry mRegistry;
    private final Executor mExecutor;
    private final Handler mMainHandler;

    public static synchronized MiniAppInstaller getInstance(@NonNull Context context) {
        if (sInstance == null) {
            sInstance = new MiniAppInstaller(context.getApplicationContext());
        }
        return sInstance;
    }

    private MiniAppInstaller(@NonNull Context context) {
        mContext = context;
        mRegistry = MiniAppRegistry.getInstance(context);
        mExecutor = Executors.newSingleThreadExecutor();
        mMainHandler = new Handler(Looper.getMainLooper());
    }

    /**
     * Install a mini-app from a manifest URL.
     * Runs asynchronously; calls back on the main thread.
     */
    public void install(@NonNull String manifestUrl, @NonNull InstallCallback callback) {
        mExecutor.execute(() -> {
            try {
                // Step 1: Fetch manifest
                String manifestJson = fetchUrl(manifestUrl);
                if (manifestJson == null) {
                    postError(callback, "Failed to fetch manifest from " + manifestUrl);
                    return;
                }

                // Step 2: Parse and validate
                MiniAppManifest manifest;
                try {
                    manifest = MiniAppManifest.fromJson(manifestJson);
                } catch (MiniAppManifestException e) {
                    postError(callback, "Invalid manifest: " + e.getMessage());
                    return;
                }

                // Step 3: Download and cache icon
                String iconPath = null;
                String iconUrl = manifest.getBestIconUrl(ICON_SIZE_PX);
                if (iconUrl != null) {
                    String resolvedIconUrl = manifest.resolveUrl(iconUrl);
                    iconPath = downloadIcon(manifest.getAppId(), resolvedIconUrl);
                }

                // Step 4: Register in the database
                mRegistry.installApp(manifest, manifestUrl, iconPath);

                // Step 5: Build MiniAppInfo for the callback
                MiniAppInfo info = new MiniAppInfo(manifest, manifestUrl);
                if (iconPath != null) {
                    info.cachedIcon = BitmapFactory.decodeFile(iconPath);
                }

                Log.i(TAG, "Installed mini-app: " + manifest.getAppId()
                        + " (" + manifest.name + ")");

                mMainHandler.post(() -> callback.onSuccess(info));

            } catch (Exception e) {
                Log.e(TAG, "Installation failed", e);
                postError(callback, "Installation failed: " + e.getMessage());
            }
        });
    }

    /**
     * Install from a web page URL (not a direct manifest URL).
     * Fetches the HTML, looks for a manifest link tag, then installs.
     */
    public void installFromPageUrl(@NonNull String pageUrl,
            @NonNull InstallCallback callback) {
        mExecutor.execute(() -> {
            try {
                String html = fetchUrl(pageUrl);
                if (html == null) {
                    postError(callback, "Failed to fetch page: " + pageUrl);
                    return;
                }

                // Look for <link rel="manifest" href="...">
                String manifestUrl = extractManifestUrl(html, pageUrl);
                if (manifestUrl == null) {
                    postError(callback, "No manifest found on page: " + pageUrl);
                    return;
                }

                // Delegate to the regular install flow
                mMainHandler.post(() -> install(manifestUrl, callback));

            } catch (Exception e) {
                Log.e(TAG, "Failed to detect manifest from page", e);
                postError(callback, "Failed to detect manifest: " + e.getMessage());
            }
        });
    }

    /**
     * Uninstall a mini-app by ID.
     */
    public void uninstall(@NonNull String appId) {
        mExecutor.execute(() -> {
            // Delete cached icon
            File iconFile = getIconFile(appId);
            if (iconFile.exists()) {
                iconFile.delete();
            }

            // Clear storage
            MiniAppStorage.getInstance(mContext).clearApp(appId);

            // Remove from registry (also removes permissions)
            mRegistry.uninstallApp(appId);

            Log.i(TAG, "Uninstalled mini-app: " + appId);
        });
    }

    /**
     * Check for updates to an installed mini-app.
     * Re-fetches the manifest and compares with stored version.
     */
    public void checkForUpdate(@NonNull String appId, @NonNull UpdateCallback callback) {
        MiniAppInfo existing = mRegistry.getApp(appId);
        if (existing == null) {
            mMainHandler.post(() -> callback.onError("App not installed: " + appId));
            return;
        }

        mExecutor.execute(() -> {
            try {
                String manifestJson = fetchUrl(existing.manifestUrl);
                if (manifestJson == null) {
                    postUpdateError(callback, "Failed to fetch manifest");
                    return;
                }

                MiniAppManifest newManifest = MiniAppManifest.fromJson(manifestJson);
                boolean changed = existing.manifest == null
                        || !manifestJson.equals(existing.manifest.toJson().toString());

                if (changed) {
                    // Re-install with new manifest
                    String iconPath = null;
                    String iconUrl = newManifest.getBestIconUrl(ICON_SIZE_PX);
                    if (iconUrl != null) {
                        iconPath = downloadIcon(appId, newManifest.resolveUrl(iconUrl));
                    }
                    mRegistry.installApp(newManifest, existing.manifestUrl, iconPath);

                    MiniAppInfo updated = new MiniAppInfo(newManifest, existing.manifestUrl);
                    mMainHandler.post(() -> callback.onUpdateAvailable(updated));
                } else {
                    mMainHandler.post(() -> callback.onUpToDate());
                }

            } catch (Exception e) {
                Log.e(TAG, "Update check failed for " + appId, e);
                postUpdateError(callback, "Update check failed: " + e.getMessage());
            }
        });
    }

    /**
     * Synchronous update check for use by MiniAppUpdateJob.
     * Returns true if the manifest has changed since last install.
     * Must NOT be called on the main thread.
     */
    public boolean checkForUpdateSync(@NonNull String manifestUrl,
            @NonNull MiniAppInfo existing) {
        try {
            String manifestJson = fetchUrl(manifestUrl);
            if (manifestJson == null) return false;

            return existing.manifest == null
                    || !manifestJson.equals(existing.manifest.toJson().toString());
        } catch (Exception e) {
            Log.w(TAG, "Sync update check failed for " + existing.appId, e);
            return false;
        }
    }

    // --- Network helpers ---

    @Nullable
    private String fetchUrl(String urlStr) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json, text/html");
            conn.setInstanceFollowRedirects(true);

            int responseCode = conn.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "HTTP " + responseCode + " from " + urlStr);
                return null;
            }

            InputStream is = conn.getInputStream();
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int totalRead = 0;
            int n;
            while ((n = is.read(buf)) != -1 && totalRead < MAX_MANIFEST_SIZE) {
                sb.append(new String(buf, 0, n));
                totalRead += n;
            }
            return sb.toString();

        } catch (IOException e) {
            Log.w(TAG, "Failed to fetch " + urlStr, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    @Nullable
    private String downloadIcon(String appId, String iconUrl) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(iconUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }

            InputStream is = conn.getInputStream();
            Bitmap bitmap = BitmapFactory.decodeStream(is);
            if (bitmap == null) return null;

            // Scale to standard size
            bitmap = Bitmap.createScaledBitmap(bitmap, ICON_SIZE_PX, ICON_SIZE_PX, true);

            // Save to cache directory
            File iconFile = getIconFile(appId);
            iconFile.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(iconFile)) {
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            }

            return iconFile.getAbsolutePath();

        } catch (IOException e) {
            Log.w(TAG, "Failed to download icon from " + iconUrl, e);
            return null;
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private File getIconFile(String appId) {
        File cacheDir = new File(mContext.getCacheDir(), "miniapp_icons");
        return new File(cacheDir, sanitize(appId) + ".png");
    }

    // --- HTML manifest detection ---

    @Nullable
    static String extractManifestUrl(String html, String baseUrl) {
        // Simple regex-free parser for <link rel="manifest" href="...">
        String lower = html.toLowerCase();
        int idx = 0;
        while (true) {
            idx = lower.indexOf("<link", idx);
            if (idx < 0) break;

            int endTag = lower.indexOf(">", idx);
            if (endTag < 0) break;

            String tag = html.substring(idx, endTag + 1);
            String tagLower = tag.toLowerCase();

            if (tagLower.contains("rel=\"manifest\"") || tagLower.contains("rel='manifest'")) {
                // Extract href
                String href = extractAttribute(tag, "href");
                if (href != null) {
                    return resolveUrl(href, baseUrl);
                }
            }
            idx = endTag + 1;
        }
        return null;
    }

    @Nullable
    private static String extractAttribute(String tag, String attr) {
        String lower = tag.toLowerCase();
        int start = lower.indexOf(attr + "=\"");
        if (start >= 0) {
            start += attr.length() + 2;
            int end = tag.indexOf("\"", start);
            if (end > start) return tag.substring(start, end);
        }
        start = lower.indexOf(attr + "='");
        if (start >= 0) {
            start += attr.length() + 2;
            int end = tag.indexOf("'", start);
            if (end > start) return tag.substring(start, end);
        }
        return null;
    }

    private static String resolveUrl(String href, String baseUrl) {
        if (href.startsWith("http://") || href.startsWith("https://")) {
            return href;
        }
        // Extract origin from base URL
        try {
            URL base = new URL(baseUrl);
            if (href.startsWith("/")) {
                return base.getProtocol() + "://" + base.getHost()
                        + (base.getPort() > 0 ? ":" + base.getPort() : "") + href;
            }
            String basePath = base.getPath();
            int lastSlash = basePath.lastIndexOf('/');
            if (lastSlash >= 0) {
                basePath = basePath.substring(0, lastSlash + 1);
            }
            return base.getProtocol() + "://" + base.getHost()
                    + (base.getPort() > 0 ? ":" + base.getPort() : "") + basePath + href;
        } catch (Exception e) {
            return href;
        }
    }

    private static String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    private void postError(InstallCallback cb, String msg) {
        Log.e(TAG, msg);
        mMainHandler.post(() -> cb.onError(msg));
    }

    private void postUpdateError(UpdateCallback cb, String msg) {
        Log.e(TAG, msg);
        mMainHandler.post(() -> cb.onError(msg));
    }

    // --- Callbacks ---

    public interface InstallCallback {
        void onSuccess(@NonNull MiniAppInfo info);
        void onError(@NonNull String message);
    }

    public interface UpdateCallback {
        void onUpdateAvailable(@NonNull MiniAppInfo updated);
        void onUpToDate();
        void onError(@NonNull String message);
    }
}
