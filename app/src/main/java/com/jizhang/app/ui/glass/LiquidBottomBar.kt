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

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/** 单个页签的展示数据。文案由调用方从 strings.xml 提供，避免 Compose 资源额外依赖。 */
internal data class GlassTab(
    val id: Int,
    val label: String,
    val icon: ImageVector,
    /** 选中态图标。MD3 导航栏的惯例是未选中用轮廓、选中用实心。 */
    val iconSelected: ImageVector = icon,
)

internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf { { 1f } }

/**
 * Adapted from Kyant0/AndroidLiquidGlass (Apache-2.0).
 *
 * The selected item is a real liquid-glass capsule that the user can drag
 * horizontally. On release it snaps to the nearest tab with a spring, matching
 * the interaction model of the official catalog component.
 *
 * [tabs] 是真实页签；底栏在正中间额外插入一个「记一笔」按钮，该按钮不参与
 * 选中与拖动吸附，因此选中索引空间仍是 [tabs] 的下标。
 */
@Composable
internal fun LiquidBottomBar(
    tabs: List<GlassTab>,
    addLabel: String,
    selectedTabIndex: () -> Int,
    onTabSelected: (index: Int) -> Unit,
    onAddClick: () -> Unit,
    backdrop: Backdrop,
    pendingBadgeCount: Int,
    isLightTheme: Boolean,
    accentColor: Color,
    onAccentColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    modifier: Modifier = Modifier,
) {
    val tabCount = tabs.size
    /** 页签各占 1 个单位宽，中间加号槽位略宽，用于容纳不撑满槽位的紧凑胶囊。 */
    val addTabIndex = tabCount / 2
    val totalWeight = tabCount + ADD_SLOT_WEIGHT
    /** 页签 [tabIndex] 左边缘在「单位坐标系」里的位置；拖动与吸附都在这个坐标系里计算。 */
    fun tabLeftUnit(tabIndex: Int): Float =
        tabIndex + if (tabIndex >= addTabIndex) ADD_SLOT_WEIGHT else 0f

    val containerColor =
        if (isLightTheme) Color(0xFFFDFEFD).copy(alpha = 0.46f)
        else Color(0xFF151917).copy(alpha = 0.54f)
    val tabsBackdrop = rememberLayerBackdrop()

    BoxWithConstraints(modifier, contentAlignment = Alignment.CenterStart) {
        val density = LocalDensity.current
        /** 1 个单位宽的像素值（也就是一个普通页签槽位的宽度）。 */
        val insetPx = with(density) { BAR_INSET.toPx() }
        val unitWidth = (constraints.maxWidth.toFloat() - insetPx * 2f) / totalWeight

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density) {
            derivedStateOf {
                val fraction =
                    (offsetAnimation.value / constraints.maxWidth).fastCoerceIn(-1f, 1f)
                with(density) { 3f.dp.toPx() * fraction.sign * EaseOut.transform(abs(fraction)) }
            }
        }

        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        var currentTabIndex by remember(selectedTabIndex) {
            mutableIntStateOf(selectedTabIndex().coerceIn(0, tabCount - 1))
        }
        val dampedDragAnimation = remember(animationScope, tabCount, unitWidth) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = tabLeftUnit(selectedTabIndex().coerceIn(0, tabCount - 1)),
                valueRange = 0f..(totalWeight - 1f),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                onDragStarted = {},
                onDragStopped = {
                    // 加号槽位不参与吸附：在像素空间里找最近的页签左边缘。
                    var bestTab = 0
                    var bestDistance = Float.MAX_VALUE
                    for (index in 0 until tabCount) {
                        val distance = abs(tabLeftUnit(index) - targetValue) * unitWidth
                        if (distance < bestDistance) {
                            bestDistance = distance
                            bestTab = index
                        }
                    }
                    currentTabIndex = bestTab
                    animateToValue(tabLeftUnit(currentTabIndex))
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    updateValue(
                        (targetValue + dragAmount.x / unitWidth * if (isLtr) 1f else -1f)
                            .fastCoerceIn(0f, totalWeight - 1f),
                    )
                    animationScope.launch {
                        offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                    }
                },
            )
        }

        LaunchedEffect(selectedTabIndex) {
            snapshotFlow { selectedTabIndex() }
                .collectLatest { index ->
                    currentTabIndex = index.coerceIn(0, tabCount - 1)
                }
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentTabIndex }
                .drop(1)
                .collectLatest { index ->
                    dampedDragAnimation.animateToValue(tabLeftUnit(index))
                    onTabSelected(index)
                }
        }

        val interactiveHighlight = remember(animationScope, unitWidth, insetPx) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        if (isLtr) {
                            insetPx + (dampedDragAnimation.value + 0.5f) * unitWidth + panelOffset
                        } else {
                            size.width - insetPx -
                                (dampedDragAnimation.value + 0.5f) * unitWidth + panelOffset
                        },
                        size.height / 2f,
                    )
                },
            )
        }

        // 底层玻璃：容器背景 + 唯一一份可见的页签内容。
        // 页签内容只在这里绘制一次，避免多层叠加造成重影。
        Row(
            Modifier
                .graphicsLayer { translationX = panelOffset }
                .drawBackdrop(
                    backdrop = backdrop,
                    shape = { Capsule() },
                    effects = {
                        vibrancy()
                        blur(10f.dp.toPx())
                        lens(28f.dp.toPx(), 28f.dp.toPx())
                    },
                    // 关掉玻璃自带的描边。
                    // 反编译确认默认值：Highlight(width = 0.5.dp, blurRadius = width / 2,
                    // alpha = 1f) —— 这是一条 0.5dp、**完全不透明**的亮色实边，
                    // 也就是用户看到的「底栏描边」。省略参数并不会不画，只会继承它。
                    highlight = { Highlight.Plain.copy(alpha = 0f) },
                    // 同时关掉底栏的投影。
                    // 默认 Shadow 是 24dp 模糊 / 6dp 下偏移 / alpha 0.1，本意是给悬浮的
                    // 玻璃胶囊垫一层落影；用户要求底栏（连同描边）一起做平，这里显式关掉。
                    // 三层里只有**这一层**是常驻投影：
                    //   · 折射层（alpha(0f)）整层不可见，它的默认投影自然也看不见；
                    //   · 可拖拽选中胶囊的 shadow 是 Shadow(alpha = pressProgress)，
                    //     松手即为 0，属于按压反馈，保留。
                    shadow = { Shadow.Default.copy(alpha = 0f) },
                    layerBlock = {
                        val progress = dampedDragAnimation.pressProgress
                        val scale = lerp(1f, 1f + 16f.dp.toPx() / size.width, progress)
                        scaleX = scale
                        scaleY = scale
                    },
                    onDrawSurface = { drawRect(containerColor) },
                )
                .then(interactiveHighlight.modifier)
                .height(BAR_HEIGHT)
                .fillMaxWidth()
                .padding(BAR_INSET),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassTabLayer(
                tabs = tabs,
                currentTabIndex = currentTabIndex,
                pendingBadgeCount = pendingBadgeCount,
                accentColor = accentColor,
                onAccentColor = onAccentColor,
                contentColor = contentColor,
                secondaryContentColor = secondaryContentColor,
                isLightTheme = isLightTheme,
                interactive = true,
                addLabel = addLabel,
                onAddClick = onAddClick,
                onSelected = { currentTabIndex = it },
            )
        }

        // 折射层：把落在下方玻璃上的内容再折射一次，形成液态凸起
        CompositionLocalProvider(
            LocalLiquidBottomTabScale provides {
                lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
            },
        ) {
            Row(
                Modifier
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(tabsBackdrop)
                    .graphicsLayer { translationX = panelOffset }
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { Capsule() },
                        effects = {
                            val progress = dampedDragAnimation.pressProgress
                            vibrancy()
                            blur(10f.dp.toPx())
                            lens(28f.dp.toPx() * progress, 28f.dp.toPx() * progress)
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                    .then(interactiveHighlight.modifier)
                    .height(BAR_CONTENT_HEIGHT)
                    .fillMaxWidth()
                    .padding(horizontal = BAR_INSET)
                    .graphicsLayer { colorFilter = ColorFilter.tint(accentColor) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                GlassTabLayer(
                    tabs = tabs,
                    currentTabIndex = currentTabIndex,
                    pendingBadgeCount = pendingBadgeCount,
                    accentColor = accentColor,
                    onAccentColor = onAccentColor,
                    contentColor = contentColor,
                    secondaryContentColor = secondaryContentColor,
                    isLightTheme = isLightTheme,
                )
            }
        }

        // 可拖拽的选中胶囊
        Box(
            Modifier
                .padding(horizontal = BAR_INSET)
                .graphicsLayer {
                    translationX =
                        if (isLtr) {
                            dampedDragAnimation.value * unitWidth + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 1f) * unitWidth + panelOffset
                        }
                }
                .then(interactiveHighlight.gestureModifier)
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(backdrop, tabsBackdrop),
                    shape = { Capsule() },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        lens(
                            12.dp.toPx() * progress,
                            16.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                    },
                    shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                    innerShadow = {
                        InnerShadow(
                            radius = 8.dp * dampedDragAnimation.pressProgress,
                            alpha = dampedDragAnimation.pressProgress,
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(
                            if (isLightTheme) Color.Black.copy(0.08f)
                            else Color.White.copy(0.10f),
                            alpha = 1f - progress,
                        )
                        drawRect(Color.Black.copy(alpha = 0.03f * progress))
                    },
                )
                .height(BAR_CONTENT_HEIGHT)
                .fillMaxWidth(1f / totalWeight),
        )
    }
}

