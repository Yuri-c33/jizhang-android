package com.jizhang.app.ui.common

import android.content.res.ColorStateList
import android.view.View
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.TxKind

/** 按交易方向给金额上色。 */
object TxColors {

    @ColorRes
    fun of(kind: TxKind?): Int = when (kind) {
        TxKind.EXPENSE -> R.color.expense
        TxKind.INCOME -> R.color.income
        TxKind.TRANSFER -> R.color.transfer
        else -> R.color.on_surface_variant
    }

    fun applyAmount(view: TextView, kind: TxKind?) {
        view.setTextColor(ContextCompat.getColor(view.context, of(kind)))
    }
}

/** Snackbar 便捷方法。 */
fun View.snack(message: CharSequence, actionLabel: String? = null, action: (() -> Unit)? = null) {
    val bar = Snackbar.make(this, message, Snackbar.LENGTH_LONG)
    if (actionLabel != null && action != null) {
        bar.setAction(actionLabel) { action() }
    }
    bar.show()
}

fun View.tintIcon(@ColorRes colorRes: Int) {
    val color = ContextCompat.getColor(context, colorRes)
    if (this is android.widget.ImageView) {
        imageTintList = ColorStateList.valueOf(color)
    }
}

/**
 * 空状态图标/emoji 由主题布尔属性控制。
 *
 * 不能把自定义 enum 直接放进 android:visibility，MIUI Android 17 会在 Inflate
 * 阶段越界崩溃；这里在视图创建后转换成标准 View 可见性。
 */
fun View.applyEmptyStateStyle() {
    val typed = context.obtainStyledAttributes(
        intArrayOf(R.attr.jizhangEmptyUseIcon),
    )
    val useIcon = typed.getBoolean(0, false)
    typed.recycle()

    findViewById<View>(R.id.emptyIcon)?.visibility = if (useIcon) View.VISIBLE else View.GONE
    findViewById<View>(R.id.emptyEmoji)?.visibility = if (useIcon) View.GONE else View.VISIBLE
}
