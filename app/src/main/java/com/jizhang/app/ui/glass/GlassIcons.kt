/*
 * Copyright 2025 Kyant
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */
package com.jizhang.app.ui.glass

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * 与现有 `res/drawable/ic_nav_*.xml` 对应的 Compose 矢量图。
 * 使用 ImageVector 而不是额外引入 material-icons 依赖，避免拉入未缓存的包。
 */
internal val IconLedger: ImageVector by lazy {
    icon("Ledger") {
        // Material Symbols: receipt_long（轮廓版）。未选中时用描边，
        // 在玻璃底栏里比实心收据更接近 Google 导航栏的观感。
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 3f)
            lineTo(7f, 5f)
            lineTo(9f, 3f)
            lineTo(11f, 5f)
            lineTo(13f, 3f)
            lineTo(15f, 5f)
            lineTo(17f, 3f)
            lineTo(19f, 5f)
            verticalLineTo(19f)
            lineTo(17f, 21f)
            lineTo(15f, 19f)
            lineTo(13f, 21f)
            lineTo(11f, 19f)
            lineTo(9f, 21f)
            lineTo(7f, 19f)
            lineTo(5f, 21f)
            close()
            moveTo(9f, 9.5f)
            horizontalLineTo(15f)
            moveTo(9f, 13f)
            horizontalLineTo(15f)
            moveTo(9f, 16.5f)
            horizontalLineTo(13f)
        }
    }
}

