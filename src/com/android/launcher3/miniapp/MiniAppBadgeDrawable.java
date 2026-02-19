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

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Wraps an existing icon drawable and overlays a small indicator dot
 * in the bottom-right corner to indicate this is a mini-app (not a native app).
 *
 * The dot is a small blue filled circle, similar to PWA indicators.
 */
public class MiniAppBadgeDrawable extends Drawable {

    private static final int BADGE_COLOR = 0xFF2196F3; // Material Blue 500
    private static final int BADGE_BORDER_COLOR = 0xFFFFFFFF; // White border
    private static final float BADGE_RADIUS_FRACTION = 0.08f; // 8% of icon size
    private static final float BADGE_BORDER_FRACTION = 0.02f; // 2% border

    private final Drawable mBase;
    private final Paint mBadgePaint;
    private final Paint mBorderPaint;

    public MiniAppBadgeDrawable(@NonNull Drawable base) {
        mBase = base;

        mBadgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBadgePaint.setColor(BADGE_COLOR);
        mBadgePaint.setStyle(Paint.Style.FILL);

        mBorderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        mBorderPaint.setColor(BADGE_BORDER_COLOR);
        mBorderPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onBoundsChange(@NonNull Rect bounds) {
        super.onBoundsChange(bounds);
        mBase.setBounds(bounds);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        // Draw the base icon
        mBase.draw(canvas);

        // Draw the badge dot in bottom-right corner
        Rect bounds = getBounds();
        float size = Math.min(bounds.width(), bounds.height());
        float badgeRadius = size * BADGE_RADIUS_FRACTION;
        float borderWidth = size * BADGE_BORDER_FRACTION;

        float cx = bounds.right - badgeRadius - borderWidth;
        float cy = bounds.bottom - badgeRadius - borderWidth;

        // White border circle
        canvas.drawCircle(cx, cy, badgeRadius + borderWidth, mBorderPaint);
        // Blue filled circle
        canvas.drawCircle(cx, cy, badgeRadius, mBadgePaint);
    }

    @Override
    public int getIntrinsicWidth() {
        return mBase.getIntrinsicWidth();
    }

    @Override
    public int getIntrinsicHeight() {
        return mBase.getIntrinsicHeight();
    }

    @Override
    public void setAlpha(int alpha) {
        mBase.setAlpha(alpha);
        mBadgePaint.setAlpha(alpha);
        mBorderPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        mBase.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
