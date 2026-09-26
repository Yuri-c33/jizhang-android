package com.jizhang.app.ui.pending

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.databinding.ItemPendingBinding
import com.jizhang.app.money.Money
import com.jizhang.app.util.Dates

class PendingAdapter(
    private val onConfirm: (PendingItem) -> Unit,
    private val onIgnore: (PendingItem) -> Unit,
    private val onPickCategory: (PendingItem) -> Unit,
    private val onPickAccount: (PendingItem) -> Unit,
    private val onPickToAccount: (PendingItem) -> Unit,
) : ListAdapter<PendingRow, PendingAdapter.Holder>(DIFF) {

    private var allRows: List<PendingRow> = emptyList()
    private var busyIds: Set<Long> = emptySet()
    private var bulkBusy: Boolean = false

    fun submit(list: List<PendingRow>) {
        allRows = list
        submitList(list)
    }

    fun setBusyIds(ids: Set<Long>) {
        busyIds = ids.toSet()
        notifyDataSetChanged()
    }

    fun setBulkBusy(busy: Boolean) {
        bulkBusy = busy
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
        Holder(ItemPendingBinding.inflate(LayoutInflater.from(parent.context), parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(private val binding: ItemPendingBinding) : RecyclerView.ViewHolder(binding.root) {

        fun bind(row: PendingRow) {
            val item = row.item

            // 金额
            if (item.cents != null && item.cents > 0) {
                binding.amountView.text = when (item.kind) {
                    TxKind.EXPENSE -> Money.formatSigned(item.cents, negative = true)
                    TxKind.INCOME -> Money.formatSigned(item.cents, negative = false)
                    else -> Money.formatCents(item.cents)
                }
            } else {
                binding.amountView.text = "未识别金额"
            }
            binding.amountView.setTextColor(
                binding.root.context.getColor(
                    when (item.kind) {
                        TxKind.EXPENSE -> R.color.expense
                        TxKind.INCOME -> R.color.income
                        TxKind.TRANSFER -> R.color.transfer
                        else -> R.color.on_surface_variant
                    },
                ),
            )

            // 置信度
            binding.confidenceView.text = binding.root.context.getString(
                R.string.pending_confidence,
                item.confidence.label,
            )
            binding.confidenceView.setTextColor(
                binding.root.context.getColor(
                    when (item.confidence) {
                        Confidence.HIGH -> R.color.income
                        Confidence.MEDIUM -> R.color.transfer
                        Confidence.LOW -> R.color.expense
                    },
                ),
            )

            // 分类与账户
            val isTransfer = item.kind == TxKind.TRANSFER
            binding.categoryButton.text = row.categoryName ?: binding.root.context.getString(R.string.add_pick_category)
            binding.accountButton.text = row.accountName
                ?: binding.root.context.getString(R.string.add_pick_account)
            binding.categoryButton.visibility = if (isTransfer) View.GONE else View.VISIBLE
            binding.toAccountButton.visibility = if (isTransfer) View.VISIBLE else View.GONE
            binding.toAccountButton.text = row.toAccountName
                ?: binding.root.context.getString(R.string.add_pick_to_account)

            // 原始通知
            binding.titleView.text = item.rawTitle.orEmpty()
            binding.rawView.text = item.rawText.orEmpty()
            binding.ruleView.text = buildString {
                append(item.matchedRule?.let { "规则：$it" } ?: "未匹配规则")
                append(" · ")
                append(Dates.fromEpochMillis(item.postedAt).format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm")))
            }

            binding.categoryButton.setOnClickListener { onPickCategory(item) }
            binding.accountButton.setOnClickListener { onPickAccount(item) }
            binding.toAccountButton.setOnClickListener { onPickToAccount(item) }
            binding.confirmButton.setOnClickListener { onConfirm(item) }
            binding.ignoreButton.setOnClickListener { onIgnore(item) }

            val rowBusy = bulkBusy || item.id in busyIds
            binding.confirmButton.isEnabled = !rowBusy && item.cents != null && item.cents > 0
            binding.ignoreButton.isEnabled = !rowBusy
            binding.categoryButton.isEnabled = !rowBusy
            binding.accountButton.isEnabled = !rowBusy
            binding.toAccountButton.isEnabled = !rowBusy
            val alpha = if (item.id in busyIds) 0.55f else 1f
            binding.confirmButton.alpha = alpha
            binding.ignoreButton.alpha = alpha
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<PendingRow>() {
            override fun areItemsTheSame(oldItem: PendingRow, newItem: PendingRow) = oldItem.item.id == newItem.item.id

            override fun areContentsTheSame(oldItem: PendingRow, newItem: PendingRow) = oldItem == newItem
        }
    }
}
