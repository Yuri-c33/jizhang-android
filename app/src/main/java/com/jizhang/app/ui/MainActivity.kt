package com.jizhang.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.widget.NestedScrollView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.jizhang.app.R
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.data.prefs.UiStyle
import com.jizhang.app.databinding.ActivityMainBinding
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.ThemedActivity
import com.jizhang.app.ui.glass.GlassBottomBarHost
import com.jizhang.app.ui.ledger.LedgerFragment
import com.jizhang.app.ui.pending.PendingFragment
import com.jizhang.app.ui.settings.SettingsFragment
import com.jizhang.app.ui.stats.StatsFragment
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.WeakHashMap

class MainActivity : ThemedActivity() {

    private lateinit var binding: ActivityMainBinding

    /** 当前页签 id，避免重复创建 Fragment。 */
    private var currentTab = R.id.tab_ledger

    /** 玻璃底栏实际高度（像素），用于给滚动内容预留可滚到玻璃上方的空间。 */
    private var glassBarHeight = 0

    /** 滚动容器原始底部 padding，避免反复叠加。 */
    private val scrollBasePadding = WeakHashMap<View, Int>()

    /** 已挂过滚动监听的滚动容器，避免同一 View 重复注册（一个 View 可能被回调多次）。 */
    private val scrimSources = WeakHashMap<View, Boolean>()

    /** 遮罩进度阈值：滚过这么多像素即视为「完全进入遮罩态」。 */
    private val scrimThresholdPx: Int
        get() = (SCRIM_THRESHOLD_DP * resources.displayMetrics.density).toInt()

    /** 上一次读到的滚动偏移，用来判断这一帧是向下滚还是向上滚（驱动底栏显隐）。 */
    private var lastScrollOffset = 0

    /** 同一方向累积的滚动量（px）。> 0 表示累积向下、< 0 表示累积向上，换方向即清零。 */
    private var barScrollAccum = 0

    /** 向下滚这么多就收起底栏。 */
    private val barHideThresholdPx: Int
        get() = (BAR_HIDE_THRESHOLD_DP * resources.displayMetrics.density).toInt()

    /** 向上滚这么多就恢复底栏。比收起阈值小 = 恢复更灵敏。 */
    private val barShowThresholdPx: Int
        get() = (BAR_SHOW_THRESHOLD_DP * resources.displayMetrics.density).toInt()

    /** 距顶部这个范围内一律视为「停在顶部」，强制显示底栏。 */
    private val barTopSlopPx: Int
        get() = (BAR_TOP_SLOP_DP * resources.displayMetrics.density).toInt()

    /**
     * 底栏高度。优先读已布局完成的高度，回退到回调缓存值。
     * 这样即使高度回调发生在监听器赋值之前，也不会漏掉留白。
     */
    private fun currentGlassBarHeight(): Int {
        val measured = binding.glassBottomBar.height
        return if (measured > 0) measured else glassBarHeight
    }

