package com.jizhang.app.ui.ledger

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import com.jizhang.app.databinding.ItemDayHeaderBinding
import com.jizhang.app.databinding.ItemTransactionBinding
import com.jizhang.app.money.Money
import com.jizhang.app.ui.common.CategoryIcons
import com.jizhang.app.util.Dates
import java.time.LocalDate

/** 明细列表的一行：日期分节头或一条账目。 */
sealed interface LedgerRow {
    data class Header(val date: LocalDate, val title: String, val expense: Long, val income: Long) : LedgerRow

    data class Item(val tx: Transaction, val category: Category?) : LedgerRow
}

class LedgerAdapter(
    private val onClick: (Transaction) -> Unit,
) : ListAdapter<LedgerRow, RecyclerView.ViewHolder>(DIFF) {

    /** 分类 id → 分类，用于渲染名称与 emoji。 */
    private var categories: Map<Long, Category> = emptyMap()

    /** 账户 id → 名称。 */
    private var accounts: Map<Long, String> = emptyMap()

    fun submit(categoryList: List<Category>, accountMap: Map<Long, String>) {
        categories = categoryList.associateBy { it.id }
        accounts = accountMap
        notifyDataSetChanged()
    }

    override fun getItemViewType(position: Int): Int = when (getItem(position)) {
        is LedgerRow.Header -> TYPE_HEADER
        is LedgerRow.Item -> TYPE_ITEM
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_HEADER) {
            HeaderHolder(ItemDayHeaderBinding.inflate(inflater, parent, false))
        } else {
            ItemHolder(ItemTransactionBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val row = getItem(position)) {
            is LedgerRow.Header -> (holder as HeaderHolder).bind(row)
            is LedgerRow.Item -> (holder as ItemHolder).bind(row)
        }
    }

    inner class HeaderHolder(private val binding: ItemDayHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: LedgerRow.Header) {
            binding.dateView.text = row.title
            val parts = mutableListOf<String>()
            if (row.expense > 0) parts += "支出 ${Money.formatCents(row.expense)}"
            if (row.income > 0) parts += "收入 ${Money.formatCents(row.income)}"
            binding.summaryView.text = parts.joinToString("  ")
        }
    }

    inner class ItemHolder(private val binding: ItemTransactionBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(row: LedgerRow.Item) {
            val tx = row.tx
            val category = row.category ?: tx.categoryId?.let { categories[it] }

            binding.emojiView.setImageResource(
                when (tx.kind) {
                    TxKind.TRANSFER -> R.drawable.ic_category_transfer
                    TxKind.NEUTRAL -> R.drawable.ic_category_default
                    else -> CategoryIcons.of(category?.name, category?.emoji)
                },
            )

            // 每笔账只有一行：标题 + 分类/商品 + 账户 + 时间（+ 来源），超长省略。
            val title = tx.counterparty?.takeIf { it.isNotBlank() }
                ?: category?.name
                ?: tx.item?.takeIf { it.isNotBlank() }
                ?: tx.kind.label
            val details = mutableListOf<String>()
            if (tx.counterparty?.isNotBlank() == true) {
                category?.name?.let { details += it }
                tx.item?.takeIf { it.isNotBlank() }?.let { details += it }
            } else {
                tx.item?.takeIf { it.isNotBlank() }?.let { details += it }
            }
            if (tx.kind == TxKind.TRANSFER) {
                val from = tx.accountId?.let { accounts[it] } ?: "?"
                val to = tx.toAccountId?.let { accounts[it] } ?: "?"
                details += "$from → $to"
            } else {
                tx.accountId?.let { accounts[it] }?.let { details += it }
            }
            tx.note?.takeIf { it.isNotBlank() }?.let { details += it }
            details += Dates.formatTime(tx.occurredAt)
            if (tx.source != TxSource.MANUAL) details += tx.source.label
            binding.titleView.text = (listOf(title) + details).joinToString(" · ")

            val negative = tx.kind == TxKind.EXPENSE
            binding.amountView.text = when (tx.kind) {
                TxKind.TRANSFER, TxKind.NEUTRAL -> Money.formatCents(tx.cents)
                else -> Money.formatSigned(tx.cents, negative)
            }
            val colorRes = when (tx.kind) {
                TxKind.EXPENSE -> R.color.expense
                TxKind.INCOME -> R.color.income
                TxKind.TRANSFER -> R.color.transfer
                TxKind.NEUTRAL -> R.color.on_surface_variant
            }
            binding.amountView.setTextColor(ContextCompat.getColor(binding.root.context, colorRes))

            binding.root.setOnClickListener { onClick(tx) }
        }
    }

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_ITEM = 1

        val DIFF = object : DiffUtil.ItemCallback<LedgerRow>() {
            override fun areItemsTheSame(oldItem: LedgerRow, newItem: LedgerRow): Boolean = when {
                oldItem is LedgerRow.Header && newItem is LedgerRow.Header -> oldItem.date == newItem.date
                oldItem is LedgerRow.Item && newItem is LedgerRow.Item -> oldItem.tx.id == newItem.tx.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: LedgerRow, newItem: LedgerRow): Boolean = oldItem == newItem
        }
    }
}
