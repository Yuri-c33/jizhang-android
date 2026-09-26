package com.jizhang.app.ui.common

import android.graphics.Color
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.jizhang.app.R
import com.jizhang.app.databinding.ItemPickerCellBinding

/** 网格选择器的一个条目。[id] 为负值时表示特殊操作（如「管理分类」入口）。 */
data class PickerItem(
    val id: Long,
    val emoji: String,
    val name: String,
)

/**
 * 通用的 emoji + 名称网格适配器，用于分类选择、账户选择等。
 */
class PickerAdapter(
    private val spanCount: Int = 4,
    private val onPick: (PickerItem) -> Unit,
) : ListAdapter<PickerItem, PickerAdapter.Holder>(DIFF) {

    var selectedId: Long = -1L
        set(value) {
            field = value
        }

    /**
     * 只刷新选中状态变化的单元格。
     *
     * 之前用 notifyDataSetChanged 会把所有图标重建，选中反馈也会闪；
     * 这里限定到旧/新两个位置，网格滚动位置不受影响。
     */
    fun selectId(id: Long) {
        val previous = selectedId
        if (previous == id) return
        selectedId = id

        val oldPosition = currentList.indexOfFirst { it.id == previous }
        val newPosition = currentList.indexOfFirst { it.id == id }
        if (oldPosition >= 0) notifyItemChanged(oldPosition)
        if (newPosition >= 0 && newPosition != oldPosition) notifyItemChanged(newPosition)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPickerCellBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemPickerCellBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(item: PickerItem) {
            binding.emojiView.setImageResource(
                if (item.id < 0) {
                    R.drawable.ic_manage
                } else {
                    CategoryIcons.of(item.name, item.emoji)
                },
            )
            binding.nameView.text = item.name

            val selected = item.id == selectedId && item.id >= 0
            // 选中态只落在圆形图标上，避免每个分类外面再出现一大块圆角底色。
            binding.selectedBg.background = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
            }
            binding.emojiView.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(
                    MaterialColors.getColor(
                        binding.root,
                        if (selected) {
                            com.google.android.material.R.attr.colorPrimary
                        } else {
                            com.google.android.material.R.attr.colorSurfaceContainerHigh
                        },
                    ),
                )
            }
            binding.emojiView.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    binding.root,
                    if (selected) {
                        com.google.android.material.R.attr.colorOnPrimary
                    } else {
                        com.google.android.material.R.attr.colorOnSurfaceVariant
                    },
                ),
            )
            binding.nameView.setTextColor(
                if (selected) {
                    MaterialColors.getColor(binding.root, com.google.android.material.R.attr.colorOnPrimaryContainer)
                } else {
                    MaterialColors.getColor(
                        binding.root,
                        com.google.android.material.R.attr.colorOnSurfaceVariant,
                    )
                },
            )

            binding.root.setOnClickListener { onPick(item) }
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PickerItem>() {
            override fun areItemsTheSame(oldItem: PickerItem, newItem: PickerItem) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: PickerItem, newItem: PickerItem) = oldItem == newItem
        }
    }
}
