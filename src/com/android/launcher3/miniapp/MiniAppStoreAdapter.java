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

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import java.net.URL;
import java.util.List;

/**
 * RecyclerView adapter for the mini-app store catalog.
 * Displays each {@link MiniAppMockStore.StoreEntry} as a material card
 * with icon placeholder, name, category badge, developer, description,
 * and install/installed button.
 */
public class MiniAppStoreAdapter
        extends RecyclerView.Adapter<MiniAppStoreAdapter.ViewHolder> {

    private final List<MiniAppMockStore.StoreEntry> mEntries;
    private final MiniAppRegistry mRegistry;

    public MiniAppStoreAdapter(List<MiniAppMockStore.StoreEntry> entries,
            MiniAppRegistry registry) {
        mEntries = entries;
        mRegistry = registry;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.miniapp_store_card, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        MiniAppMockStore.StoreEntry entry = mEntries.get(position);
        Context context = holder.itemView.getContext();

        // Icon placeholder: first letter of app name
        holder.iconLetter.setText(entry.name.substring(0, 1).toUpperCase());

        holder.appName.setText(entry.name);
        holder.appCategory.setText(entry.category);
        holder.appDeveloper.setText(
                context.getString(R.string.miniapp_store_developer, entry.developer));
        holder.appDescription.setText(entry.description);

        // Check if already installed
        String appId = deriveAppId(entry.manifestUrl);
        boolean installed = appId != null && mRegistry.isInstalled(appId);

        if (installed) {
            holder.btnInstall.setText(R.string.miniapp_installed);
            holder.btnInstall.setEnabled(false);
        } else {
            holder.btnInstall.setText(R.string.miniapp_install);
            holder.btnInstall.setEnabled(true);
            holder.btnInstall.setOnClickListener(v -> {
                Intent intent = new Intent(context, MiniAppInstallActivity.class);
                intent.putExtra(MiniAppInstallActivity.EXTRA_MANIFEST_URL, entry.manifestUrl);
                context.startActivity(intent);
            });
        }
    }

    @Override
    public int getItemCount() {
        return mEntries.size();
    }

    private static String deriveAppId(String manifestUrl) {
        try {
            return new URL(manifestUrl).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView iconLetter;
        final TextView appName;
        final TextView appCategory;
        final TextView appDeveloper;
        final TextView appDescription;
        final Button btnInstall;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            iconLetter = itemView.findViewById(R.id.icon_letter);
            appName = itemView.findViewById(R.id.app_name);
            appCategory = itemView.findViewById(R.id.app_category);
            appDeveloper = itemView.findViewById(R.id.app_developer);
            appDescription = itemView.findViewById(R.id.app_description);
            btnInstall = itemView.findViewById(R.id.btn_install);
        }
    }
}