/** 底栏玻璃容器高度。 */
private val BAR_HEIGHT = 68.dp

/** 玻璃容器内边距；内容区域高度 = BAR_HEIGHT - 2 * BAR_INSET。 */
private val BAR_INSET = 4.dp

/** 内容区域高度，页签文字与中间加号都以此为基准对齐。 */
private val BAR_CONTENT_HEIGHT = BAR_HEIGHT - (BAR_INSET + BAR_INSET)

/** 中间加号槽位相对于普通页签槽位的宽度倍数；与普通页签等宽，保持轻盈。 */
private const val ADD_SLOT_WEIGHT = 1f

/**
 * 加号胶囊的高度。
 *
 * 参考图中的加号按钮约占底栏高度的一半，而不是撑满整个内容区；
 * 这样既保留点击热区，又不会在玻璃底栏里显得笨重。
 */
private val ADD_CAPSULE_HEIGHT = 28.dp

/** 加号图标的绘制尺寸，约为胶囊高度的一半。 */
private val ADD_ICON_SIZE = 12.dp

/**
 * 绘制一层页签内容。
 *
 * 可见层与折射层必须绘制完全相同的图标与文字，
 * 否则两者位置不一致时会出现重影。
 */
@Composable
private fun RowScope.GlassTabLayer(
    tabs: List<GlassTab>,
    currentTabIndex: Int,
    pendingBadgeCount: Int,
    accentColor: Color,
    onAccentColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    isLightTheme: Boolean,
    interactive: Boolean = false,
    addLabel: String = "",
    onAddClick: (() -> Unit)? = null,
    onSelected: ((Int) -> Unit)? = null,
) {
    val addSlotIndex = tabs.size / 2
    tabs.forEachIndexed { index, tab ->
        if (index == addSlotIndex) {
            GlassAddSlot(
                label = addLabel,
                accentColor = accentColor,
                onAccentColor = onAccentColor,
                interactive = interactive,
                onClick = onAddClick,
            )
        }
        val selected = index == currentTabIndex
        GlassTabSlot(
            tab = tab,
            selected = selected,
            badgeCount = if (tab.id == TAB_PENDING_ID) pendingBadgeCount else 0,
            accentColor = accentColor,
            contentColor = contentColor,
            secondaryContentColor = secondaryContentColor,
            isLightTheme = isLightTheme,
            interactive = interactive,
            onSelected = onSelected?.let { select -> { select(index) } },
        )
    }
}

