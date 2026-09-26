package com.jizhang.app.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import com.jizhang.app.money.Money
import kotlin.math.max

/** 柱状图的一个数据点。 */
data class BarPoint(
    val label: String,
    val expense: Long,
    val income: Long,
)

/**
 * 自绘收支柱状图，用于「近 6 个月趋势」。
 * 每个月两根柱子：支出（红）与收入（绿）。
 */
class TrendBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = 11f * resources.displayMetrics.density
        color = resolveThemeColor(
            com.google.android.material.R.attr.colorOnSurfaceVariant,
            0xFF9AA0A6.toInt(),
        )
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = resolveThemeColor(
            com.google.android.material.R.attr.colorOutlineVariant,
            0x1A000000,
        )
        strokeWidth = 1f
    }

    private fun resolveThemeColor(attr: Int, fallback: Int): Int {
        val values = android.util.TypedValue()
        val resolved = context.theme.resolveAttribute(attr, values, true)
        return if (resolved) values.data else fallback
    }

    private var points: List<BarPoint> = emptyList()
    private var expenseColor: Int = 0xFFC0392B.toInt()
    private var incomeColor: Int = 0xFF1E8E5A.toInt()

    fun setData(points: List<BarPoint>, expenseColor: Int, incomeColor: Int) {
        this.points = points
        this.expenseColor = expenseColor
        this.incomeColor = incomeColor
        contentDescription = describe()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (points.isEmpty()) return

        // 用真实字体度量预留标签空间，避免 baseline 落到 View 底边之外，
        // 从而和卡片下方图例发生视觉重叠。
        val fontMetrics = labelPaint.fontMetrics
        val labelHeight = fontMetrics.bottom - fontMetrics.top
        val labelGap = labelPaint.textSize * 0.35f
        val chartTop = paddingTop.toFloat()
        val chartBottom = height - paddingBottom - labelHeight - labelGap
        val chartHeight = chartBottom - chartTop
        if (chartHeight <= 0) return

        val maxValue = max(
            points.maxOfOrNull { it.expense } ?: 0L,
            points.maxOfOrNull { it.income } ?: 0L,
        ).coerceAtLeast(1L)

        // 三条水平参考线
        for (i in 0..2) {
            val y = chartBottom - chartHeight * i / 2f
            canvas.drawLine(paddingLeft.toFloat(), y, width - paddingRight.toFloat(), y, gridPaint)
        }

        val available = width - paddingLeft - paddingRight
        val slotWidth = available.toFloat() / points.size
        val barWidth = slotWidth * 0.26f
        val gap = slotWidth * 0.06f

        val rect = RectF()

        points.forEachIndexed { index, point ->
            val centerX = paddingLeft + slotWidth * index + slotWidth / 2f

            // 支出柱（左）
            val expenseHeight = chartHeight * (point.expense.toFloat() / maxValue)
            if (expenseHeight > 0.5f) {
                barPaint.color = expenseColor
                rect.set(
                    centerX - gap / 2f - barWidth,
                    chartBottom - expenseHeight,
                    centerX - gap / 2f,
                    chartBottom,
                )
                canvas.drawRoundRect(rect, barWidth / 3f, barWidth / 3f, barPaint)
            }

            // 收入柱（右）
            val incomeHeight = chartHeight * (point.income.toFloat() / maxValue)
            if (incomeHeight > 0.5f) {
                barPaint.color = incomeColor
                rect.set(
                    centerX + gap / 2f,
                    chartBottom - incomeHeight,
                    centerX + gap / 2f + barWidth,
                    chartBottom,
                )
                canvas.drawRoundRect(rect, barWidth / 3f, barWidth / 3f, barPaint)
            }

            // 月份标签
            canvas.drawText(
                point.label,
                centerX,
                height - paddingBottom - fontMetrics.descent,
                labelPaint,
            )
        }
    }

    /** 在图上叠加数值（供无障碍/说明用，不参与绘制）。 */
    fun describe(): String = points.joinToString("；") {
        "${it.label} 支出 ${Money.formatCents(it.expense)}，收入 ${Money.formatCents(it.income)}"
    }
}
