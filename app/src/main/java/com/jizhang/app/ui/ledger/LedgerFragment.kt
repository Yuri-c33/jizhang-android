package com.jizhang.app.ui.ledger

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.chip.Chip
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.repo.LedgerFilter
import com.jizhang.app.databinding.FragmentLedgerBinding
import com.jizhang.app.databinding.SheetFilterBinding
import com.jizhang.app.money.Money
import com.jizhang.app.ui.AddTransactionActivity
import com.jizhang.app.ui.common.Factory
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.applyEmptyStateStyle
import com.jizhang.app.util.Dates
import kotlinx.coroutines.launch

class LedgerFragment : Fragment() {

    private var _binding: FragmentLedgerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LedgerViewModel by viewModels {
        Factory {
            LedgerViewModel(
                appContainer.transactionRepo,
                appContainer.categoryRepo,
                appContainer.accountRepo,
            )
        }
    }

    private lateinit var adapter: LedgerAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentLedgerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyEmptyStateStyle()

        adapter = LedgerAdapter { tx -> openEditor(tx) }
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.prevMonth.setOnClickListener { viewModel.previousMonth() }
        binding.nextMonth.setOnClickListener { viewModel.nextMonth() }
        binding.searchButton.setOnClickListener { viewModel.toggleSearch() }
        binding.filterButton.setOnClickListener { showFilterSheet() }
        binding.filterClear.setOnClickListener { viewModel.clearFilter() }
        // 下拉刷新必须有人接：不接的话控件自己把转圈打开，却没人负责关掉
        binding.swipeRefresh.setOnRefreshListener { viewModel.refresh() }

        binding.searchInput.doAfterTextChanged { text ->
            viewModel.setKeyword(text?.toString().orEmpty())
        }

        observeState()
    }

    private fun observeState() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.uiState.collect { state -> render(state) }
                }
                launch {
                    // 分类/账户变化时刷新行内图标与账户名
                    viewModel.categoryList.collect {
                        adapter.submit(it, viewModel.accountMap.value)
                    }
                }
                launch {
                    viewModel.accountMap.collect {
                        adapter.submit(viewModel.categoryList.value, it)
                    }
                }
                launch {
                    viewModel.refreshing.collect { syncRefreshSpinner(it) }
                }
            }
        }
    }

    /** 下拉刷新的转圈跟着 ViewModel 的状态走；它是有界的，必定会落到 false。 */
    private fun syncRefreshSpinner(refreshing: Boolean) {
        binding.swipeRefresh.isRefreshing = refreshing && binding.swipeRefresh.visibility == View.VISIBLE
    }

    /**
     * 列表区是否展示。收起时顺手把转圈停掉 ——
     * `SwipeRefreshLayout` 的转圈是它自己的内部状态，视图 GONE 期间照样转，
     * 不停掉的话下次显示出来会接着转，又成了"停不下来的圈"。
     */
    private fun showListArea(visible: Boolean) {
        binding.swipeRefresh.visibility = if (visible) View.VISIBLE else View.GONE
        if (!visible) binding.swipeRefresh.isRefreshing = false
    }

    private fun render(state: LedgerUiState) {
        binding.monthLabel.text = Dates.formatMonth(state.yearMonth)
        binding.expenseSum.text = Money.formatCentsWithSymbol(state.expense)
        binding.incomeSum.text = Money.formatCentsWithSymbol(state.income)
        binding.balanceSum.text = Money.formatCentsWithSymbol(state.balance)
        binding.balanceSum.setTextColor(
            requireContext().getColor(if (state.balance < 0) R.color.expense else R.color.income),
        )
        binding.countLabel.text = getString(R.string.ledger_count_format, state.count)

        binding.searchBox.visibility = if (state.searchVisible) View.VISIBLE else View.GONE

        if (state.loading) {
            binding.filterBanner.visibility = View.GONE
            binding.emptyView.visibility = View.GONE
            binding.loadingView.visibility = View.VISIBLE
            showListArea(false)
            binding.countLabel.visibility = View.INVISIBLE
            return
        }
        binding.loadingView.visibility = View.GONE
        binding.countLabel.visibility = View.VISIBLE

        val hasFilter = state.filterLabel.isNotBlank()
        binding.filterBanner.visibility = if (hasFilter) View.VISIBLE else View.GONE
        binding.filterText.text = getString(R.string.ledger_filter_title) + "：" + state.filterLabel

        adapter.submitList(state.rows)

        val empty = state.rows.isEmpty()
        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
        showListArea(!empty)
        if (empty) {
            val filtered = state.filter.isActive || state.filter.keyword.isNotBlank()
            binding.emptyTitle.setText(
                if (filtered) R.string.ledger_no_result else R.string.ledger_empty,
            )
            binding.emptyHint.setText(
                if (filtered) R.string.ledger_no_result_hint else R.string.ledger_empty_hint,
            )
        }
    }

    private fun openEditor(tx: Transaction) {
        startActivity(AddTransactionActivity.editIntent(requireContext(), tx.id))
    }

    /** 底部弹出的筛选面板：类型多选，分类/账户单选。 */
    private fun showFilterSheet() {
        val state = viewModel.uiState.value
        val cats = viewModel.categoryList.value
        val accs = viewModel.accountMap.value

        val sheet = SheetFilterBinding.inflate(layoutInflater)
        val kindValues = listOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER)

        kindValues.forEach { kind ->
            val chip = Chip(requireContext()).apply {
                text = kind.label
                isCheckable = true
                isChecked = kind in state.filter.kinds
                tag = kind
            }
            sheet.kindGroup.addView(chip)
        }
        cats.forEach { category ->
            val chip = Chip(requireContext()).apply {
                text = category.name
                isCheckable = true
                isChecked = category.id == state.filter.categoryId
                tag = category.id
            }
            sheet.categoryGroup.addView(chip)
        }
        accs.forEach { (id, name) ->
            val chip = Chip(requireContext()).apply {
                text = name
                isCheckable = true
                isChecked = id == state.filter.accountId
                tag = id
            }
            sheet.accountGroup.addView(chip)
        }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.ledger_filter_title)
            .setView(sheet.root)
            .setPositiveButton(R.string.action_apply) { _, _ ->
                val kinds = sheet.kindGroup.checkedChipIds
                    .mapNotNull { id -> (sheet.kindGroup.findViewById<Chip>(id)?.tag as? TxKind) }
                    .toSet()
                val categoryId = sheet.categoryGroup.checkedChipIds.firstOrNull()
                    ?.let { sheet.categoryGroup.findViewById<Chip>(it)?.tag as? Long }
                val accountId = sheet.accountGroup.checkedChipIds.firstOrNull()
                    ?.let { sheet.accountGroup.findViewById<Chip>(it)?.tag as? Long }

                viewModel.setFilter(
                    LedgerFilter(
                        kinds = kinds,
                        categoryId = categoryId,
                        accountId = accountId,
                        keyword = state.filter.keyword,
                    ),
                )
            }
            .setNeutralButton(R.string.action_reset) { _, _ -> viewModel.clearFilter() }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.list.adapter = null
        _binding = null
    }
}
