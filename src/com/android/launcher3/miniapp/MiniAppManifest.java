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

import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Parsed representation of a MiniApp manifest (extended PWA manifest).
 *
 * Standard PWA fields: name, short_name, start_url, display, icons, etc.
 * Extended fields: native_permissions, agent, runtime, store.
 */
public class MiniAppManifest {

    // --- Standard PWA fields ---
    @NonNull public final String name;
    @Nullable public final String shortName;
    @NonNull public final String startUrl;
    @Nullable public final String scope;
    @NonNull public final String display; // "standalone", "fullscreen", "minimal-ui", "browser"
    @Nullable public final String backgroundColor;
    @Nullable public final String themeColor;
    @Nullable public final String description;
    @NonNull public final List<Icon> icons;

    // --- Extended: Native permissions ---
    @NonNull public final List<String> nativePermissions;

    // --- Extended: Agent capabilities ---
    @NonNull public final List<AgentCapability> agentCapabilities;
    @NonNull public final List<String> agentDataProvides;

    // --- Extended: Runtime configuration ---
    public final int maxCacheMb;
    public final boolean offlineCapable;
    @NonNull public final List<String> preloadUrls;
    @NonNull public final String sandbox; // "strict", "permissive", "custom"

    // --- Extended: Share target ---
    @Nullable public final ShareTarget shareTarget;

    // --- Extended: Store metadata ---
    @Nullable public final String category;
    @NonNull public final List<String> tags;
    @NonNull public final List<String> screenshots;
    @Nullable public final String privacyPolicy;
    @Nullable public final String contentRating;
    @Nullable public final DeveloperInfo developer;

    private MiniAppManifest(Builder builder) {
        this.name = builder.name;
        this.shortName = builder.shortName;
        this.startUrl = builder.startUrl;
        this.scope = builder.scope;
        this.display = builder.display;
        this.backgroundColor = builder.backgroundColor;
        this.themeColor = builder.themeColor;
        this.description = builder.description;
        this.icons = Collections.unmodifiableList(builder.icons);
        this.nativePermissions = Collections.unmodifiableList(builder.nativePermissions);
        this.agentCapabilities = Collections.unmodifiableList(builder.agentCapabilities);
        this.agentDataProvides = Collections.unmodifiableList(builder.agentDataProvides);
        this.maxCacheMb = builder.maxCacheMb;
        this.offlineCapable = builder.offlineCapable;
        this.preloadUrls = Collections.unmodifiableList(builder.preloadUrls);
        this.sandbox = builder.sandbox;
        this.shareTarget = builder.shareTarget;
        this.category = builder.category;
        this.tags = Collections.unmodifiableList(builder.tags);
        this.screenshots = Collections.unmodifiableList(builder.screenshots);
        this.privacyPolicy = builder.privacyPolicy;
        this.contentRating = builder.contentRating;
        this.developer = builder.developer;
    }

