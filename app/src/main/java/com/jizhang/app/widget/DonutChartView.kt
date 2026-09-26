package com.jizhang.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.jizhang.app.money.Money
import kotlin.math.min

/** 环形图的一个扇区。 */
data class DonutSlice(
    val label: String,
    val value: Long,
    val color: Int,
)

/**
 * 自绘环形图。用 Canvas 画，不引入图表库——
 * 依赖越少，在无法真机验证的情况下风险越低。
 */
class DonutChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val centerTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
    }

    private var slices: List<DonutSlice> = emptyList()
    private var total: Long = 0
    private var centerTitle: String = ""
    private val strokeWidthDp: Float = 26f * resources.displayMetrics.density

    /** 从主题取色，保证深色模式下也清晰。 */
    private val labelColor: Int = resolveThemeColor(
        com.google.android.material.R.attr.colorOnSurfaceVariant,
        0xFF888888.toInt(),
    )

    private val valueColor: Int = resolveThemeColor(
        com.google.android.material.R.attr.colorOnSurface,
        0xFF333333.toInt(),
    )

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val values = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, values, true)
        return if (resolved) values.data else fallback
    }

    fun setData(slices: List<DonutSlice>, centerTitle: String = "") {
        this.slices = slices.filter { it.value > 0 }
        this.total = this.slices.sumOf { it.value }
        this.centerTitle = centerTitle
        contentDescription = describe()
        invalidate()
    }

    /** 供无障碍服务朗读的环形图摘要。 */
    fun describe(): String {
        if (total <= 0) return "暂无数据"
        val parts = slices.joinToString("，") {
            "${it.label} ${Money.formatCentsWithSymbol(it.value)}"
        }
        return "$centerTitle，合计 ${Money.formatCentsWithSymbol(total)}。$parts"
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (total <= 0) return

        val size = min(width, height).toFloat()
        val padding = strokeWidthDp / 2f + 2f
        val left = (width - size) / 2f + padding
        val top = (height - size) / 2f + padding
        rect.set(left, top, left + size - 2 * padding, top + size - 2 * padding)

        paint.style = Paint.Style.STROKE
        paint.strokeWidth = strokeWidthDp
        paint.strokeCap = Paint.Cap.BUTT

        // 留出间隙，近似 Material 的环形风格
        val gapDegrees = if (slices.size > 1) 1.6f else 0f
        var startAngle = -90f

        slices.forEach { slice ->
            val sweep = slice.value.toFloat() / total.toFloat() * 360f
            paint.color = slice.color
            val drawSweep = (sweep - gapDegrees).coerceAtLeast(0.5f)
            canvas.drawArc(rect, startAngle + gapDegrees / 2f, drawSweep, false, paint)
            startAngle += sweep
        }

        // 圆心文字：总额
        if (centerTitle.isNotEmpty()) {
            centerTextPaint.textSize = size * 0.075f
            centerTextPaint.color = labelColor
            canvas.drawText(
                centerTitle,
                width / 2f,
                height / 2f - size * 0.02f,
                centerTextPaint,
            )
            centerTextPaint.textSize = size * 0.11f
            centerTextPaint.isFakeBoldText = true
            centerTextPaint.color = valueColor
            canvas.drawText(
                Money.formatCents(total),
                width / 2f,
                height / 2f + size * 0.09f,
                centerTextPaint,
            )
            centerTextPaint.isFakeBoldText = false
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val size = min(measuredWidth, measuredHeight)
        if (size > 0) {
            setMeasuredDimension(
                resolveSize(size, widthMeasureSpec),
                resolveSize(size, heightMeasureSpec),
            )
        }
    }
}