    private val fragmentViewCallbacks = object : FragmentManager.FragmentLifecycleCallbacks() {
        override fun onFragmentViewCreated(
            fm: FragmentManager,
            f: Fragment,
            v: View,
            savedInstanceState: Bundle?,
        ) {
            applyScrollBottomInset(v, currentGlassBarHeight())
            instrumentScrimSource(v)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val settings = SettingsStore(this)
        if (!settings.onboardingDone) {
            startActivity(Intent(this, OnboardingActivity::class.java))
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState == null) {
            showTab(R.id.tab_ledger)
        } else {
            currentTab = savedInstanceState.getInt(KEY_TAB, R.id.tab_ledger)
        }

        setupGlassBottomBar()
        setupGlassStatusBar()
        supportFragmentManager.registerFragmentLifecycleCallbacks(fragmentViewCallbacks, false)

        observePendingBadge()
    }

    private fun setupGlassStatusBar() {
        val host = binding.glassStatusBar
        host.contentView = binding.contentHost
        host.installCompose()
    }

    private fun setupGlassBottomBar() {
        val host = binding.glassBottomBar
        host.contentView = binding.contentHost
        host.elegantStyle = SettingsStore(this).uiStyle != UiStyle.CLASSIC
        host.selectedTabIndex = tabIndexFor(currentTab)
        host.onTabSelected = { index ->
            val tabId = tabIdFor(index)
            if (tabId != currentTab) showTab(tabId)
        }
        host.onAddClick = {
            startActivity(Intent(this, AddTransactionActivity::class.java))
        }
        host.onBarHeightChanged = { height ->
            glassBarHeight = height
            applyGlassInsets()
        }
        host.installCompose()
    }

    /**
     * 悬浮玻璃底栏会盖住内容底部。这里把真实底栏高度写进各页滚动容器的
     * paddingBottom，列表最后一行仍能完整滚到玻璃上方；容器本身不缩小，
     * 因此玻璃下方仍能看到真实滚动内容，折射才会正确。
     */
    private fun applyGlassInsets() {
        binding.root.post {
            if (isFinishing || isDestroyed) return@post
            val barHeight = currentGlassBarHeight()
            supportFragmentManager.fragments.forEach { fragment ->
                fragment.view?.let { applyScrollBottomInset(it, barHeight) }
            }
        }
    }

    private fun applyScrollBottomInset(view: View, extraBottom: Int) {
        // 页面若自己维护了尾部的悬浮底栏占位（例如统计页的 spacer），
        // 就不要再叠加通用 padding，避免出现「先清零、后补不上」的时序问题。
        if (view.getTag(R.id.tag_explicit_bottom_inset) == true) return
        if (extraBottom <= 0) return
        when (view) {
            is RecyclerView, is ScrollView, is NestedScrollView -> {
                val base = scrollBasePadding.getOrPut(view) { view.paddingBottom }
                view.clipToPadding = false
                view.setPadding(
                    view.paddingLeft,
                    view.paddingTop,
                    view.paddingRight,
                    base + extraBottom,
                )
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                applyScrollBottomInset(view.getChildAt(index), extraBottom)
            }
        }
    }

    /**
     * 给页面里的滚动容器挂滚动监听，用来驱动顶部磨砂遮罩的强度与底部玻璃栏的显隐。
     *
     * RecyclerView 与 ScrollView/NestedScrollView 的滚动回调机制不一样，得分开挂：
     * RecyclerView 是靠 LayoutManager 移动子 View 来「滚」的，不会触发 View.onScrollChanged，
     * 所以 `setOnScrollChangeListener` 对它无效，必须用 addOnScrollListener；
     * 另外两种才走 View 自己的回调。
     */
    private fun instrumentScrimSource(view: View) {
        when (view) {
            is RecyclerView -> if (scrimSources.put(view, true) == null) {
                view.addOnScrollListener(object : RecyclerView.OnScrollListener() {
                    override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                        onPageScrolled()
                    }
                })
            }

            is NestedScrollView, is ScrollView -> if (scrimSources.put(view, true) == null) {
                view.setOnScrollChangeListener { _, _, _, _, _ -> onPageScrolled() }
            }
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                instrumentScrimSource(view.getChildAt(index))
            }
        }
    }

    /**
     * 页面滚动回调：同时驱动顶部遮罩与底部玻璃栏的显隐。
     *
     * 两者判据**故意不同**：
     * - 顶栏看**绝对位置** —— 只有内容真的钻到状态栏下面时才需要那层玻璃，
     *   所以滚离顶部才浮现、回到顶部就隐去；
     * - 底栏看**滚动方向** —— 底栏是导航，不能一停在顶部就消失
     *   （页面初始状态恰恰就是停在顶部，那时导航必须可见），
     *   所以是「向下滚让位收起、向上滚立刻回来」。
     */
    private fun onPageScrolled() {
        val offset = currentScrollOffset()
        updateScrimProgress(offset)
        updateBarReveal(offset)
    }

    /**
     * 顶部遮罩按滚动**位置**算进度（0..1，由 View 侧再夹一次范围）。
     *
     * 用 computeVerticalScrollOffset() 而不是 scrollY：RecyclerView 的 scrollY 恒为 0，
     * 只有前者对三种滚动容器都成立。
     */
    private fun updateScrimProgress(offset: Int) {
        val threshold = scrimThresholdPx
        binding.glassStatusBar.scrimProgress =
            if (threshold <= 0) 0f else offset.toFloat() / threshold
    }

    /**
     * 底栏按滚动**方向**收起 / 恢复。
     *
     * 用「同方向累积量」而不是单个 dy 判方向：手指抖动、惯性回弹都会给出
     * 零星的相反 dy，只看单帧会让底栏反复闪。累积到阈值才动作，换方向即清零。
     */
    private fun updateBarReveal(offset: Int) {
        val delta = offset - lastScrollOffset
        lastScrollOffset = offset

        // 停在顶部一律恢复显示：这是「滚回顶部却找不到导航」的最后一道保险。
        if (offset <= barTopSlopPx) {
            barScrollAccum = 0
            binding.glassBottomBar.revealProgress = 1f
            return
        }

        if (delta == 0) return

        // 换方向就清零，避免「滚下去一点、又滚上来一点」互相抵消掉。
        if ((delta > 0 && barScrollAccum < 0) || (delta < 0 && barScrollAccum > 0)) {
            barScrollAccum = 0
        }
        barScrollAccum += delta

        // 迟隐快现：向下要滚够多才收起，向上滚一点就立刻回来 ——
        // 收起是「让位给内容」，恢复是「用户要用导航了」，后者必须更灵敏。
        when {
            barScrollAccum >= barHideThresholdPx -> binding.glassBottomBar.revealProgress = 0f
            barScrollAccum <= -barShowThresholdPx -> binding.glassBottomBar.revealProgress = 1f
        }
    }

    /** 当前页签对应 Fragment 子树里的最大纵向滚动偏移。 */
    private fun currentScrollOffset(): Int {
        val root = supportFragmentManager.fragments
            .firstOrNull { it.tag == tagFor(currentTab) }
            ?.view
            ?: return 0
        return maxVerticalScrollOffset(root)
    }

    private fun maxVerticalScrollOffset(view: View): Int {
        // computeVerticalScrollOffset() 在 View 里是 protected，只有 RecyclerView /
        // NestedScrollView 把它重写成了公开方法；framework 的 ScrollView 只能用 scrollY
        // （它自己就是靠 scrollTo 滚的，scrollY 是准的）。
        val own = when (view) {
            is RecyclerView -> view.computeVerticalScrollOffset()
            is NestedScrollView -> view.computeVerticalScrollOffset()
            is ScrollView -> view.scrollY
            else -> 0
        }
        if (view !is ViewGroup) return own
        var best = own
        for (index in 0 until view.childCount) {
            best = maxOf(best, maxVerticalScrollOffset(view.getChildAt(index)))
        }
        return best
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    /** 待确认数量显示为角标。 */
    private fun observePendingBadge() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                appContainer.pendingRepo.observePendingCount().collectLatest { count ->
                    binding.glassBottomBar.pendingBadgeCount = count
                }
            }
        }
    }

    /** 切换页签。已存在则复用，避免丢失滚动位置。 */
    private fun showTab(tabId: Int) {
        currentTab = tabId
        val tag = tagFor(tabId)
        val manager = supportFragmentManager
        val transaction = manager.beginTransaction()

        manager.fragments.forEach { fragment ->
            if (fragment.tag != tag && !fragment.isHidden) {
                transaction.hide(fragment)
            }
        }

        val existing = manager.findFragmentByTag(tag)
        if (existing == null) {
            transaction.add(R.id.fragmentContainer, createFragment(tabId), tag)
        } else {
            transaction.show(existing)
        }
        transaction.commit()

        binding.glassBottomBar.selectedTabIndex = tabIndexFor(tabId)

        // 新页面的滚动位置和上一页无关：先同步基准偏移、复位方向累加器并把底栏收回显示态，
        // 否则会把上一页的滚动状态带到新页面（例如在 A 页下滚收起了底栏，切到 B 页时
        // 底栏还收着、页面明明停在顶部却看不到导航），随后按新页面自己的偏移重算一次强度。
        binding.root.post {
            lastScrollOffset = currentScrollOffset()
            barScrollAccum = 0
            binding.glassBottomBar.revealProgress = 1f
            onPageScrolled()
        }
    }

    private fun tagFor(tabId: Int): String = "tab_$tabId"

    private fun createFragment(tabId: Int): Fragment = when (tabId) {
        R.id.tab_stats -> StatsFragment()
        R.id.tab_pending -> PendingFragment()
        R.id.tab_settings -> SettingsFragment()
        else -> LedgerFragment()
    }

    private fun tabIndexFor(tabId: Int): Int = when (tabId) {
        R.id.tab_stats -> 1
        R.id.tab_pending -> 2
        R.id.tab_settings -> 3
        else -> 0
    }

    private fun tabIdFor(index: Int): Int = when (index) {
        1 -> R.id.tab_stats
        2 -> R.id.tab_pending
        3 -> R.id.tab_settings
        else -> R.id.tab_ledger
    }

    private companion object {
        const val KEY_TAB = "current_tab"

        /** 顶部磨砂遮罩的滚动阈值（dp）：滚过这么多就到达满强度。 */
        const val SCRIM_THRESHOLD_DP = 56f

        /** 向下滚这么多（dp）收起底栏。 */
        const val BAR_HIDE_THRESHOLD_DP = 48f

        /** 向上滚这么多（dp）恢复底栏。比收起阈值小 = 恢复更灵敏。 */
        const val BAR_SHOW_THRESHOLD_DP = 20f

        /** 距顶部这个范围内一律视为「停在顶部」，强制显示底栏。 */
        const val BAR_TOP_SLOP_DP = 8f
    }
}
