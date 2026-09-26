package com.jizhang.app.ui.common

import android.content.Context
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jizhang.app.R

/**
 * emoji 选择对话框。中文记账 App 的分类图标用 emoji 最自然，
 * 也省掉了维护几十个矢量图资源。
 */
object EmojiPicker {

    /** 常用图标，按生活场景分组排列。 */
    val EMOJIS: List<String> = listOf(
        "🍜", "🍚", "🍔", "🍕", "🍰", "☕", "🍺", "🍎",
        "🚌", "🚇", "🚕", "🚗", "✈️", "🚄", "⛽", "🅿️",
        "🛍️", "👕", "👟", "💄", "💍", "👜", "🧴", "🛒",
        "💡", "💧", "🔥", "📱", "🌐", "🏠", "🛋️", "🔧",
        "💊", "🏥", "🦷", "👓", "🏃", "⚽", "🎬", "🎮",
        "📚", "✏️", "🎓", "🎁", "🧧", "🐾", "🍼", "👶",
        "💰", "💵", "💳", "🏦", "📈", "📉", "🧾", "↩️",
        "🏆", "🧰", "💼", "🎯", "📦", "🔖", "❤️", "⭐",
    )

    fun show(context: Context, current: String?, onPick: (String) -> Unit) {
        val view = RecyclerView(context).apply {
            layoutManager = GridLayoutManager(context, 8)
            setPadding(24, 32, 24, 16)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.category_emoji)
            .setView(view)
            .setNegativeButton(R.string.action_cancel, null)
            .create()
        val adapter = SimpleEmojiAdapter(EMOJIS, current) { emoji ->
            onPick(emoji)
            dialog.dismiss()
        }
        view.adapter = adapter
        dialog.show()
    }

    private class SimpleEmojiAdapter(
        private val items: List<String>,
        private val selected: String?,
        private val onPick: (String) -> Unit,
    ) : RecyclerView.Adapter<SimpleEmojiAdapter.Holder>() {

        class Holder(val text: android.widget.TextView) : RecyclerView.ViewHolder(text)

        override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int): Holder {
            val size = (parent.resources.displayMetrics.density * 44).toInt()
            val tv = android.widget.TextView(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(size, size)
                gravity = android.view.Gravity.CENTER
                textSize = 22f
                isClickable = true
                val attrs = intArrayOf(android.R.attr.selectableItemBackgroundBorderless)
                val ta = parent.context.obtainStyledAttributes(attrs)
                background = ta.getDrawable(0)
                ta.recycle()
            }
            return Holder(tv)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val emoji = items[position]
            holder.text.text = emoji
            holder.text.alpha = if (selected == null || selected == emoji) 1f else 0.55f
            holder.text.setOnClickListener { onPick(emoji) }
        }

        override fun getItemCount(): Int = items.size
    }
}
