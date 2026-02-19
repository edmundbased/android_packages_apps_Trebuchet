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
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.util.List;

/**
 * Management panel for installed mini-apps.
 * Shows a list of installed mini-apps with options to:
 * - View permissions and toggle them
 * - View storage usage
 * - Clear cache
 * - Force update
 * - Uninstall
 */
public class MiniAppManageActivity extends Activity {

    public static final String EXTRA_APP_ID = "manage_app_id";

    private MiniAppRegistry mRegistry;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mRegistry = MiniAppRegistry.getInstance(this);

        String targetAppId = getIntent() != null
                ? getIntent().getStringExtra(EXTRA_APP_ID) : null;

        if (targetAppId != null) {
            buildSingleAppView(targetAppId);
        } else {
            buildAppListView();
        }
    }

    private void buildAppListView() {
        setContentView(R.layout.miniapp_activity_manage);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.action_bar);
        setActionBar(toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());

        RecyclerView recyclerView = findViewById(R.id.manage_list);
        TextView emptyView = findViewById(R.id.manage_empty);

        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        List<MiniAppInfo> apps = mRegistry.getAllApps();
        if (apps.isEmpty()) {
            emptyView.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
            return;
        }

        MiniAppManageAdapter adapter = new MiniAppManageAdapter(apps, mRegistry,
                new MiniAppManageAdapter.ActionCallback() {
                    @Override
                    public void onClearData(MiniAppInfo app) {
                        MiniAppStorage.getInstance(MiniAppManageActivity.this)
                                .clearApp(app.appId);
                        mRegistry.revokeAllPermissions(app.appId);
                        Toast.makeText(MiniAppManageActivity.this,
                                getString(R.string.miniapp_manage_cleared_for,
                                        app.getDisplayName()),
                                Toast.LENGTH_SHORT).show();
                        recreate();
                    }

                    @Override
                    public void onUninstall(MiniAppInfo app) {
                        new AlertDialog.Builder(MiniAppManageActivity.this)
                                .setTitle(getString(R.string.miniapp_uninstall_confirm_title,
                                        app.getDisplayName()))
                                .setMessage(R.string.miniapp_uninstall_confirm_message)
                                .setPositiveButton(R.string.miniapp_uninstall, (d, w) -> {
                                    MiniAppInstaller.getInstance(MiniAppManageActivity.this)
                                            .uninstall(app.appId);
                                    Toast.makeText(MiniAppManageActivity.this,
                                            getString(R.string.miniapp_uninstalled_name,
                                                    app.getDisplayName()),
                                            Toast.LENGTH_SHORT).show();
                                    recreate();
                                })
                                .setNegativeButton(R.string.miniapp_cancel, null)
                                .show();
                    }
                });
        recyclerView.setAdapter(adapter);
    }

    private void buildSingleAppView(String appId) {
        MiniAppInfo app = mRegistry.getApp(appId);
        if (app == null) {
            Toast.makeText(this, R.string.miniapp_manage_not_found,
                    Toast.LENGTH_SHORT).show();
            finish();
            return;
        }

        // Single-app detail mode uses a simple ScrollView with themed views
        ScrollView scroll = new ScrollView(this);
        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        int padding = dpToPx(16);
        container.setPadding(padding, padding, padding, padding);
        scroll.addView(container);
        setContentView(scroll);

        setTitle(app.getDisplayName());

        // App name
        TextView name = new TextView(this);
        name.setText(app.getDisplayName());
        name.setTextAppearance(R.style.MiniApp_TextAppearance_Headline);
        container.addView(name);

        // URL
        TextView url = new TextView(this);
        url.setText(app.startUrl);
        url.setTextAppearance(R.style.MiniApp_TextAppearance_Caption);
        url.setPadding(0, dpToPx(4), 0, dpToPx(16));
        container.addView(url);

        // Description
        if (app.manifest != null && app.manifest.description != null) {
            TextView desc = new TextView(this);
            desc.setText(app.manifest.description);
            desc.setTextAppearance(R.style.MiniApp_TextAppearance_Body);
            desc.setPadding(0, 0, 0, dpToPx(16));
            container.addView(desc);
        }

        // Permissions section
        TextView permHeader = new TextView(this);
        permHeader.setText(R.string.miniapp_manage_permissions_header);
        permHeader.setTextAppearance(R.style.MiniApp_TextAppearance_Title);
        permHeader.setPadding(0, dpToPx(8), 0, dpToPx(8));
        container.addView(permHeader);

        List<MiniAppRegistry.PermissionEntry> perms = mRegistry.getPermissions(appId);
        if (perms.isEmpty()) {
            TextView noPerm = new TextView(this);
            noPerm.setText(R.string.miniapp_manage_no_permissions);
            noPerm.setTextAppearance(R.style.MiniApp_TextAppearance_Caption);
            container.addView(noPerm);
        } else {
            LayoutInflater inflater = LayoutInflater.from(this);
            for (MiniAppRegistry.PermissionEntry perm : perms) {
                View row = inflater.inflate(R.layout.miniapp_permission_row,
                        container, false);
                TextView permName = row.findViewById(R.id.perm_name);
                TextView permState = row.findViewById(R.id.perm_state);

                permName.setText(MiniAppPermissionManager.getFriendlyPermissionName(
                        perm.permission));
                permState.setText(perm.state.value);
                permState.setTextColor(
                        perm.state == MiniAppRegistry.PermissionState.GRANTED
                                ? getColor(R.color.material_color_primary)
                                : resolveColorAttr(android.R.attr.textColorSecondary,
                                        0xFF757575));
                container.addView(row);
            }
        }

        // Storage section
        long storageBytes = MiniAppStorage.getInstance(this).getStorageSize(appId);
        TextView storageHeader = new TextView(this);
        storageHeader.setText(getString(R.string.miniapp_manage_storage,
                formatBytes(storageBytes)));
        storageHeader.setTextAppearance(R.style.MiniApp_TextAppearance_Body);
        storageHeader.setPadding(0, dpToPx(16), 0, dpToPx(8));
        container.addView(storageHeader);

        // Actions
        Button clearBtn = new Button(this, null, 0, R.style.MiniApp_Button_Secondary);
        clearBtn.setText(R.string.miniapp_clear_all_data);
        clearBtn.setOnClickListener(v -> {
            MiniAppStorage.getInstance(this).clearApp(appId);
            mRegistry.revokeAllPermissions(appId);
            Toast.makeText(this, R.string.miniapp_manage_data_cleared,
                    Toast.LENGTH_SHORT).show();
            recreate();
        });
        container.addView(clearBtn);

        Button updateBtn = new Button(this, null, 0, R.style.MiniApp_Button_Secondary);
        updateBtn.setText(R.string.miniapp_check_updates);
        LinearLayout.LayoutParams updateParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        updateParams.topMargin = dpToPx(8);
        updateBtn.setLayoutParams(updateParams);
        updateBtn.setOnClickListener(v -> {
            MiniAppInstaller.getInstance(this).checkForUpdate(appId,
                    new MiniAppInstaller.UpdateCallback() {
                        @Override
                        public void onUpdateAvailable(MiniAppInfo updated) {
                            Toast.makeText(MiniAppManageActivity.this,
                                    R.string.miniapp_manage_updated,
                                    Toast.LENGTH_SHORT).show();
                            recreate();
                        }

                        @Override
                        public void onUpToDate() {
                            Toast.makeText(MiniAppManageActivity.this,
                                    R.string.miniapp_manage_up_to_date,
                                    Toast.LENGTH_SHORT).show();
                        }

                        @Override
                        public void onError(String message) {
                            Toast.makeText(MiniAppManageActivity.this,
                                    getString(R.string.miniapp_manage_update_failed, message),
                                    Toast.LENGTH_SHORT).show();
                        }
                    });
        });
        container.addView(updateBtn);

        Button uninstallBtn = new Button(this, null, 0, R.style.MiniApp_Button_Danger);
        uninstallBtn.setText(R.string.miniapp_uninstall);
        LinearLayout.LayoutParams uninstallParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        uninstallParams.topMargin = dpToPx(8);
        uninstallBtn.setLayoutParams(uninstallParams);
        uninstallBtn.setOnClickListener(v -> {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.miniapp_uninstall_confirm_title,
                            app.getDisplayName()))
                    .setMessage(R.string.miniapp_uninstall_confirm_message)
                    .setPositiveButton(R.string.miniapp_uninstall, (d, w) -> {
                        MiniAppInstaller.getInstance(this).uninstall(appId);
                        Toast.makeText(this, R.string.miniapp_uninstalled,
                                Toast.LENGTH_SHORT).show();
                        finish();
                    })
                    .setNegativeButton(R.string.miniapp_cancel, null)
                    .show();
        });
        container.addView(uninstallBtn);
    }

    private int resolveColorAttr(int attr, int fallback) {
        TypedValue tv = new TypedValue();
        if (getTheme().resolveAttribute(attr, tv, true)) {
            return tv.data;
        }
        return fallback;
    }

    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