    /**
     * Derives a unique app ID from the manifest's start_url origin + path.
     * e.g. "https://quickpay.app/mini" → "quickpay.app/mini"
     */
    @NonNull
    public String getAppId() {
        String url = startUrl;
        // Strip protocol
        if (url.startsWith("https://")) {
            url = url.substring(8);
        } else if (url.startsWith("http://")) {
            url = url.substring(7);
        }
        // Strip trailing slash
        if (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }

    /**
     * Returns the best icon URL for the given minimum size, or null if none.
     */
    @Nullable
    public String getBestIconUrl(int minSize) {
        Icon best = null;
        int bestSize = 0;
        for (Icon icon : icons) {
            if (icon.maxSize >= minSize && (best == null || icon.maxSize < bestSize)) {
                best = icon;
                bestSize = icon.maxSize;
            }
        }
        // Fallback to largest available
        if (best == null) {
            for (Icon icon : icons) {
                if (best == null || icon.maxSize > bestSize) {
                    best = icon;
                    bestSize = icon.maxSize;
                }
            }
        }
        return best != null ? best.src : null;
    }

    /**
     * Resolves a relative URL against the start_url origin.
     */
    @NonNull
    public String resolveUrl(@NonNull String relativeOrAbsolute) {
        if (relativeOrAbsolute.startsWith("http://") || relativeOrAbsolute.startsWith("https://")) {
            return relativeOrAbsolute;
        }
        // Extract origin from start_url
        String origin = startUrl;
        int schemeEnd = origin.indexOf("://");
        if (schemeEnd >= 0) {
            int pathStart = origin.indexOf('/', schemeEnd + 3);
            if (pathStart >= 0) {
                origin = origin.substring(0, pathStart);
            }
        }
        if (relativeOrAbsolute.startsWith("/")) {
            return origin + relativeOrAbsolute;
        }
        return origin + "/" + relativeOrAbsolute;
    }

    // --- Parsing ---

    /**
     * Parse a MiniApp manifest from JSON string.
     * @throws MiniAppManifestException if required fields are missing or invalid.
     */
    @NonNull
    public static MiniAppManifest fromJson(@NonNull String json) throws MiniAppManifestException {
        try {
            return fromJson(new JSONObject(json));
        } catch (JSONException e) {
            throw new MiniAppManifestException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Parse a MiniApp manifest from a JSONObject.
     * @throws MiniAppManifestException if required fields are missing or invalid.
     */
    @NonNull
    public static MiniAppManifest fromJson(@NonNull JSONObject json)
            throws MiniAppManifestException {
        Builder b = new Builder();

        // Required fields
        b.name = requireString(json, "name");
        b.startUrl = requireString(json, "start_url");

        // Optional standard PWA fields
        b.shortName = json.optString("short_name", null);
        b.scope = json.optString("scope", null);
        b.display = json.optString("display", "standalone");
        b.backgroundColor = json.optString("background_color", null);
        b.themeColor = json.optString("theme_color", null);
        b.description = json.optString("description", null);

        // Icons
        JSONArray iconsArr = json.optJSONArray("icons");
        if (iconsArr != null) {
            for (int i = 0; i < iconsArr.length(); i++) {
                b.icons.add(Icon.fromJson(iconsArr.getJSONObject(i)));
            }
        }

        // Native permissions
        JSONArray permsArr = json.optJSONArray("native_permissions");
        if (permsArr != null) {
            for (int i = 0; i < permsArr.length(); i++) {
                b.nativePermissions.add(permsArr.getString(i));
            }
        }

        // Agent
        JSONObject agentObj = json.optJSONObject("agent");
        if (agentObj != null) {
            JSONArray caps = agentObj.optJSONArray("capabilities");
            if (caps != null) {
                for (int i = 0; i < caps.length(); i++) {
                    b.agentCapabilities.add(AgentCapability.fromJson(caps.getJSONObject(i)));
                }
            }
            JSONArray provides = agentObj.optJSONArray("data_provides");
            if (provides != null) {
                for (int i = 0; i < provides.length(); i++) {
                    b.agentDataProvides.add(provides.getString(i));
                }
            }
        }

        // Runtime
        JSONObject runtimeObj = json.optJSONObject("runtime");
        if (runtimeObj != null) {
            b.maxCacheMb = runtimeObj.optInt("max_cache_mb", 50);
            b.offlineCapable = runtimeObj.optBoolean("offline_capable", false);
            b.sandbox = runtimeObj.optString("sandbox", "strict");
            JSONArray preload = runtimeObj.optJSONArray("preload_urls");
            if (preload != null) {
                for (int i = 0; i < preload.length(); i++) {
                    b.preloadUrls.add(preload.getString(i));
                }
            }
        }

        // Share target
        JSONObject shareObj = json.optJSONObject("share_target");
        if (shareObj != null) {
            b.shareTarget = ShareTarget.fromJson(shareObj);
        }

        // Store metadata
        JSONObject storeObj = json.optJSONObject("store");
        if (storeObj != null) {
            b.category = storeObj.optString("category", null);
            b.privacyPolicy = storeObj.optString("privacy_policy", null);
            b.contentRating = storeObj.optString("content_rating", null);
            JSONArray tagsArr = storeObj.optJSONArray("tags");
            if (tagsArr != null) {
                for (int i = 0; i < tagsArr.length(); i++) {
                    b.tags.add(tagsArr.getString(i));
                }
            }
            JSONArray ssArr = storeObj.optJSONArray("screenshots");
            if (ssArr != null) {
                for (int i = 0; i < ssArr.length(); i++) {
                    b.screenshots.add(ssArr.getString(i));
                }
            }
            JSONObject devObj = storeObj.optJSONObject("developer");
            if (devObj != null) {
                b.developer = DeveloperInfo.fromJson(devObj);
            }
        }

        return b.build();
    }

    private static String requireString(JSONObject json, String key)
            throws MiniAppManifestException {
        String val = json.optString(key, null);
        if (TextUtils.isEmpty(val)) {
            throw new MiniAppManifestException("Missing required field: " + key);
        }
        return val;
    }

    // --- Serialization ---

    @NonNull
    public JSONObject toJson() throws JSONException {
        JSONObject json = new JSONObject();
        json.put("name", name);
        json.putOpt("short_name", shortName);
        json.put("start_url", startUrl);
        json.putOpt("scope", scope);
        json.put("display", display);
        json.putOpt("background_color", backgroundColor);
        json.putOpt("theme_color", themeColor);
        json.putOpt("description", description);

        if (!icons.isEmpty()) {
            JSONArray arr = new JSONArray();
            for (Icon icon : icons) arr.put(icon.toJson());
            json.put("icons", arr);
        }

        if (!nativePermissions.isEmpty()) {
            json.put("native_permissions", new JSONArray(nativePermissions));
        }

        if (!agentCapabilities.isEmpty() || !agentDataProvides.isEmpty()) {
            JSONObject agent = new JSONObject();
            if (!agentCapabilities.isEmpty()) {
                JSONArray caps = new JSONArray();
                for (AgentCapability c : agentCapabilities) caps.put(c.toJson());
                agent.put("capabilities", caps);
            }
            if (!agentDataProvides.isEmpty()) {
                agent.put("data_provides", new JSONArray(agentDataProvides));
            }
            json.put("agent", agent);
        }

        JSONObject runtime = new JSONObject();
        runtime.put("max_cache_mb", maxCacheMb);
        runtime.put("offline_capable", offlineCapable);
        runtime.put("sandbox", sandbox);
        if (!preloadUrls.isEmpty()) {
            runtime.put("preload_urls", new JSONArray(preloadUrls));
        }
        json.put("runtime", runtime);

        if (shareTarget != null) {
            json.put("share_target", shareTarget.toJson());
        }

        if (category != null || !tags.isEmpty() || developer != null) {
            JSONObject store = new JSONObject();
            store.putOpt("category", category);
            store.putOpt("privacy_policy", privacyPolicy);
            store.putOpt("content_rating", contentRating);
            if (!tags.isEmpty()) store.put("tags", new JSONArray(tags));
            if (!screenshots.isEmpty()) store.put("screenshots", new JSONArray(screenshots));
            if (developer != null) store.put("developer", developer.toJson());
            json.put("store", store);
        }

        return json;
    }

    // --- Inner classes ---

    public static class Icon {
        @NonNull public final String src;
        @NonNull public final String sizes; // e.g. "192x192"
        @Nullable public final String type; // e.g. "image/png"
        public final int maxSize; // parsed max dimension

        public Icon(@NonNull String src, @NonNull String sizes, @Nullable String type) {
            this.src = src;
            this.sizes = sizes;
            this.type = type;
            this.maxSize = parseMaxSize(sizes);
        }

        private static int parseMaxSize(String sizes) {
            int max = 0;
            for (String part : sizes.split("\\s+")) {
                String[] dims = part.split("x");
                for (String d : dims) {
                    try {
                        max = Math.max(max, Integer.parseInt(d.trim()));
                    } catch (NumberFormatException ignored) {}
                }
            }
            return max;
        }

        static Icon fromJson(JSONObject json) throws JSONException {
            return new Icon(
                    json.getString("src"),
                    json.optString("sizes", "0x0"),
                    json.optString("type", null)
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("src", src);
            o.put("sizes", sizes);
            o.putOpt("type", type);
            return o;
        }
    }

    public static class AgentCapability {
        @NonNull public final String name;
        @NonNull public final String description;
        @NonNull public final JSONObject parameters;
        public final boolean requiresConfirmation;

        public AgentCapability(@NonNull String name, @NonNull String description,
                @NonNull JSONObject parameters, boolean requiresConfirmation) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
            this.requiresConfirmation = requiresConfirmation;
        }

        static AgentCapability fromJson(JSONObject json) throws JSONException {
            return new AgentCapability(
                    json.getString("name"),
                    json.optString("description", ""),
                    json.optJSONObject("parameters") != null
                            ? json.getJSONObject("parameters") : new JSONObject(),
                    json.optBoolean("requires_confirmation", false)
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.put("description", description);
            o.put("parameters", parameters);
            o.put("requires_confirmation", requiresConfirmation);
            return o;
        }
    }

    public static class DeveloperInfo {
        @NonNull public final String name;
        @Nullable public final String url;
        @Nullable public final String email;
        public final boolean verified;

        public DeveloperInfo(@NonNull String name, @Nullable String url,
                @Nullable String email, boolean verified) {
            this.name = name;
            this.url = url;
            this.email = email;
            this.verified = verified;
        }

        static DeveloperInfo fromJson(JSONObject json) throws JSONException {
            return new DeveloperInfo(
                    json.getString("name"),
                    json.optString("url", null),
                    json.optString("email", null),
                    json.optBoolean("verified", false)
            );
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("name", name);
            o.putOpt("url", url);
            o.putOpt("email", email);
            o.put("verified", verified);
            return o;
        }
    }

    public static class ShareTarget {
        @NonNull public final String action; // URL path to navigate to
        @NonNull public final String method; // "GET" or "POST"
        @Nullable public final String titleParam;  // query param name for shared title
        @Nullable public final String textParam;   // query param name for shared text
        @Nullable public final String urlParam;    // query param name for shared URL

        public ShareTarget(@NonNull String action, @NonNull String method,
                @Nullable String titleParam, @Nullable String textParam,
                @Nullable String urlParam) {
            this.action = action;
            this.method = method;
            this.titleParam = titleParam;
            this.textParam = textParam;
            this.urlParam = urlParam;
        }

        static ShareTarget fromJson(JSONObject json) throws JSONException {
            String action = json.getString("action");
            String method = json.optString("method", "GET");
            JSONObject params = json.optJSONObject("params");
            String titleP = null, textP = null, urlP = null;
            if (params != null) {
                titleP = params.optString("title", null);
                textP = params.optString("text", null);
                urlP = params.optString("url", null);
            }
            return new ShareTarget(action, method, titleP, textP, urlP);
        }

        JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("action", action);
            o.put("method", method);
            if (titleParam != null || textParam != null || urlParam != null) {
                JSONObject params = new JSONObject();
                params.putOpt("title", titleParam);
                params.putOpt("text", textParam);
                params.putOpt("url", urlParam);
                o.put("params", params);
            }
            return o;
        }
    }

    // --- Builder ---

    private static class Builder {
        String name;
        String shortName;
        String startUrl;
        String scope;
        String display = "standalone";
        String backgroundColor;
        String themeColor;
        String description;
        List<Icon> icons = new ArrayList<>();
        List<String> nativePermissions = new ArrayList<>();
        List<AgentCapability> agentCapabilities = new ArrayList<>();
        List<String> agentDataProvides = new ArrayList<>();
        int maxCacheMb = 50;
        boolean offlineCapable = false;
        List<String> preloadUrls = new ArrayList<>();
        String sandbox = "strict";
        String category;
        List<String> tags = new ArrayList<>();
        List<String> screenshots = new ArrayList<>();
        String privacyPolicy;
        String contentRating;
        DeveloperInfo developer;
        ShareTarget shareTarget;

        MiniAppManifest build() throws MiniAppManifestException {
            if (TextUtils.isEmpty(name)) {
                throw new MiniAppManifestException("name is required");
            }
            if (TextUtils.isEmpty(startUrl)) {
                throw new MiniAppManifestException("start_url is required");
            }
            if (!startUrl.startsWith("https://") && !startUrl.startsWith("http://")) {
                throw new MiniAppManifestException(
                        "start_url must be an absolute URL with http(s) scheme");
            }
            if (!("strict".equals(sandbox) || "permissive".equals(sandbox)
                    || "custom".equals(sandbox))) {
                throw new MiniAppManifestException(
                        "sandbox must be 'strict', 'permissive', or 'custom'");
            }
            return new MiniAppManifest(this);
        }
    }
}
