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

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.android.launcher3.R;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.popup.SystemShortcut;
import com.android.launcher3.views.ActivityContext;

/**
 * System shortcuts (long-press popup actions) for mini-apps.
 *
 * Provides: Mini-App Info, Uninstall, Permissions.
 */
public class MiniAppShortcut {

    /**
     * Factory that shows "Mini-App Info" — opens the manage activity for this app.
     */
    public static final SystemShortcut.Factory<ActivityContext> MINIAPP_INFO =
            (context, itemInfo, originalView) -> {
                if (!(itemInfo instanceof MiniAppInfo)) return null;
                return new Info(context, itemInfo, originalView);
            };

    /**
     * Factory that shows "Uninstall Mini-App" — removes the mini-app.
     */
    public static final SystemShortcut.Factory<ActivityContext> MINIAPP_UNINSTALL =
            (context, itemInfo, originalView) -> {
                if (!(itemInfo instanceof MiniAppInfo)) return null;
                return new Uninstall(context, itemInfo, originalView);
            };

    // --- Info shortcut ---

    private static class Info<T extends ActivityContext> extends SystemShortcut<T> {
        Info(T target, ItemInfo itemInfo, @NonNull View originalView) {
            super(R.drawable.ic_info_no_shadow,
                    R.string.miniapp_info_shortcut,
                    target, itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            MiniAppInfo miniApp = (MiniAppInfo) mItemInfo;
            Context ctx = view.getContext();
            Intent intent = new Intent(ctx, MiniAppManageActivity.class);
            intent.putExtra(MiniAppManageActivity.EXTRA_APP_ID, miniApp.appId);
            ctx.startActivity(intent);
            com.android.launcher3.AbstractFloatingView.closeAllOpenViews(mTarget);
        }
    }

    // --- Uninstall shortcut ---

    private static class Uninstall<T extends ActivityContext> extends SystemShortcut<T> {
        Uninstall(T target, ItemInfo itemInfo, @NonNull View originalView) {
            super(R.drawable.ic_uninstall_no_shadow,
                    R.string.miniapp_uninstall_shortcut,
                    target, itemInfo, originalView);
        }

        @Override
        public void onClick(View view) {
            MiniAppInfo miniApp = (MiniAppInfo) mItemInfo;
            Context ctx = view.getContext();

            new AlertDialog.Builder(ctx)
                    .setTitle(ctx.getString(R.string.miniapp_uninstall_confirm_title,
                            miniApp.getDisplayName()))
                    .setMessage(R.string.miniapp_uninstall_confirm_message)
                    .setPositiveButton(R.string.miniapp_uninstall, (dialog, which) -> {
                        MiniAppInstaller.getInstance(ctx).uninstall(miniApp.appId);
                        Toast.makeText(ctx,
                                ctx.getString(R.string.miniapp_uninstalled_name,
                                        miniApp.getDisplayName()),
                                Toast.LENGTH_SHORT).show();
                        com.android.launcher3.AbstractFloatingView.closeAllOpenViews(mTarget);
                    })
                    .setNegativeButton(R.string.miniapp_cancel, null)
                    .show();
        }
    }
}
