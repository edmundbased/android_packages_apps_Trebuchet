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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.text.TextUtils;

import androidx.annotation.NonNull;

/**
 * Helper for posting notifications on behalf of mini-apps.
 * Each mini-app gets its own notification channel group.
 */
public class MiniAppNotificationHelper {

    private static final String CHANNEL_GROUP_PREFIX = "miniapp_";
    private static final String CHANNEL_PREFIX = "miniapp_ch_";

    /**
     * Show a notification for a mini-app.
     */
    public static void show(@NonNull Context context, @NonNull String appId,
            @NonNull String title, @NonNull String body, @NonNull String channelId) {

        NotificationManager nm = context.getSystemService(NotificationManager.class);
        if (nm == null) return;

        String fullChannelId = CHANNEL_PREFIX + sanitize(appId) + "_"
                + (TextUtils.isEmpty(channelId) ? "default" : sanitize(channelId));

        // Ensure channel exists
        if (nm.getNotificationChannel(fullChannelId) == null) {
            String channelName = appId + " notifications";
            NotificationChannel channel = new NotificationChannel(
                    fullChannelId, channelName, NotificationManager.IMPORTANCE_DEFAULT);
            channel.setDescription("Notifications from mini-app: " + appId);
            nm.createNotificationChannel(channel);
        }

        // Build launch intent
        Intent launchIntent = new Intent(MiniAppActivity.ACTION_LAUNCH_MINIAPP);
        launchIntent.putExtra(MiniAppActivity.EXTRA_APP_ID, appId);
        MiniAppInfo info = MiniAppRegistry.getInstance(context).getApp(appId);
        if (info != null) {
            launchIntent.putExtra(MiniAppActivity.EXTRA_START_URL, info.startUrl);
            launchIntent.putExtra(MiniAppActivity.EXTRA_MANIFEST_URL, info.manifestUrl);
        }
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pi = PendingIntent.getActivity(context, appId.hashCode(),
                launchIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(context, fullChannelId)
                .setContentTitle(title)
                .setContentText(body)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        // Use a stable notification ID per app so updates replace previous
        int notifId = (appId + "_" + System.currentTimeMillis()).hashCode();
        nm.notify(notifId, notification);
    }

    private static String sanitize(String input) {
        return input.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
