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

import android.content.Context
import android.content.res.Configuration
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import com.jizhang.app.R
import com.jizhang.app.ui.common.themeColor
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 液态玻璃底栏的宿主。
 *
 * 该 View 只测量 Compose 子树需要的底部高度，不会遮住上方页面。
 * [contentView] 是页面真实内容容器，每帧按窗口坐标差平移后绘制进
 * Backdrop，所以玻璃折射的是真实页面，而不是一块模拟底色。
 */
class GlassBottomBarHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 采样源：承载 Fragment 的内容容器。 */
    var contentView: android.view.View? = null

    private val tabIndexState = mutableIntStateOf(0)
    private val badgeCountState = mutableIntStateOf(0)
    private val revealState = mutableFloatStateOf(1f)

    /**
     * 底栏显隐进度：1 = 完全显示，0 = 完全收起。
     *
     * 由滚动方向驱动（向下滚收起、向上滚恢复），具体判定在 Activity 侧。
     * 用 snapshot state 承载，Activity 侧改动才能驱动 Compose 重组。
     *
     * 注意这里是**纯视觉**量：收起只改透明度，不改测量高度 ——
     * 底栏高度是喂给各页滚动容器 paddingBottom 的对外契约（见 onBarHeightChanged），
     * 一旦跟着收起变化，所有页面的尾部留白都会跟着抖。
     */
    var revealProgress: Float
        get() = revealState.floatValue
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (revealState.floatValue != clamped) revealState.floatValue = clamped
        }

    /** 当前页签，0..3。用 snapshot state 承载，Activity 侧改动才能驱动 Compose 重组。 */
    var selectedTabIndex: Int
        get() = tabIndexState.intValue
        set(value) {
            if (tabIndexState.intValue != value) tabIndexState.intValue = value
        }

    /** 待确认角标数量。 */
    var pendingBadgeCount: Int
        get() = badgeCountState.intValue
        set(value) {
            if (badgeCountState.intValue != value) badgeCountState.intValue = value
        }

    /** 是否使用雅致配色。必须在 [installCompose] 之前设置。 */
    var elegantStyle: Boolean = true

    /** 页签点击回调。 */
    var onTabSelected: ((Int) -> Unit)? = null

    /** 底栏中间「记一笔」按钮点击回调。 */
    var onAddClick: (() -> Unit)? = null

    /** 底栏实际高度（含系统栏），供 Activity 精确预留内容空间。 */
    var onBarHeightChanged: ((Int) -> Unit)? = null

    private var composeInstalled = false
    private var lastReportedHeight = -1

    init {
        setWillNotDraw(true)
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // XML 中即使写 match_parent，也强制按内容高度测量，避免覆盖整屏。
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    /**
     * 底栏收起后不再接收触摸。
     *
     * 为什么必须显式处理：收起是**纯 alpha** 动画（几何不能动，见 [revealProgress]），
     * 而 Compose 的 alpha=0 只影响绘制、**不影响命中测试** —— 底栏看不见了，
     * 里面的页签和「记一笔」按钮照样能被点到。那样用户会在底部空白处误触，
     * 凭空跳页或弹出记一笔界面。这里直接返回 false，让事件按 z 序交给下层页面。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (revealState.floatValue < TOUCH_ALPHA_CUTOFF) return false
        return super.dispatchTouchEvent(ev)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h > 0 && h != lastReportedHeight) {
            lastReportedHeight = h
            // 等布局稳定后再回调，避免 Fragment 尚未创建时读到旧高度。
            post {
                if (!isAttachedToWindow) return@post
                onBarHeightChanged?.invoke(h)
            }
        }
    }

    /** 在 setContentView 之后调用，把 Compose 底栏挂进本 View。 */
    fun installCompose() {
        if (composeInstalled) return
        composeInstalled = true

        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.BOTTOM
            }
            setContent {
                GlassBarContent()
            }
        }
        addView(composeView)
    }

    @Composable
    private fun GlassBarContent() {
        val context = this.context
        val host = this
        val lightTheme = (context.resources.configuration.uiMode and
            Configuration.UI_MODE_NIGHT_MASK) != Configuration.UI_MODE_NIGHT_YES

        // 颜色取自当前页面主题，这样五种 MD3 配色都能即时作用到底栏。
        val accent = Color(context.themeColor(com.google.android.material.R.attr.colorPrimary))
        val onSurface = Color(context.themeColor(com.google.android.material.R.attr.colorOnSurface))
        val onSurfaceVariant =
            Color(context.themeColor(com.google.android.material.R.attr.colorOnSurfaceVariant))
        val onAccent = Color(context.themeColor(com.google.android.material.R.attr.colorOnPrimary))
        val surface = Color(context.themeColor(com.google.android.material.R.attr.colorSurface))

        // 内容 View 已经先于玻璃层完成绘制，因此每次渲染直接读取它当前状态即可，
        // 不需要额外的滚动监听或逐帧状态刷新。
        val backdrop = rememberCanvasBackdrop {
            val content = contentView ?: return@rememberCanvasBackdrop
            val contentLocation = IntArray(2)
            content.getLocationInWindow(contentLocation)
            val hostLocation = IntArray(2)
            host.getLocationInWindow(hostLocation)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.withSave {
                    // 页面本身大多是透明背景，先铺一层主题表面色，
                    // 模糊与折射才能得到真实页面质感，而不是空白。
                    drawColor(surface.toArgb())
                    translate(
                        (contentLocation[0] - hostLocation[0]).toFloat(),
                        (contentLocation[1] - hostLocation[1]).toFloat(),
                    )
                    content.draw(this)
                }
            }
        }

        // 收起 / 恢复做成动画：滚动方向翻转时状态是跳变的，直接跟随会「啪」一下。
        // 只作用于 alpha，不碰任何尺寸 —— 底栏总高是喂给各页 paddingBottom 的对外契约。
        val reveal by animateFloatAsState(
            targetValue = revealState.floatValue,
            animationSpec = tween(durationMillis = REVEAL_ANIM_MS),
            label = "bottomBarReveal",
        )

        Column(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = reveal },
        ) {
            LiquidBottomBar(
                tabs = remember(context) { context.glassTabs() },
                addLabel = context.getString(R.string.action_add),
                selectedTabIndex = { tabIndexState.intValue },
                onTabSelected = { index -> onTabSelected?.invoke(index) },
                onAddClick = { onAddClick?.invoke() },
                backdrop = backdrop,
                pendingBadgeCount = badgeCountState.intValue,
                isLightTheme = lightTheme,
                accentColor = accent,
                onAccentColor = onAccent,
                contentColor = onSurface,
                secondaryContentColor = onSurfaceVariant,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 14.dp, end = 14.dp, top = 8.dp),
            )
            // 手势条区域的磨砂底座。
            //
            // 原来这里是个纯占位 Spacer：玻璃胶囊下方露出一条「页面底色 + 小白条」，
            // 手势条看着像贴在页面外的一条独立色带，底部因此显得割裂。
            // 现在改成同源 backdrop 的磨砂层 —— 手势条落在玻璃上。
            //
            // 高度必须与原来一致（= 导航栏 inset + 10dp）：底栏总高度会通过
            // onBarHeightChanged 写进各页滚动容器的 paddingBottom，
            // 高度一变，所有页面的尾部留白都会跟着变。
            //
            // 外层只做一件事：用纵向 alpha 遮罩把整层（模糊 + 底色）在顶部渐隐掉。
            // 为什么非要有这层遮罩：`blur` 只在一个**矩形**范围内生效，矩形边界上
            // 模糊突然就没有了，那条起边在真机上是一条看得见的硬线；
            // 光给底色加渐变盖不住它 —— 渐变是压在模糊**之上**的，模糊本身照旧硬切。
            Box(
                Modifier
                    .fillMaxWidth()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // 顶部全透明 -> MASK_FADE_END 处起完全显现：
                        // 模糊从「没有」平滑长到「满」，整层不再有任何起边。
                        drawRect(
                            brush = Brush.verticalGradient(
                                0.00f to Color.Transparent,
                                MASK_FADE_END to Color.Black,
                                1.00f to Color.Black,
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        // drawBackdrop 必须排在 padding **之前**。修饰符是自外向内包的，
                        // 绘制节点拿到的尺寸 =「它右边所有修饰符的结果」，所以排在外面
                        // 才能覆盖「导航栏 inset + 10dp」整块，玻璃一路铺到屏幕最底边。
                        // 反过来写（先 padding 再 drawBackdrop）就只画到 inset 那么高，
                        // 屏幕最底下会剩一条 10dp 没画到的缝，而底色渐变恰好在玻璃底边
                        // 达到最实的 0.90 —— 那条缝的上边界就是用户看到的「框」的底边。
                        .drawBackdrop(
                            backdrop = backdrop,
                            shape = { RectangleShape },
                            effects = {
                                vibrancy()
                                blur(12.dp.toPx())
                            },
                            // 这两个默认值是给悬浮胶囊描边、投影用的。
                            // 套到通栏矩形上，它会沿整块玻璃的四周画出一圈亮边加投影，
                            // 看上去就是一个把小白条框住的「框」—— 这里显式关掉。
                            highlight = { Highlight.Plain.copy(alpha = 0f) },
                            shadow = { Shadow.Default.copy(alpha = 0f) },
                            // 底色画在玻璃面上（不再另加一个子 Box）：
                            // 这样它和模糊层同属一层，一起被上面那层遮罩渐隐，
                            // 不会出现「模糊已淡出、底色还在」的分界。
                            onDrawSurface = {
                                drawRect(surface.copy(alpha = SURFACE_ALPHA))
                            },
                        )
                        .padding(bottom = DOCK_GAP)
                        .windowInsetsBottomHeight(WindowInsets.navigationBars),
                )
            }
        }
    }

    private companion object {
        /**
         * 底座比导航栏 inset 再多留的高度。这个值是**对外契约**的一部分
         * （参与底栏总高），和它替换掉的旧 Spacer 保持一致，不要随手改。
         */
        val DOCK_GAP = 10.dp

        /** 遮罩渐变到这条线就完全显现；之上按比例渐隐，用来消掉模糊的起边。
         *  调大 = 渐隐带更长更柔（整块玻璃是「慢慢长出来」而不是很快变实）。 */
        const val MASK_FADE_END = 0.62f

        /** 玻璃底色不透明度。调小 = 更通透（内容更明显地透过玻璃）。 */
        const val SURFACE_ALPHA = 0.66f

        /** 收起 / 恢复的动画时长。太短会显得弹，太长会跟不上手势。 */
        const val REVEAL_ANIM_MS = 220

        /**
         * 收起超过这个程度就停止接收触摸（见 dispatchTouchEvent）。
         * 取 0.5 是因为此时底栏已经只剩一半影子，用户不会再认为那里有按钮。
         */
        const val TOUCH_ALPHA_CUTOFF = 0.5f
    }
}

private fun Context.glassTabs(): List<GlassTab> = listOf(
    GlassTab(0, getString(R.string.tab_ledger), IconLedger, IconLedgerFilled),
    GlassTab(1, getString(R.string.tab_stats), IconStats, IconStatsFilled),
    GlassTab(2, getString(R.string.tab_pending), IconPending, IconPendingFilled),
    GlassTab(3, getString(R.string.tab_settings), IconSettings, IconSettingsFilled),
)