/** 底栏中间的「记一笔」按钮。 */
@Composable
private fun RowScope.GlassAddSlot(
    label: String,
    accentColor: Color,
    onAccentColor: Color,
    interactive: Boolean,
    onClick: (() -> Unit)?,
) {
    val scale = LocalLiquidBottomTabScale.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val pressScale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(1f, 600f, 0.72f),
        label = "glassAddScale",
    )
    val icon = rememberVectorPainter(IconAddBold)

    Box(
        modifier = Modifier
            .height(BAR_CONTENT_HEIGHT)
            .weight(ADD_SLOT_WEIGHT)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                // 紧凑胶囊加号：高度约为底栏的一半，宽度明显窄于普通页签。
                .fillMaxWidth(0.56f)
                .height(ADD_CAPSULE_HEIGHT)
                .graphicsLayer {
                    scaleX = pressScale
                    scaleY = pressScale
                }
                .let { base ->
                    if (!interactive || onClick == null) {
                        base
                    } else {
                        base
                            .semantics(mergeDescendants = true) {
                                contentDescription = label
                            }
                            .clickable(
                                interactionSource = interactionSource,
                                indication = null,
                                role = Role.Button,
                                onClick = onClick,
                            )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(ADD_CAPSULE_HEIGHT)
                    .graphicsLayer {
                        shape = Capsule()
                        clip = false
                        shadowElevation = if (pressed) 0f else 2f.dp.toPx()
                        ambientShadowColor = accentColor
                        spotShadowColor = accentColor
                    }
                    .clip(Capsule())
                    .background(accentColor)
                    .background(
                        if (pressed) Color.Black.copy(alpha = 0.08f) else Color.Transparent,
                    ),
            )
            Box(
                Modifier
                    .size(ADD_ICON_SIZE)
                    .paint(icon, colorFilter = ColorFilter.tint(onAccentColor)),
            )
        }
    }
}

