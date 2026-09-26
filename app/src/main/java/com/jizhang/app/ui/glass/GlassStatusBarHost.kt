/*
 * 顶部状态栏磨砂遮罩。
 *
 * 为什么需要它：页面是边到边绘制的（各页从 y=0 开始排内容，没有给状态栏留内边距），
 * 所以「← 2026年9月 →」这类顶栏会直接顶到状态栏下面，滚动时更是被状态栏下沿**硬切**一刀，
 * 看上去和状态栏是两层割裂的东西。
 *
 * 这里在状态栏区域盖一层和底栏同源的液态玻璃：
 * 1. 模糊层：用 rememberCanvasBackdrop 采样真实内容区，不是拿纯色糊上去；
 * 2. 渐变层：顶部接近不透明、向下溶成透明，模糊层不会在底边留下一条硬边；
 * 3. 按滚动动态显隐：整体透明度和**模糊半径**都由滚动进度驱动 —— 停在顶部时
 *    几乎完全隐去（只剩一层极淡的纱，用来压暗穿在状态栏区域里的页面内容，
 *    保证系统状态栏图标可读）；一旦滚离顶部就渐强，滚过阈值后到达满强度。
 *    也就是说「顶栏玻璃」只在内容真的钻到状态栏下面时才出现。
 *
 * 为什么不直接改各页的 paddingTop：那是把内容整体下推，等于牺牲屏幕高度；
 * 滚动时内容照样得从状态栏下面穿过去，硬切的问题依然在。这里是「内容照旧穿过、
 * 交界处用玻璃溶掉」的做法，也是 MD3 / iOS 大标题栏的常规解法。
 *
 * 状态栏图标由系统绘制在本视图之上，所以遮罩不会影响它们；顶部渐变保持较高
 * 不透明度就是为了保证可读性。
 */
package com.jizhang.app.ui.glass

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.widget.FrameLayout
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.graphics.withSave
import com.jizhang.app.ui.common.themeColor
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

/**
 * 磨砂遮罩宿主。只覆盖状态栏那一条，不参与页面测量，也不给内容留白。
 */
class GlassStatusBarHost @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {

    /** 采样源：承载 Fragment 的内容容器，与底栏共用同一套采样方式。 */
    var contentView: android.view.View? = null

    private val progressState = mutableFloatStateOf(0f)

    /**
     * 滚动进度 0..1。0 = 停在顶部（遮罩最弱），1 = 已滚过阈值（遮罩满强度）。
     * 用 snapshot state 承载，Activity 侧改动才能驱动 Compose 重组。
     */
    var scrimProgress: Float
        get() = progressState.floatValue
        set(value) {
            val clamped = value.coerceIn(0f, 1f)
            if (progressState.floatValue != clamped) progressState.floatValue = clamped
        }

    private var composeInstalled = false

