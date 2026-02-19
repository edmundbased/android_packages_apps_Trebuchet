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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.util.List;

/**
 * RecyclerView adapter for the mini-app manage list.
 * Displays each installed {@link MiniAppInfo} as a material card
 * with name, domain, storage size, permissions count, and action buttons.
 */
public class MiniAppManageAdapter
        extends RecyclerView.Adapter<MiniAppManageAdapter.ViewHolder> {

    /** Callback interface for card action buttons. */
    public interface ActionCallback {
        void onClearData(MiniAppInfo app);
        void onUninstall(MiniAppInfo app);
    }

    private final List<MiniAppInfo> mApps;
    private final MiniAppRegistry mRegistry;
    private final ActionCallback mCallback;

    public MiniAppManageAdapter(List<MiniAppInfo> apps, MiniAppRegistry registry,
            ActionCallback callback) {
        mApps = apps;
        mRegistry = registry;
        mCallback = callback;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.miniapp_manage_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MiniAppInfo app = mApps.get(position);
        Context context = holder.itemView.getContext();

        // Icon letter
        String displayName = app.getDisplayName();
        holder.iconLetter.setText(displayName.substring(0, 1).toUpperCase());

        holder.appName.setText(displayName);
        holder.appDomain.setText(app.appId);

        // Storage
        long storageBytes = MiniAppStorage.getInstance(context).getStorageSize(app.appId);
        holder.appStorage.setText(
                context.getString(R.string.miniapp_manage_storage, formatBytes(storageBytes)));

        // Permissions
        List<MiniAppRegistry.PermissionEntry> perms = mRegistry.getPermissions(app.appId);
        long grantedCount = perms.stream()
                .filter(p -> p.state == MiniAppRegistry.PermissionState.GRANTED)
                .count();
        holder.appPermissions.setText(
                context.getString(R.string.miniapp_manage_permissions_summary,
                        (int) grantedCount, perms.size()));

        // Action buttons
        holder.btnClear.setOnClickListener(v -> mCallback.onClearData(app));
        holder.btnUninstall.setOnClickListener(v -> mCallback.onUninstall(app));
    }

    @Override
    public int getItemCount() {
        return mApps.size();
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView iconLetter;
        final TextView appName;
        final TextView appDomain;
        final TextView appStorage;
        final TextView appPermissions;
        final Button btnClear;
        final Button btnUninstall;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iconLetter = itemView.findViewById(R.id.icon_letter);
            appName = itemView.findViewById(R.id.app_name);
            appDomain = itemView.findViewById(R.id.app_domain);
            appStorage = itemView.findViewById(R.id.app_storage);
            appPermissions = itemView.findViewById(R.id.app_permissions);
            btnClear = itemView.findViewById(R.id.btn_clear);
            btnUninstall = itemView.findViewById(R.id.btn_uninstall);
        }
    }
}
