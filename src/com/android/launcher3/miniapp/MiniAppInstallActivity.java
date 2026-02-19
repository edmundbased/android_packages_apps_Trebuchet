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

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.Nullable;

import com.android.launcher3.R;

/**
 * Activity for installing a mini-app from a URL.
 *
 * Two entry modes:
 * 1. User opens from settings/menu -> manual URL entry
 * 2. Deep link via miniapp:// or intent with URL extra -> auto-fetch
 *
 * Flow: Enter URL -> Fetch manifest -> Show preview card -> Install
 */
public class MiniAppInstallActivity extends Activity {

    public static final String EXTRA_MANIFEST_URL = "manifest_url";
    public static final String ACTION_INSTALL = "com.android.launcher3.miniapp.action.INSTALL";

    private EditText mUrlInput;
    private Button mFetchButton;
    private Button mInstallButton;
    private ProgressBar mProgress;
    private View mPreviewCard;

    private TextView mAppName;
    private TextView mAppDomain;
    private TextView mAppDescription;
    private TextView mPermissionsLabel;
    private LinearLayout mPermissionsList;

    private MiniAppManifest mPendingManifest;
    private String mPendingManifestUrl;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.miniapp_activity_install);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.action_bar);
        setActionBar(toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());

        // Bind views
        mUrlInput = findViewById(R.id.url_input);
        mFetchButton = findViewById(R.id.btn_fetch);
        mInstallButton = findViewById(R.id.btn_install);
        mProgress = findViewById(R.id.install_progress);
        mPreviewCard = findViewById(R.id.preview_card);
        mAppName = findViewById(R.id.app_name);
        mAppDomain = findViewById(R.id.app_domain);
        mAppDescription = findViewById(R.id.app_description);
        mPermissionsLabel = findViewById(R.id.permissions_label);
        mPermissionsList = findViewById(R.id.permissions_list);

        // Wire fetch button
        mFetchButton.setOnClickListener(v -> {
            String url = mUrlInput.getText().toString().trim();
            if (TextUtils.isEmpty(url)) {
                Toast.makeText(this, R.string.miniapp_install_enter_url,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "https://" + url;
                mUrlInput.setText(url);
            }
            fetchManifest(url);
        });

        // Wire install button
        mInstallButton.setOnClickListener(v -> installMiniApp());

        // Check if launched with a URL
        String url = null;
        Intent intent = getIntent();
        if (intent != null) {
            url = intent.getStringExtra(EXTRA_MANIFEST_URL);
            if (url == null && intent.getData() != null) {
                Uri data = intent.getData();
                if ("miniapp".equals(data.getScheme())) {
                    // miniapp://domain.com/path -> https://domain.com/path/manifest.json
                    url = "https://" + data.getHost()
                            + (data.getPath() != null ? data.getPath() : "")
                            + "/manifest.json";
                } else {
                    url = data.toString();
                }
            }
        }

        if (url != null) {
            mUrlInput.setText(url);
            fetchManifest(url);
        }
    }

    private void fetchManifest(String url) {
        mProgress.setVisibility(View.VISIBLE);
        mPreviewCard.setVisibility(View.GONE);
        mFetchButton.setEnabled(false);
        mPendingManifest = null;
        mPendingManifestUrl = url;

        // Try direct manifest fetch first
        MiniAppInstaller.getInstance(this).install(url,
                new MiniAppInstaller.InstallCallback() {
                    @Override
                    public void onSuccess(MiniAppInfo info) {
                        // Don't actually install yet — just preview
                        // Uninstall the auto-install and show preview instead
                        MiniAppInstaller.getInstance(MiniAppInstallActivity.this)
                                .uninstall(info.appId);
                        mPendingManifest = info.manifest;
                        mPendingManifestUrl = url;
                        showPreview(info.manifest);
                    }

                    @Override
                    public void onError(String message) {
                        // Try as page URL (auto-detect manifest)
                        tryAsPageUrl(url);
                    }
                });
    }

    private void tryAsPageUrl(String url) {
        MiniAppInstaller.getInstance(this).installFromPageUrl(url,
                new MiniAppInstaller.InstallCallback() {
                    @Override
                    public void onSuccess(MiniAppInfo info) {
                        MiniAppInstaller.getInstance(MiniAppInstallActivity.this)
                                .uninstall(info.appId);
                        mPendingManifest = info.manifest;
                        showPreview(info.manifest);
                    }

                    @Override
                    public void onError(String message) {
                        mProgress.setVisibility(View.GONE);
                        mFetchButton.setEnabled(true);
                        Toast.makeText(MiniAppInstallActivity.this,
                                getString(R.string.miniapp_install_fetch_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private void showPreview(MiniAppManifest manifest) {
        mProgress.setVisibility(View.GONE);
        mFetchButton.setEnabled(true);
        mPreviewCard.setVisibility(View.VISIBLE);
        mInstallButton.setEnabled(true);

        mAppName.setText(manifest.name);
        mAppDomain.setText(manifest.getAppId());

        if (manifest.description != null) {
            mAppDescription.setText(manifest.description);
            mAppDescription.setVisibility(View.VISIBLE);
        } else {
            mAppDescription.setVisibility(View.GONE);
        }

        // Permissions
        mPermissionsList.removeAllViews();
        if (manifest.nativePermissions.isEmpty()) {
            mPermissionsLabel.setText(R.string.miniapp_install_no_permissions);
        } else {
            mPermissionsLabel.setText(R.string.miniapp_install_permissions_requested);
            LayoutInflater inflater = LayoutInflater.from(this);
            for (String perm : manifest.nativePermissions) {
                View row = inflater.inflate(R.layout.miniapp_permission_row,
                        mPermissionsList, false);
                TextView permName = row.findViewById(R.id.perm_name);
                TextView permState = row.findViewById(R.id.perm_state);

                String friendly = MiniAppPermissionManager.getFriendlyPermissionName(perm);
                int tier = MiniAppPermissionManager.getInstance(this).getPermissionTier(perm);

                permName.setText("\u2022 " + friendly);
                if (tier == 3) {
                    permState.setText(R.string.miniapp_install_privileged);
                    permState.setTextColor(getColor(R.color.material_color_error));
                }
                mPermissionsList.addView(row);
            }
        }

        // Developer info
        if (manifest.developer != null) {
            TextView dev = new TextView(this);
            dev.setText(getString(R.string.miniapp_install_developer,
                    manifest.developer.name
                            + (manifest.developer.verified ? " \u2713" : "")));
            dev.setTextAppearance(R.style.MiniApp_TextAppearance_Caption);
            dev.setPadding(0, dpToPx(4), 0, dpToPx(8));
            // Insert before install button
            LinearLayout cardContent = (LinearLayout) mPreviewCard.findViewById(
                    R.id.permissions_list).getParent();
            int installIdx = cardContent.indexOfChild(mInstallButton);
            cardContent.addView(dev, installIdx);
        }
    }

    private void installMiniApp() {
        if (mPendingManifest == null || mPendingManifestUrl == null) return;

        mInstallButton.setEnabled(false);
        mInstallButton.setText(R.string.miniapp_install_installing);

        MiniAppInstaller.getInstance(this).install(mPendingManifestUrl,
                new MiniAppInstaller.InstallCallback() {
                    @Override
                    public void onSuccess(MiniAppInfo info) {
                        Toast.makeText(MiniAppInstallActivity.this,
                                getString(R.string.miniapp_install_success,
                                        info.getDisplayName()),
                                Toast.LENGTH_SHORT).show();
                        finish();
                    }

                    @Override
                    public void onError(String message) {
                        mInstallButton.setEnabled(true);
                        mInstallButton.setText(R.string.miniapp_install);
                        Toast.makeText(MiniAppInstallActivity.this,
                                getString(R.string.miniapp_install_failed, message),
                                Toast.LENGTH_LONG).show();
                    }
                });
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }
}