/** 选中态的明细图标：实心收据，与 Material Symbols `receipt_long` 填充版一致。 */
internal val IconLedgerFilled: ImageVector by lazy {
    icon("LedgerFilled") {
        path(fill = SolidColor(Color.Black)) {
            // Material Symbols: receipt_long
            moveTo(19.5f, 3.5f)
            lineTo(18f, 2f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(15f, 2f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(12f, 2f)
            lineToRelative(-1.5f, 1.5f)
            lineTo(9f, 2f)
            lineTo(7.5f, 3.5f)
            lineTo(6f, 2f)
            verticalLineToRelative(20f)
            lineToRelative(1.5f, -1.5f)
            lineTo(9f, 22f)
            lineToRelative(1.5f, -1.5f)
            lineTo(12f, 22f)
            lineToRelative(1.5f, -1.5f)
            lineTo(15f, 22f)
            lineToRelative(1.5f, -1.5f)
            lineTo(18f, 22f)
            lineToRelative(1.5f, -1.5f)
            lineTo(21f, 22f)
            verticalLineTo(2f)
            lineToRelative(-1.5f, 1.5f)
            close()
            moveTo(19f, 19.09f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineToRelative(-1f, -0.5f)
            lineToRelative(-1f, 0.5f)
            lineTo(7f, 4.91f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            lineToRelative(1f, 0.5f)
            lineToRelative(1f, -0.5f)
            verticalLineToRelative(14.18f)
            close()
            moveTo(14f, 11f)
            horizontalLineTo(8f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(2f)
            close()
            moveTo(17f, 7f)
            horizontalLineTo(8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(9f)
            verticalLineTo(7f)
            close()
            moveTo(14f, 15f)
            horizontalLineTo(8f)
            verticalLineToRelative(2f)
            horizontalLineToRelative(6f)
            verticalLineToRelative(-2f)
            close()
        }
    }
}

internal val IconStats: ImageVector by lazy {
    icon("Stats") {
        // Material Symbols: analytics（未选中轮廓版）。
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(5f, 4f)
            horizontalLineTo(19f)
            verticalLineTo(16f)
            horizontalLineTo(5f)
            close()
            moveTo(9f, 8f)
            verticalLineTo(12f)
            moveTo(12f, 6.5f)
            verticalLineTo(12f)
            moveTo(15f, 9.5f)
            verticalLineTo(12f)
            moveTo(8f, 20f)
            lineTo(12f, 16f)
            lineTo(16f, 20f)
        }
    }
}

/**
 * 选中态的统计图标：Material Symbols `analytics` 的实心版。
 * 外框、柱形用同一路径配合 evenOdd 填充，柱间留白才不会和底色糊成一块。
 */
internal val IconStatsFilled: ImageVector by lazy {
    icon("StatsFilled") {
        path(
            fill = SolidColor(Color.Black),
            pathFillType = androidx.compose.ui.graphics.PathFillType.EvenOdd,
        ) {
            // Material Symbols: analytics
            moveTo(19f, 3f)
            horizontalLineTo(5f)
            curveToRelative(-1.1f, 0f, -2f, 0.9f, -2f, 2f)
            verticalLineToRelative(14f)
            curveToRelative(0f, 1.1f, 0.9f, 2f, 2f, 2f)
            horizontalLineToRelative(14f)
            curveToRelative(1.1f, 0f, 2f, -0.9f, 2f, -2f)
            verticalLineTo(5f)
            curveToRelative(0f, -1.1f, -0.9f, -2f, -2f, -2f)
            close()
            moveTo(9f, 17f)
            horizontalLineTo(7f)
            verticalLineToRelative(-4f)
            horizontalLineToRelative(2f)
            close()
            moveTo(13f, 17f)
            horizontalLineToRelative(-2f)
            verticalLineTo(7f)
            horizontalLineToRelative(2f)
            close()
            moveTo(17f, 17f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-6f)
            horizontalLineToRelative(2f)
            close()
        }
    }
}

internal val IconPending: ImageVector by lazy {
    icon("Pending") {
        // Material Symbols: notifications（未选中轮廓版）。
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            moveTo(6.5f, 17f)
            verticalLineTo(11f)
            curveToRelative(0f, -3f, 2.2f, -5.2f, 5.5f, -5.2f)
            curveToRelative(3.3f, 0f, 5.5f, 2.2f, 5.5f, 5.2f)
            verticalLineTo(17f)
            moveTo(5f, 17f)
            horizontalLineTo(19f)
            moveTo(10.2f, 20f)
            curveToRelative(0.5f, 0.9f, 1.2f, 1.35f, 1.8f, 1.35f)
            curveToRelative(0.6f, 0f, 1.3f, -0.45f, 1.8f, -1.35f)
        }
    }
}

/** 选中态的待确认图标：实心铃铛。 */
internal val IconPendingFilled: ImageVector by lazy {
    icon("PendingFilled") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(12f, 22f)
            curveToRelative(1.15f, 0f, 2.1f, -0.95f, 2.1f, -2.1f)
            horizontalLineTo(9.9f)
            curveToRelative(0f, 1.15f, 0.95f, 2.1f, 2.1f, 2.1f)
            close()
            moveTo(18f, 16f)
            verticalLineTo(11f)
            curveToRelative(0f, -3.07f, -1.63f, -5.64f, -4.5f, -6.32f)
            verticalLineTo(4f)
            curveToRelative(0f, -0.83f, -0.67f, -1.5f, -1.5f, -1.5f)
            curveToRelative(-0.83f, 0f, -1.5f, 0.67f, -1.5f, 1.5f)
            verticalLineTo(4.68f)
            curveTo(7.63f, 5.36f, 6f, 7.92f, 6f, 11f)
            verticalLineToRelative(5f)
            lineToRelative(-2f, 2f)
            verticalLineToRelative(1f)
            horizontalLineToRelative(16f)
            verticalLineToRelative(-1f)
            close()
        }
    }
}

internal val IconSettings: ImageVector by lazy {
    icon("Settings") {
        // Material Symbols: settings（轮廓齿轮）：同一路径只描边，
        // 外轮廓和内圈都会以 1.7 的线宽绘制，与选中态的实心版严格同形。
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.7f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        ) {
            settingsGear()
        }
    }
}

/** 选中态的设置图标：实心齿轮。 */
internal val IconSettingsFilled: ImageVector by lazy {
    icon("SettingsFilled") {
        path(fill = SolidColor(Color.Black)) {
            settingsGear()
        }
    }
}

/**
 * Material Symbols: settings 的标准齿轮路径。
 * 描边即轮廓版，填充即实心版，两者几何完全一致，切换页签时不会跳形。
 */
private fun androidx.compose.ui.graphics.vector.PathBuilder.settingsGear() {
    moveTo(19.43f, 12.98f)
    curveToRelative(0.04f, -0.32f, 0.07f, -0.65f, 0.07f, -0.98f)
    curveToRelative(0f, -0.33f, -0.03f, -0.66f, -0.07f, -0.98f)
    lineToRelative(2.11f, -1.65f)
    curveToRelative(0.19f, -0.15f, 0.24f, -0.42f, 0.12f, -0.64f)
    lineToRelative(-2f, -3.46f)
    curveToRelative(-0.12f, -0.22f, -0.37f, -0.31f, -0.6f, -0.22f)
    lineToRelative(-2.49f, 1f)
    curveToRelative(-0.52f, -0.4f, -1.08f, -0.73f, -1.69f, -0.98f)
    lineToRelative(-0.38f, -2.65f)
    curveToRelative(-0.04f, -0.24f, -0.24f, -0.42f, -0.49f, -0.42f)
    horizontalLineToRelative(-4f)
    curveToRelative(-0.25f, 0f, -0.45f, 0.18f, -0.49f, 0.42f)
    lineToRelative(-0.38f, 2.65f)
    curveToRelative(-0.61f, 0.25f, -1.17f, 0.59f, -1.69f, 0.98f)
    lineToRelative(-2.49f, -1f)
    curveToRelative(-0.23f, -0.09f, -0.48f, 0f, -0.6f, 0.22f)
    lineToRelative(-2f, 3.46f)
    curveToRelative(-0.12f, 0.22f, -0.07f, 0.49f, 0.12f, 0.64f)
    lineToRelative(2.11f, 1.65f)
    curveToRelative(-0.04f, 0.32f, -0.07f, 0.65f, -0.07f, 0.98f)
    curveToRelative(0f, 0.33f, 0.03f, 0.66f, 0.07f, 0.98f)
    lineToRelative(-2.11f, 1.65f)
    curveToRelative(-0.19f, 0.15f, -0.24f, 0.42f, -0.12f, 0.64f)
    lineToRelative(2f, 3.46f)
    curveToRelative(0.12f, 0.22f, 0.37f, 0.31f, 0.6f, 0.22f)
    lineToRelative(2.49f, -1f)
    curveToRelative(0.52f, 0.4f, 1.08f, 0.73f, 1.69f, 0.98f)
    lineToRelative(0.38f, 2.65f)
    curveToRelative(0.04f, 0.24f, 0.24f, 0.42f, 0.49f, 0.42f)
    horizontalLineToRelative(4f)
    curveToRelative(0.25f, 0f, 0.45f, -0.18f, 0.49f, -0.42f)
    lineToRelative(0.38f, -2.65f)
    curveToRelative(0.61f, -0.25f, 1.17f, -0.59f, 1.69f, -0.98f)
    lineToRelative(2.49f, 1f)
    curveToRelative(0.23f, 0.09f, 0.48f, 0f, 0.6f, -0.22f)
    lineToRelative(2f, -3.46f)
    curveToRelative(0.12f, -0.22f, 0.07f, -0.49f, -0.12f, -0.64f)
    close()
    moveTo(12f, 15.5f)
    curveToRelative(-1.93f, 0f, -3.5f, -1.57f, -3.5f, -3.5f)
    curveToRelative(0f, -1.93f, 1.57f, -3.5f, 3.5f, -3.5f)
    curveToRelative(1.93f, 0f, 3.5f, 1.57f, 3.5f, 3.5f)
    curveToRelative(0f, 1.93f, -1.57f, 3.5f, -3.5f, 3.5f)
    close()
}

/** 对应 res/drawable/ic_add.xml（Material 标准 add 图标）。 */
internal val IconAdd: ImageVector by lazy {
    icon("Add") {
        path(fill = SolidColor(Color.Black)) {
            moveTo(19f, 13f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(-2f)
            verticalLineToRelative(-6f)
            horizontalLineToRelative(-6f)
            verticalLineToRelative(-2f)
            horizontalLineToRelative(6f)
            verticalLineTo(5f)
            horizontalLineToRelative(2f)
            verticalLineToRelative(6f)
            horizontalLineToRelative(6f)
            close()
        }
    }
}

/**
 * 底栏中间的加号。
 *
 * Material 官方 add 是 2/24 的笔画，放在大胶囊里会显得单薄；这里用圆头描边
 * 画一个更粗的加号，和酷安/iOS 那种「大加号胶囊」的视觉重量一致。
 */
internal val IconAddBold: ImageVector by lazy {
    icon("AddBold") {
        path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 2.4f,
            strokeLineCap = StrokeCap.Round,
        ) {
            moveTo(12f, 4.4f)
            verticalLineTo(19.6f)
            moveTo(4.4f, 12f)
            horizontalLineTo(19.6f)
        }
    }
}

internal val BadgeCircle: ImageVector by lazy {
    icon("Badge", viewportWidth = 16f, viewportHeight = 16f, size = 16.dp) {
        path(fill = SolidColor(Color.Black)) {
            moveTo(8f, 0f)
            arcToRelative(8f, 8f, 0f, true, true, 0f, 16f)
            arcToRelative(8f, 8f, 0f, true, true, 0f, -16f)
            close()
        }
    }
}

private fun icon(
    name: String,
    viewportWidth: Float = 24f,
    viewportHeight: Float = 24f,
    size: androidx.compose.ui.unit.Dp = 24.dp,
    block: androidx.compose.ui.graphics.vector.ImageVector.Builder.() -> Unit,
): ImageVector = ImageVector.Builder(
    name = name,
    defaultWidth = size,
    defaultHeight = size,
    viewportWidth = viewportWidth,
    viewportHeight = viewportHeight,
).apply(block).build()
