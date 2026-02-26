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
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.R;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

/**
 * Receives ACTION_SEND intents and routes shared content to mini-apps
 * that have declared share_target in their manifest.
 *
 * If multiple mini-apps accept shares, shows a chooser dialog.
 * Otherwise, opens the matching mini-app directly with shared data as URL params.
 */
public class MiniAppShareReceiver extends Activity {

    private static final String TAG = "MiniAppShareRecv";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) {
            finish();
            return;
        }

        String sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
        String sharedTitle = intent.getStringExtra(Intent.EXTRA_SUBJECT);

        // Find mini-apps with share_target
        MiniAppRegistry registry = MiniAppRegistry.getInstance(this);
        List<MiniAppInfo> allApps = registry.getAllApps();
        List<MiniAppInfo> shareApps = new ArrayList<>();

        for (MiniAppInfo app : allApps) {
            if (app.manifest != null && app.manifest.shareTarget != null) {
                shareApps.add(app);
            }
        }

        if (shareApps.isEmpty()) {
            Log.w(TAG, "No mini-apps accept shares");
            finish();
            return;
        }

        if (shareApps.size() == 1) {
            launchWithShareData(shareApps.get(0), sharedTitle, sharedText);
        } else {
            showChooser(shareApps, sharedTitle, sharedText);
        }
    }

    private void showChooser(List<MiniAppInfo> apps, String title, String text) {
        String[] names = new String[apps.size()];
        for (int i = 0; i < apps.size(); i++) {
            names[i] = apps.get(i).getDisplayName();
        }

        new AlertDialog.Builder(this)
                .setTitle(R.string.miniapp_share_chooser_title)
                .setItems(names, (dialog, which) -> {
                    launchWithShareData(apps.get(which), title, text);
                })
                .setOnCancelListener(d -> finish())
                .show();
    }

    private void launchWithShareData(MiniAppInfo app, String title, String text) {
        MiniAppManifest.ShareTarget target = app.manifest.shareTarget;

        // Build the share URL with query parameters
        String url = app.manifest.resolveUrl(target.action);
        StringBuilder params = new StringBuilder();

        if (target.titleParam != null && title != null) {
            appendParam(params, target.titleParam, title);
        }
        if (target.textParam != null && text != null) {
            appendParam(params, target.textParam, text);
        }
        if (target.urlParam != null && text != null && text.startsWith("http")) {
            appendParam(params, target.urlParam, text);
        }

        if (params.length() > 0) {
            url += (url.contains("?") ? "&" : "?") + params;
        }

        // Launch the mini-app with the share URL as start URL
        Intent launch = new Intent(this, MiniAppActivity.class);
        launch.setAction(MiniAppActivity.ACTION_LAUNCH_MINIAPP);
        launch.putExtra(MiniAppActivity.EXTRA_APP_ID, app.appId);
        launch.putExtra(MiniAppActivity.EXTRA_START_URL, url);
        launch.putExtra(MiniAppActivity.EXTRA_MANIFEST_URL, app.manifestUrl);
        startActivity(launch);
        finish();
    }

    private static void appendParam(StringBuilder sb, String key, String value) {
        if (sb.length() > 0) sb.append("&");
        try {
            sb.append(URLEncoder.encode(key, "UTF-8"))
              .append("=")
              .append(URLEncoder.encode(value, "UTF-8"));
        } catch (UnsupportedEncodingException e) {
            sb.append(key).append("=").append(value);
        }
    }
}
