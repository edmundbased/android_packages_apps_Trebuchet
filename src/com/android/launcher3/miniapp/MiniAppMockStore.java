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

import java.util.ArrayList;
import java.util.List;

/**
 * Provides a static catalog of mock mini-apps for the store when the
 * remote index is unreachable. Each entry mirrors the JSON format
 * returned by the real store index (name, description, manifest_url,
 * developer, category).
 */
public final class MiniAppMockStore {

    private MiniAppMockStore() {}

    /** A single store catalog entry. */
    public static class StoreEntry {
        public final String name;
        public final String description;
        public final String manifestUrl;
        public final String developer;
        public final String category;

        StoreEntry(String name, String description, String manifestUrl,
                String developer, String category) {
            this.name = name;
            this.description = description;
            this.manifestUrl = manifestUrl;
            this.developer = developer;
            this.category = category;
        }
    }

    /** Returns the full mock catalog (8 apps). */
    public static List<StoreEntry> getMockCatalog() {
        List<StoreEntry> catalog = new ArrayList<>();

        catalog.add(new StoreEntry(
                "QuickCalc",
                "Fast calculator with unit conversion and history",
                "https://quickcalc.example.com/manifest.json",
                "GrandiOS Labs",
                "Productivity"));

        catalog.add(new StoreEntry(
                "NoteFlash",
                "Lightweight markdown note-taking with instant sync",
                "https://noteflash.example.com/manifest.json",
                "Flashnote Inc.",
                "Productivity"));

        catalog.add(new StoreEntry(
                "SkyView Weather",
                "Real-time forecasts, radar, and severe weather alerts",
                "https://skyview.example.com/manifest.json",
                "WeatherStack",
                "Utilities"));

        catalog.add(new StoreEntry(
                "CoinSwap",
                "Live currency conversion with offline caching",
                "https://coinswap.example.com/manifest.json",
                "FinTools",
                "Finance"));

        catalog.add(new StoreEntry(
                "FocusTimer",
                "Pomodoro timer with session stats and break reminders",
                "https://focustimer.example.com/manifest.json",
                "DeepWork Studio",
                "Productivity"));

        catalog.add(new StoreEntry(
                "ScanSnap QR",
                "QR code and barcode scanner with history",
                "https://scansnap.example.com/manifest.json",
                "ScanTech",
                "Utilities"));

        catalog.add(new StoreEntry(
                "HabitLoop",
                "Daily habit tracker with streaks and insights",
                "https://habitloop.example.com/manifest.json",
                "LoopLabs",
                "Health & Fitness"));

        catalog.add(new StoreEntry(
                "Chromatic",
                "Color picker from camera and curated palettes",
                "https://chromatic.example.com/manifest.json",
                "PixelForge",
                "Design"));

        return catalog;
    }
}