    init {
        setWillNotDraw(true)
        clipChildren = false
        clipToPadding = false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 与底栏一致：XML 里即使写 match_parent 也按内容高度测量，
        // 否则这层会盖住整屏、把下面的点击全部吃掉。
        super.onMeasure(
            widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
    }

    /**
     * 纯装饰层，不参与交互：本视图永不消费触摸，事件原样交给下层页面。
     *
     * 为什么还要显式写一遍：本层是叠在页面之上的，覆盖范围是「状态栏 + 18dp 渐隐区」。
     * 一旦 Compose 子树测到整屏高，或者某些版本下 ComposeView 把落在自身范围内的
     * DOWN 吞掉，顶栏附近的按钮就会出现点不动的死区 —— 这种问题在真机上才暴露，
     * 靠布局检查是看不出来的。返回 false 让父容器继续按 z 序找下一个子 View，
     * 代价为零（本层本来就没有任何可点内容）。
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false

    /** 在 setContentView 之后调用，把 Compose 遮罩挂进本 View。 */
    fun installCompose() {
        if (composeInstalled) return
        composeInstalled = true

        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP
            }
            setContent { ScrimContent() }
        }
        addView(composeView)
    }

    @Composable
    private fun ScrimContent() {
        val context = this.context
        val host = this

        // 不再按明/暗主题分别给不透明度：底色本身就取自当前主题的 colorSurface，
        // 明暗两套都由这一个值控制，渐隐交给下面那层遮罩统一做。
        val surface = Color(context.themeColor(com.google.android.material.R.attr.colorSurface))
        val maxBlurPx = with(LocalDensity.current) { MAX_BLUR.roundToPx().toFloat() }

        // 与底栏同样的采样方式：内容先于玻璃层绘制，所以每次渲染直接读它当前状态即可，
        // 不需要额外滚动监听去刷新（进度值单独由 Activity 驱动）。
        val backdrop = rememberCanvasBackdrop {
            val content = contentView ?: return@rememberCanvasBackdrop
            val contentLocation = IntArray(2)
            content.getLocationInWindow(contentLocation)
            val hostLocation = IntArray(2)
            host.getLocationInWindow(hostLocation)

            drawIntoCanvas { canvas ->
                canvas.nativeCanvas.withSave {
                    // 页面本身大多是透明背景，先铺一层主题表面色，
                    // 模糊才有真实内容可模糊，而不是一团空白。
                    drawColor(surface.toArgb())
                    translate(
                        (contentLocation[0] - hostLocation[0]).toFloat(),
                        (contentLocation[1] - hostLocation[1]).toFloat(),
                    )
                    content.draw(this)
                }
            }
        }

        // 滚动进度做成动画再消费：切换页签时进度会跳变，直接跟随会「啪」一下。
        val progress by animateFloatAsState(
            targetValue = progressState.floatValue,
            animationSpec = tween(durationMillis = 220),
            label = "statusScrim",
        )

        // 停在顶部时几乎隐去，滚动才是它的主体：progress=0 时只剩 BASE_ALPHA
        // 这一点点纱，progress=1 时到完全不透明的玻璃。
        //
        // 为什么不停在顶部就彻底归零：页面是边到边排的（顶部内容距屏幕顶端只有
        // jizhangSectionSpacing=12dp，比状态栏还矮），未滚动时页面顶栏本身就压在
        // 状态栏区域里。归零的话系统状态栏的时间/电量会直接叠在页面文字上，
        // 所以留这一层薄纱只做「压暗背景、保住可读性」这一件事。
        val veil = BASE_ALPHA + (1f - BASE_ALPHA) * progress

        // 遮罩：状态栏那一段保持完全显现（保证状态栏图标可读），往下在渐隐带里溶到全透明。
        //
        // 顶栏为什么也要这层遮罩：`blur` 只在一个**矩形**范围内生效，矩形边界上模糊会
        // 突然消失 —— 只给底色加渐变是盖不住这条起边的（渐变压在模糊**之上**，
        // 模糊本身照旧硬切）。这和底栏是同一个手法，只是方向相反：
        // 底栏贴着屏幕底边，所以是「从下往上越实」；这里是「从上往下越虚」。
        val statusBarDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val totalDp = statusBarDp + FADE_HEIGHT
        val solidUntil = if (totalDp.value > 0f) (statusBarDp / totalDp) else 1f

        Box(
            Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    alpha = veil
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.00f to Color.Black,
                            solidUntil.coerceIn(0f, 1f) to Color.Black,
                            1.00f to Color.Transparent,
                        ),
                        blendMode = BlendMode.DstIn,
                    )
                },
        ) {
            // 只负责撑出高度：状态栏高度 + 一段渐隐区。
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars))
                Box(Modifier.fillMaxWidth().height(FADE_HEIGHT))
            }

            // 模糊 + 底色放在**同一层**：这样底色也会跟着遮罩一起渐隐，
            // 不会出现「模糊已淡出、底色还在」的第二条分界线。
            Box(
                Modifier
                    .matchParentSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RectangleShape },
                        effects = {
                            vibrancy()
                            blur(maxBlurPx * progress)
                        },
                        // 与底栏同理：drawBackdrop 的默认 highlight/shadow 是给悬浮
                        // 胶囊描边投影用的，套到通栏矩形上会在四周画出一圈亮边和投影
                        // （状态栏遮罩的底边因此会多一条横线）。这里是纯覆盖层，显式关掉。
                        highlight = { Highlight.Plain.copy(alpha = 0f) },
                        shadow = { Shadow.Default.copy(alpha = 0f) },
                        // 底色不再单独铺一层渐变，改由上面的遮罩统一控制渐隐；
                        // 底色只给一个更透的值，内容就能更多地透上来（「更通透」）。
                        onDrawSurface = {
                            drawRect(surface.copy(alpha = SURFACE_ALPHA))
                        },
                    ),
            )
        }
    }

    private companion object {
        /** 渐隐区高度：状态栏之下再溶掉这一段（遮罩在这一段里把整层溶掉）。 */
        val FADE_HEIGHT = 18.dp

        /**
         * 停在顶部（未滚动）时的基础遮罩强度。
         *
         * 0.10 = 几乎隐去 —— 滚动前顶栏玻璃在视觉上等于不存在，只保留这一层
         * 极淡的纱把穿在状态栏区域里的页面内容压暗，保证系统状态栏图标可读。
         * 调大 = 未滚动时也看得见一层纱；调到 0 = 完全透明（状态栏图标会与
         * 页面顶部文字直接重叠，可读性会掉）。
         */
        const val BASE_ALPHA = 0.10f

        /** 满强度时的模糊半径。 */
        val MAX_BLUR = 12.dp

        /**
         * 玻璃底色不透明度。调小 = 更通透（内容更明显地透过玻璃）。
         * 状态栏那一段由遮罩保持完全显现，所以这个值只影响「玻璃有多实」，
         * 降到 0.8 仍能保证状态栏图标可读。
         */
        const val SURFACE_ALPHA = 0.80f
    }
}
