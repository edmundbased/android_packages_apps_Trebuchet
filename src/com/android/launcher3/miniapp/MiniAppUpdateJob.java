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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

/**
 * Periodic background job that checks for mini-app manifest updates.
 * Runs every 24 hours on an unmetered network connection.
 * Notifies the user when updates are available.
 */
public class MiniAppUpdateJob extends JobService {

    private static final String TAG = "MiniAppUpdateJob";
    private static final int JOB_ID_VALUE = 77777;
    private static final String CHANNEL_ID = "miniapp_updates";
    private static final int NOTIFICATION_ID = 77778;

    // 24 hours in milliseconds
    private static final long INTERVAL_MS = 24 * 60 * 60 * 1000L;

    /**
     * Schedule the periodic update check job.
     * Call this when the launcher starts or when a mini-app is installed.
     */
    public static void schedule(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler == null) return;

        // Don't re-schedule if already pending
        if (scheduler.getPendingJob(JOB_ID_VALUE) != null) {
            return;
        }

        JobInfo job = new JobInfo.Builder(JOB_ID_VALUE,
                new ComponentName(context, MiniAppUpdateJob.class))
                .setPeriodic(INTERVAL_MS)
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
                .setPersisted(true)
                .build();

        int result = scheduler.schedule(job);
        if (result == JobScheduler.RESULT_SUCCESS) {
            Log.i(TAG, "Scheduled periodic mini-app update check");
        } else {
            Log.w(TAG, "Failed to schedule update check job");
        }
    }

    /**
     * Cancel the periodic update check job.
     */
    public static void cancel(Context context) {
        JobScheduler scheduler = context.getSystemService(JobScheduler.class);
        if (scheduler != null) {
            scheduler.cancel(JOB_ID_VALUE);
        }
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.i(TAG, "Starting mini-app update check");

        new Thread(() -> {
            try {
                checkForUpdates();
            } catch (Exception e) {
                Log.e(TAG, "Update check failed", e);
            }
            jobFinished(params, false);
        }).start();

        return true; // Work is async
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return true; // Retry if interrupted
    }

    private void checkForUpdates() {
        MiniAppRegistry registry = MiniAppRegistry.getInstance(this);
        MiniAppInstaller installer = MiniAppInstaller.getInstance(this);
        List<MiniAppInfo> apps = registry.getAllApps();

        if (apps.isEmpty()) return;

        int updatesAvailable = 0;
        StringBuilder updatedNames = new StringBuilder();

        for (MiniAppInfo app : apps) {
            if (app.manifestUrl == null) continue;

            try {
                boolean hasUpdate = installer.checkForUpdateSync(app.manifestUrl, app);
                if (hasUpdate) {
                    updatesAvailable++;
                    if (updatedNames.length() > 0) {
                        updatedNames.append(", ");
                    }
                    updatedNames.append(app.getDisplayName());
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to check update for " + app.appId, e);
            }
        }

        if (updatesAvailable > 0) {
            showUpdateNotification(updatesAvailable, updatedNames.toString());
        }

        Log.i(TAG, "Update check complete: " + updatesAvailable + " updates for "
                + apps.size() + " apps");
    }

    private void showUpdateNotification(int count, String names) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Ensure channel exists
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Mini-App Updates",
                NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("Notifications about mini-app updates");
        nm.createNotificationChannel(channel);

        String title = count == 1
                ? "Mini-App update available"
                : count + " mini-app updates available";

        Intent intent = new Intent(this, MiniAppManageActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_popup_sync)
                .setContentTitle(title)
                .setContentText(names)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();

        nm.notify(NOTIFICATION_ID, notification);
    }
}
