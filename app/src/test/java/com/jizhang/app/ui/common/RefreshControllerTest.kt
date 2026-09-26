package com.jizhang.app.ui.common

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下拉刷新「起转后必定落停」的契约测试。
 *
 * 背景：明细页原来布局里有 SwipeRefreshLayout，却没人接 setOnRefreshListener、
 * 也没人置 isRefreshing = false，用户一拉就永远转圈。
 * 这几条用例把"永远停不下来"这个失效模式钉在测试里。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RefreshControllerTest {

    @Test
    fun `起转后必定在有界时间内落停`() = runTest {
        val ctl = RefreshController(this)
        assertFalse("初始不应在转", ctl.refreshing.value)

        ctl.trigger()
        assertTrue("触发后应立刻在转", ctl.refreshing.value)

        advanceTimeBy(RefreshController.DEFAULT_MIN_VISIBLE_MS - 1)
        runCurrent()
        assertTrue("期限内不应提前停（否则会闪一下像没反应）", ctl.refreshing.value)

        advanceTimeBy(1)
        runCurrent()
        assertFalse("到点必须停 —— 这就是原来永远停不下来的那条", ctl.refreshing.value)
    }

    @Test
    fun `刷新期间重复触发不会把落停推迟`() = runTest {
        val ctl = RefreshController(this)
        ctl.trigger()

        advanceTimeBy(200)
        runCurrent()
        ctl.trigger() // 中途再拉一次

        advanceTimeBy(200)
        runCurrent()
        assertFalse("仍按第一次的期限落停，不叠加第二次", ctl.refreshing.value)
    }

    @Test
    fun `刷新期间重复触发时重新查询只跑一次`() = runTest {
        val ctl = RefreshController(this)
        var hits = 0

        ctl.trigger { hits++ }
        advanceTimeBy(100)
        runCurrent()
        ctl.trigger { hits++ } // 应被忽略

        advanceTimeBy(RefreshController.DEFAULT_MIN_VISIBLE_MS)
        runCurrent()
        assertEquals("重复触发不应把重新查询跑第二遍", 1, hits)
        assertFalse(ctl.refreshing.value)
    }

    @Test
    fun `落停之后可以再次触发`() = runTest {
        val ctl = RefreshController(this)
        ctl.trigger()
        advanceTimeBy(RefreshController.DEFAULT_MIN_VISIBLE_MS)
        runCurrent()
        assertFalse(ctl.refreshing.value)

        ctl.trigger()
        assertTrue("上一次结束后应能再次刷新", ctl.refreshing.value)
        advanceTimeBy(RefreshController.DEFAULT_MIN_VISIBLE_MS)
        runCurrent()
        assertFalse(ctl.refreshing.value)
    }

    @Test
    fun `重新查询动作在起转之后执行`() = runTest {
        val ctl = RefreshController(this)
        var hits = 0
        ctl.trigger { hits++ }
        runCurrent()
        assertEquals("起转后应已执行重新查询", 1, hits)
        assertTrue(ctl.refreshing.value)
    }
}
