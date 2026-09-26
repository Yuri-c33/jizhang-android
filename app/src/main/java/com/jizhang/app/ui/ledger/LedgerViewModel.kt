package com.jizhang.app.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.repo.AccountRepository
import com.jizhang.app.data.repo.CategoryRepository
import com.jizhang.app.data.repo.LedgerFilter
import com.jizhang.app.data.repo.TransactionRepository
import com.jizhang.app.ui.common.RefreshController
import com.jizhang.app.util.Dates
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.YearMonth

/** 明细页的完整界面状态。 */
data class LedgerUiState(
    val yearMonth: YearMonth = YearMonth.now(),
    val rows: List<LedgerRow> = emptyList(),
    val expense: Long = 0,
    val income: Long = 0,
    val count: Int = 0,
    val filter: LedgerFilter = LedgerFilter(),
    val filterLabel: String = "",
    val searchVisible: Boolean = false,
    val loading: Boolean = true,
) {
    val balance: Long get() = income - expense
}

/** 月份 + 筛选条件 + 搜索框可见性，三者合成“查询意图”。 */
private data class Query(
    val yearMonth: YearMonth,
    val filter: LedgerFilter,
    val searchVisible: Boolean,
)

class LedgerViewModel(
    private val txRepo: TransactionRepository,
    categoryRepo: CategoryRepository,
    accountRepo: AccountRepository,
) : ViewModel() {

    private val yearMonth = MutableStateFlow(YearMonth.now())
    private val filter = MutableStateFlow(LedgerFilter())
    private val searchVisible = MutableStateFlow(false)

    /** 每次下拉刷新自增一次，用来触发查询重算（值本身不参与查询）。 */
    private val refreshTick = MutableStateFlow(0)

    /** 下拉刷新的转圈状态。由 [RefreshController] 保证起转后必定会落停。 */
    private val refreshCtl = RefreshController(viewModelScope)
    val refreshing: StateFlow<Boolean> = refreshCtl.refreshing

    val categoryList: StateFlow<List<Category>> = categoryRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val accountMap: StateFlow<Map<Long, String>> = accountRepo.observeActive()
        .map { list -> list.associate { it.id to it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val query: kotlinx.coroutines.flow.Flow<Query> =
        combine(yearMonth, filter, searchVisible, refreshTick) { ym, f, search, _ ->
            Query(ym, f, search)
        }

    /** 数据输入：账目全量 + 分类表 + 账户表。 */
    private val dataInput: kotlinx.coroutines.flow.Flow<Triple<List<Transaction>, List<Category>, Map<Long, String>>> =
        combine(txRepo.observeAll(), categoryList, accountMap) { txs, cats, accs -> Triple(txs, cats, accs) }

    val uiState: StateFlow<LedgerUiState> = combine(query, dataInput) { q, (txs, cats, accs) ->
        buildState(txs, q, cats, accs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LedgerUiState())

    private fun buildState(
        all: List<Transaction>,
        q: Query,
        cats: List<Category>,
        accs: Map<Long, String>,
    ): LedgerUiState {
        val monthTx = all.filter { Dates.yearMonthOf(it.occurredAt) == q.yearMonth }
        val filtered = monthTx.filter { matches(it, q.filter) }
        val catMap = cats.associateBy { it.id }

        return LedgerUiState(
            yearMonth = q.yearMonth,
            rows = buildRows(filtered, catMap),
            expense = filtered.filter { it.kind == TxKind.EXPENSE && !it.excludedFromStats }.sumOf { it.cents },
            income = filtered.filter { it.kind == TxKind.INCOME && !it.excludedFromStats }.sumOf { it.cents },
            count = filtered.size,
            filter = q.filter,
            filterLabel = describeFilter(q.filter, catMap, accs),
            searchVisible = q.searchVisible,
            loading = false,
        )
    }

    private fun matches(tx: Transaction, f: LedgerFilter): Boolean {
        if (f.kinds.isNotEmpty() && tx.kind !in f.kinds) return false
        if (f.categoryId != null && tx.categoryId != f.categoryId) return false
        if (f.accountId != null && tx.accountId != f.accountId && tx.toAccountId != f.accountId) return false
        if (f.keyword.isNotBlank()) {
            val haystack = listOfNotNull(tx.counterparty, tx.item, tx.note).joinToString(" ")
            if (!haystack.contains(f.keyword.trim(), ignoreCase = true)) return false
        }
        return true
    }

    private fun buildRows(txs: List<Transaction>, catMap: Map<Long, Category>): List<LedgerRow> {
        val rows = mutableListOf<LedgerRow>()
        val grouped = txs.groupBy { Dates.fromEpochMillis(it.occurredAt).toLocalDate() }
        grouped.keys.sortedDescending().forEach { date ->
            val dayTx = grouped.getValue(date)
            rows += LedgerRow.Header(
                date = date,
                title = Dates.sectionTitle(date),
                expense = dayTx.filter { it.kind == TxKind.EXPENSE && !it.excludedFromStats }.sumOf { it.cents },
                income = dayTx.filter { it.kind == TxKind.INCOME && !it.excludedFromStats }.sumOf { it.cents },
            )
            dayTx.sortedByDescending { it.occurredAt }.forEach { tx ->
                rows += LedgerRow.Item(tx, tx.categoryId?.let { catMap[it] })
            }
        }
        return rows
    }

    private fun describeFilter(f: LedgerFilter, catMap: Map<Long, Category>, accs: Map<Long, String>): String {
        val parts = mutableListOf<String>()
        if (f.kinds.isNotEmpty()) parts += f.kinds.joinToString("/") { it.label }
        f.categoryId?.let { id -> catMap[id]?.name?.let { parts += it } }
        f.accountId?.let { id -> accs[id]?.let { parts += it } }
        if (f.keyword.isNotBlank()) parts += "“${f.keyword}”"
        return parts.joinToString(" · ")
    }

    /**
     * 下拉刷新：触发一次查询重算。
     *
     * 起转与落停都交给 [RefreshController] —— 那里是**有界等待**，
     * 不"等新数据回来再关转圈"（本地数据未变时 StateFlow 会吞掉相等的值，
     * 那样永远等不到，又是停不下来的圈）。
     *
     * 旧版布局里有 SwipeRefreshLayout 却没人接 `setOnRefreshListener`、
     * 也没人置 `isRefreshing = false`，用户一拉就永远转。
     */
    fun refresh() {
        refreshCtl.trigger { refreshTick.value += 1 } // 让 query 重新发射 => 用当前快照重算一次
    }

    fun previousMonth() {
        yearMonth.value = yearMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        val next = yearMonth.value.plusMonths(1)
        if (next <= YearMonth.now()) yearMonth.value = next
    }

    fun toggleSearch() {
        val next = !searchVisible.value
        searchVisible.value = next
        if (!next) filter.value = filter.value.copy(keyword = "")
    }

    fun setKeyword(keyword: String) {
        filter.value = filter.value.copy(keyword = keyword)
    }

    fun setFilter(f: LedgerFilter) {
        filter.value = f
    }

    fun clearFilter() {
        filter.value = LedgerFilter()
    }

    fun delete(tx: Transaction) {
        viewModelScope.launch { txRepo.delete(tx) }
    }
}
