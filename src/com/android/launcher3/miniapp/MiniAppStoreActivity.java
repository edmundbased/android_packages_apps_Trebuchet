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
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toolbar;

import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.launcher3.R;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Browsable mini-app store that fetches a catalog from a remote JSON index.
 *
 * The store index URL is configurable. Default: store.based.one/miniapps/index.json
 * Each entry has: name, description, manifest_url, icon_url, developer, category.
 *
 * Users can browse, preview, and install mini-apps directly from this UI.
 * Falls back to mock catalog when remote index is unreachable.
 */
public class MiniAppStoreActivity extends Activity {

    private static final String TAG = "MiniAppStore";

    // Default store index — overridable via intent extra
    private static final String DEFAULT_STORE_URL =
            "https://store.based.one/miniapps/index.json";
    public static final String EXTRA_STORE_URL = "store_url";

    private RecyclerView mRecyclerView;
    private ProgressBar mProgress;
    private TextView mEmptyView;
    private String mStoreUrl;
    private MiniAppStoreAdapter mAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.miniapp_activity_store);

        // Toolbar
        Toolbar toolbar = findViewById(R.id.action_bar);
        setActionBar(toolbar);
        toolbar.setNavigationIcon(android.R.drawable.ic_menu_close_clear_cancel);
        toolbar.setNavigationOnClickListener(v -> finish());

        mRecyclerView = findViewById(R.id.store_list);
        mProgress = findViewById(R.id.store_progress);
        mEmptyView = findViewById(R.id.store_empty);

        mRecyclerView.setLayoutManager(new LinearLayoutManager(this));

        Intent intent = getIntent();
        mStoreUrl = intent != null ? intent.getStringExtra(EXTRA_STORE_URL) : null;
        if (TextUtils.isEmpty(mStoreUrl)) {
            mStoreUrl = DEFAULT_STORE_URL;
        }

        fetchCatalog();
    }

    private void fetchCatalog() {
        mProgress.setVisibility(View.VISIBLE);
        mEmptyView.setVisibility(View.GONE);

        new Thread(() -> {
            try {
                URL url = new URL(mStoreUrl);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(15000);
                conn.setRequestProperty("User-Agent", "MiniAppStore/1.0 BasedOS");

                StringBuilder sb = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(conn.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line);
                    }
                }
                conn.disconnect();

                JSONObject catalog = new JSONObject(sb.toString());
                JSONArray apps = catalog.getJSONArray("apps");

                List<MiniAppMockStore.StoreEntry> entries = new ArrayList<>();
                for (int i = 0; i < apps.length(); i++) {
                    JSONObject app = apps.getJSONObject(i);
                    entries.add(new MiniAppMockStore.StoreEntry(
                            app.getString("name"),
                            app.optString("description", ""),
                            app.getString("manifest_url"),
                            app.optString("developer", "Unknown"),
                            app.optString("category", "General")));
                }

                runOnUiThread(() -> showCatalog(entries));
            } catch (Exception e) {
                Log.w(TAG, "Remote store unavailable, falling back to mock catalog", e);
                // Fall back to mock catalog instead of showing error
                List<MiniAppMockStore.StoreEntry> mock =
                        MiniAppMockStore.getMockCatalog();
                runOnUiThread(() -> showCatalog(mock));
            }
        }).start();
    }

    private void showCatalog(List<MiniAppMockStore.StoreEntry> entries) {
        mProgress.setVisibility(View.GONE);

        if (entries.isEmpty()) {
            mEmptyView.setVisibility(View.VISIBLE);
            return;
        }
        mEmptyView.setVisibility(View.GONE);

        MiniAppRegistry registry = MiniAppRegistry.getInstance(this);
        mAdapter = new MiniAppStoreAdapter(entries, registry);
        mRecyclerView.setAdapter(mAdapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Refresh installed state when coming back from install
        if (mAdapter != null) {
            mAdapter.notifyDataSetChanged();
        }
    }
}