@Composable
private fun RowScope.GlassTabSlot(
    tab: GlassTab,
    selected: Boolean,
    badgeCount: Int,
    accentColor: Color,
    contentColor: Color,
    secondaryContentColor: Color,
    isLightTheme: Boolean,
    interactive: Boolean,
    onSelected: (() -> Unit)?,
) {
    val scale = LocalLiquidBottomTabScale.current
    val icon = rememberVectorPainter(if (selected) tab.iconSelected else tab.icon)
    val color = if (selected) accentColor else secondaryContentColor
    val badgeColor = if (isLightTheme) Color(0xFFBA1A1A) else Color(0xFFFFB4AB)

    Column(
        modifier = Modifier
            .clip(Capsule())
            .let { base ->
                if (!interactive || onSelected == null) {
                    base
                } else {
                    base
                        .semantics(mergeDescendants = true) {
                            contentDescription = tab.label
                            role = Role.Tab
                            this.selected = selected
                        }
                        .clickable(
                            interactionSource = null,
                            indication = null,
                            role = Role.Tab,
                            onClick = onSelected,
                        )
                }
            }
            .height(56.dp)
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        // 未选中时不显示文字，图标必须落在槽位正中；选中时图标与文字作为一组整体居中。
        verticalArrangement = if (selected) {
            Arrangement.spacedBy(2.dp, Alignment.CenterVertically)
        } else {
            Arrangement.Center
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Box(
                Modifier
                    .size(24.dp)
                    .paint(icon, colorFilter = ColorFilter.tint(color)),
            )
            if (badgeCount > 0) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .graphicsLayer { translationX = 8.dp.toPx(); translationY = (-8).dp.toPx() }
                        .widthIn(min = 16.dp)
                        .height(16.dp)
                        .clip(Capsule())
                        .paint(
                            rememberVectorPainter(BadgeCircle),
                            colorFilter = ColorFilter.tint(badgeColor),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    BasicText(
                        text = if (badgeCount > 99) "99+" else badgeCount.toString(),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = if (badgeCount > 99) 7.sp else 9.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        modifier = Modifier.padding(horizontal = 3.dp),
                    )
                }
            }
        }
        // 只有选中项显示文字。未选中时不占位，图标才会真正落在槽位正中，
        // 不会出现「图标飘在上面」的观感；选中时图标与文字作为整体居中。
        if (selected) {
            Box(
                modifier = Modifier.height(13.dp),
                contentAlignment = Alignment.Center,
            ) {
                BasicText(
                    text = tab.label,
                    style = TextStyle(
                        color = contentColor,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    ),
                )
            }
        }
    }
}

internal const val TAB_PENDING_ID = 2
