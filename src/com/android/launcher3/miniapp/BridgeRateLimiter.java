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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sliding-window rate limiter for NativeBridge calls.
 *
 * Prevents mini-apps from abusing native APIs by enforcing per-method
 * call limits within configurable time windows.
 *
 * Default limits:
 * - SMS: 10 per hour
 * - Notifications: 30 per hour
 * - Storage: 500 per minute
 * - General: 120 per minute
 */
public final class BridgeRateLimiter {

    private static final String TAG = "BridgeRateLimiter";

    // Limits: {method -> (max_calls, window_ms)}
    private static final Map<String, int[]> LIMITS = new ConcurrentHashMap<>();

    static {
        // method name → [max_calls, window_ms]
        LIMITS.put("sendSMS", new int[]{10, 3600_000});        // 10/hour
        LIMITS.put("sendNotification", new int[]{30, 3600_000}); // 30/hour
        LIMITS.put("readContacts", new int[]{60, 3600_000});     // 60/hour
        LIMITS.put("readNFC", new int[]{60, 60_000});            // 60/min
        LIMITS.put("writeNFC", new int[]{30, 3600_000});         // 30/hour
        LIMITS.put("storageGet", new int[]{500, 60_000});        // 500/min
        LIMITS.put("storageSet", new int[]{500, 60_000});        // 500/min
        LIMITS.put("clipboardRead", new int[]{30, 60_000});      // 30/min
    }

    // Default limit for methods not in the map
    private static final int DEFAULT_MAX = 120;
    private static final int DEFAULT_WINDOW_MS = 60_000;

    // Tracks call timestamps: {appId:method -> circular buffer of timestamps}
    private final Map<String, CallWindow> mWindows = new ConcurrentHashMap<>();

    /**
     * Check if a call is allowed under rate limits.
     *
     * @param appId  the calling mini-app's ID
     * @param method the bridge method being called
     * @return true if the call is allowed, false if rate-limited
     */
    public boolean allowCall(String appId, String method) {
        String key = appId + ":" + method;
        int[] limit = LIMITS.getOrDefault(method, new int[]{DEFAULT_MAX, DEFAULT_WINDOW_MS});
        int maxCalls = limit[0];
        int windowMs = limit[1];

        CallWindow window = mWindows.computeIfAbsent(key,
                k -> new CallWindow(maxCalls));

        boolean allowed = window.tryRecord(maxCalls, windowMs);
        if (!allowed) {
            Log.w(TAG, "Rate limited: " + appId + " → " + method
                    + " (max " + maxCalls + " per " + (windowMs / 1000) + "s)");
        }
        return allowed;
    }

    /**
     * Reset all rate limit windows (e.g., when app is uninstalled).
     */
    public void resetForApp(String appId) {
        mWindows.entrySet().removeIf(entry -> entry.getKey().startsWith(appId + ":"));
    }

    /**
     * Simple sliding window counter using a circular buffer of timestamps.
     */
    private static class CallWindow {
        private final long[] timestamps;
        private int head = 0;
        private int count = 0;

        CallWindow(int maxSize) {
            timestamps = new long[maxSize];
        }

        synchronized boolean tryRecord(int maxCalls, int windowMs) {
            long now = System.currentTimeMillis();
            long cutoff = now - windowMs;

            // Evict expired entries
            while (count > 0 && timestamps[head] < cutoff) {
                head = (head + 1) % timestamps.length;
                count--;
            }

            if (count >= maxCalls) {
                return false;
            }

            // Record this call
            int tail = (head + count) % timestamps.length;
            timestamps[tail] = now;
            count++;
            return true;
        }
    }
}
