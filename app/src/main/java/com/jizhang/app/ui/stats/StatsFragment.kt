package com.jizhang.app.ui.stats

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.updateLayoutParams
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.databinding.FragmentStatsBinding
import com.jizhang.app.databinding.ItemCategoryRankBinding
import com.jizhang.app.money.Money
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.applyEmptyStateStyle
import com.jizhang.app.util.Dates
import com.jizhang.app.widget.BarPoint
import com.jizhang.app.widget.DonutSlice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.YearMonth

/** 统计页：月度汇总、分类构成、趋势。 */
class StatsFragment : Fragment() {

    private var _binding: FragmentStatsBinding? = null
    private val binding get() = _binding!!

    private val yearMonth = MutableStateFlow(YearMonth.now())
    private var renderJob: Job? = null
    private var renderVersion = 0

    /** 统计页自己管理尾部占位，监听底栏高度变化以便始终留出准确空间。 */
    private var glassBarHost: View? = null
    private val glassBarLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
        updateBottomSpacer()
    }

    /** 点击标题可在支出/收入构成之间切换。 */
    private var showIncomeBreakdown = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentStatsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyEmptyStateStyle()

        /*
         * MainActivity 的通用底栏留白会递归遍历 ScrollView / RecyclerView，
         * 同一个底栏高度可能被容器和子滚动区域重复加两次。统计页这里只
         * 依赖显式的尾部 spacer，先把根 ScrollView 的 padding 归零，再由
         * 下面按底栏真实高度设置 spacer，保证趋势图标签和图例完整露出。
         */
        binding.root.setPadding(
            binding.root.paddingLeft,
            binding.root.paddingTop,
            binding.root.paddingRight,
            0,
        )
        // 打上标记后，MainActivity 的通用底栏 padding 会跳过本页。
        binding.root.setTag(R.id.tag_explicit_bottom_inset, true)
        glassBarHost = requireActivity().findViewById(R.id.glassBottomBar)
        glassBarHost?.addOnLayoutChangeListener(glassBarLayoutListener)
        // 底栏可能已经完成布局，也可能稍后才量出高度；两种时序都覆盖。
        view.post { updateBottomSpacer() }

        binding.prevMonth.setOnClickListener { yearMonth.value = yearMonth.value.minusMonths(1) }
        binding.nextMonth.setOnClickListener {
            val next = yearMonth.value.plusMonths(1)
            if (next <= YearMonth.now()) yearMonth.value = next
        }
        binding.categoryTitle.setOnClickListener {
            showIncomeBreakdown = !showIncomeBreakdown
            requestRender(yearMonth.value)
        }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    yearMonth.collect { requestRender(it) }
                }
                launch {
                    // 账目变化时重算
                    appContainer.transactionRepo.observeAll().collect {
                        requestRender(yearMonth.value)
                    }
                }
            }
        }
    }

    private fun updateBottomSpacer() {
        val binding = _binding ?: return
        if (!isAdded) return
        val barHeight = glassBarHost?.height ?: 0
        val fallback = resources.getDimensionPixelSize(R.dimen.keypad_padding_bottom)
        // 玻璃底栏是悬浮层，柱状图月份标签与图例需要完整露出，
        // 因此在底栏高度之外再留一小段视觉缓冲。
        val clearance = (12 * resources.displayMetrics.density).toInt()
        binding.bottomBarSpacer.updateLayoutParams {
            height = (if (barHeight > 0) barHeight else fallback) + clearance
        }
    }

    private data class StatsData(
        val expense: Long,
        val income: Long,
        val count: Int,
        val byCategoryExpense: List<Pair<Long?, Long>>,
        val byCategoryIncome: List<Pair<Long?, Long>>,
        val monthly: List<Pair<YearMonth, Long>>,
        val monthlyIncome: List<Pair<YearMonth, Long>>,
        val categories: List<Category>,
    )

    /**
     * 每次请求都绑定自己的月份，并取消前一个渲染任务。
     * 这样快速切月时，旧查询即使更晚返回也不能覆盖新月份。
     */
    private fun requestRender(ym: YearMonth) {
        renderJob?.cancel()
        val version = ++renderVersion
        renderJob = viewLifecycleOwner.lifecycleScope.launch {
            binding.monthLabel.text = Dates.formatMonth(ym)
            updateMonthControls(ym)

            val data = withContext(Dispatchers.IO) {
                StatsData(
                    expense = appContainer.transactionRepo.sumOfKind(ym, TxKind.EXPENSE),
                    income = appContainer.transactionRepo.sumOfKind(ym, TxKind.INCOME),
                    count = appContainer.transactionRepo.countInMonth(ym),
                    byCategoryExpense = appContainer.transactionRepo
                        .sumByCategory(ym, TxKind.EXPENSE).map { it.categoryId to it.cents },
                    byCategoryIncome = appContainer.transactionRepo
                        .sumByCategory(ym, TxKind.INCOME).map { it.categoryId to it.cents },
                    monthly = appContainer.transactionRepo.monthlyTotals(6, TxKind.EXPENSE),
                    monthlyIncome = appContainer.transactionRepo.monthlyTotals(6, TxKind.INCOME),
                    categories = appContainer.categoryRepo.listAll(),
                )
            }
            // 取消防抖只降低开销；版本号负责阻止极窄竞态下的旧结果回写。
            if (version == renderVersion && yearMonth.value == ym && isAdded) {
                bind(data, ym)
            }
        }
    }

    private fun bind(data: StatsData, ym: YearMonth) {
        val isCurrentMonth = ym == YearMonth.now()
        val daysElapsed = if (isCurrentMonth) minOf(Dates.today().dayOfMonth, ym.lengthOfMonth()) else ym.lengthOfMonth()

        binding.expenseView.text = Money.formatCentsWithSymbol(data.expense)
        binding.incomeView.text = Money.formatCentsWithSymbol(data.income)

        val balance = data.income - data.expense
        binding.balanceView.text = (if (balance >= 0) "+" else "-") +
            Money.formatCentsWithSymbol(kotlin.math.abs(balance))
        binding.balanceView.setTextColor(
            ContextCompat.getColor(requireContext(), if (balance < 0) R.color.expense else R.color.income),
        )

        binding.avgView.text = Money.formatCentsWithSymbol(
            if (daysElapsed > 0) data.expense / daysElapsed else 0L,
        )
        binding.countView.text = getString(R.string.stats_count_format, data.count)

        val hasData = data.expense > 0 || data.income > 0 || data.count > 0
        binding.emptyView.visibility = if (hasData) View.GONE else View.VISIBLE
        binding.categoryCard.visibility = if (hasData) View.VISIBLE else View.GONE

        // ---- 分类构成 ----
        val breakdown = if (showIncomeBreakdown) data.byCategoryIncome else data.byCategoryExpense
        binding.categoryTitle.setText(
            if (showIncomeBreakdown) R.string.stats_by_category_income else R.string.stats_by_category,
        )

        val catMap = data.categories.associateBy { it.id }
        val palette = PALETTE.map { ContextCompat.getColor(requireContext(), it) }

        val allSlices = breakdown.mapIndexed { index, (categoryId, cents) ->
            DonutSlice(
                label = catMap[categoryId]?.name ?: getString(R.string.stats_other_category),
                value = cents,
                color = palette[index % palette.size],
            )
        }

        // 分类过多时把尾部合并为「其他」
        val maxSlices = 7
        val displayEntries: List<Pair<Category?, DonutSlice>>

        if (allSlices.size > maxSlices) {
            val headSlices = allSlices.take(maxSlices - 1)
            val otherSlice = DonutSlice(
                label = getString(R.string.stats_other_category),
                value = allSlices.drop(maxSlices - 1).sumOf { it.value },
                color = palette.last(),
            )
            displayEntries = headSlices.mapIndexed { index, slice ->
                catMap[breakdown[index].first] to slice
            } + (null to otherSlice)
        } else {
            displayEntries = allSlices.mapIndexed { index, slice ->
                catMap[breakdown[index].first] to slice
            }
        }

        binding.donut.setData(
            displayEntries.map { it.second },
            if (showIncomeBreakdown) getString(R.string.stats_income) else getString(R.string.stats_expense),
        )
        binding.donut.contentDescription = binding.donut.describe()

        // ---- 排行榜 ----
        binding.rankingBox.removeAllViews()
        val total = displayEntries.sumOf { it.second.value }
        displayEntries.forEach { (category, slice) ->
            val row = ItemCategoryRankBinding.inflate(layoutInflater, binding.rankingBox, false)
            row.colorDot.setBackgroundColor(slice.color)
            row.nameView.text = category?.name ?: slice.label
            val percent = if (total > 0) slice.value * 100.0 / total else 0.0
            row.percentView.text = String.format("%.1f%%", percent)
            row.amountView.text = Money.formatCentsWithSymbol(slice.value)
            binding.rankingBox.addView(row.root)
        }

        // ---- 趋势 ----
        val points = data.monthly.indices.map { index ->
            val (month, expense) = data.monthly[index]
            BarPoint(
                label = "${month.monthValue}月",
                expense = expense,
                income = data.monthlyIncome.getOrNull(index)?.second ?: 0L,
            )
        }
        binding.trend.setData(
            points,
            ContextCompat.getColor(requireContext(), R.color.expense),
            ContextCompat.getColor(requireContext(), R.color.income),
        )
        binding.trend.contentDescription = binding.trend.describe()
    }

    private fun updateMonthControls(ym: YearMonth) {
        val canGoNext = ym < YearMonth.now()
        binding.nextMonth.isEnabled = canGoNext
        // 统一禁用态透明度，避免未来月份看起来仍像可点击。
        binding.nextMonth.alpha = if (canGoNext) 1f else 0.38f
    }

    override fun onDestroyView() {
        glassBarHost?.removeOnLayoutChangeListener(glassBarLayoutListener)
        glassBarHost = null
        super.onDestroyView()
        _binding = null
    }

    private companion object {
        val PALETTE = listOf(
            R.color.chart_1,
            R.color.chart_2,
            R.color.chart_3,
            R.color.chart_4,
            R.color.chart_5,
            R.color.chart_6,
            R.color.chart_7,
        )
    }
}
