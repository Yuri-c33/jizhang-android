package com.jizhang.app.ui.common

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 下拉刷新的「起转 / 必停」控制器。
 *
 * 抽出来是因为踩过一个坑：`SwipeRefreshLayout` 的转圈是它**自己**的内部状态 ——
 * 控件在松手越过阈值时会自己把它打开，所以必须有人负责关掉。
 * 明细页原来既没接 `setOnRefreshListener`、也没人置 `isRefreshing = false`，
 * 于是用户一拉就永远转（而且视图被 GONE 掉也照转）。
 *
 * 第二个坑是"等新数据回来再关转圈"：本地数据没变化时，重算出来的状态与上一份**相等**，
 * `StateFlow` 会把相等的值吞掉、不再发射，那样就永远等不到，又是一个停不下来的圈。
 * 所以这里走**有界等待**：起转后固定时长必然结束，与数据是否变化无关。
 *
 * 终止性：唯一能把 [refreshing] 置 true 的是 [trigger]，它必定会安排一次
 * `delay` 之后置回 false；而 `delay` 只可能因协程被取消而中断，
 * 协程跑在宿主的 scope 上，宿主销毁时整个界面一起没了，不存在"留在屏幕上的圈"。
 */
class RefreshController(
    private val scope: CoroutineScope,
    /** 转圈的最短可见时长；同时它就是"一定会停"的上界。 */
    private val minVisibleMs: Long = DEFAULT_MIN_VISIBLE_MS,
) {

    private val _refreshing = MutableStateFlow(false)

    /** 转圈是否进行中。 */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    /**
     * 触发一次刷新。已在进行中时直接忽略，不叠加第二次
     * —— 叠加会让先落的那次把转圈提前关掉。
     *
     * @param onStart 真正的重新查询动作，在起转之后、落停之前执行。
     */
    fun trigger(onStart: () -> Unit = {}) {
        if (_refreshing.value) return
        _refreshing.value = true
        scope.launch {
            onStart()
            delay(minVisibleMs)
            _refreshing.value = false
        }
    }

    companion object {
        /** 400ms：短到不拖沓，又长到不会"闪一下"像没反应。 */
        const val DEFAULT_MIN_VISIBLE_MS = 400L
    }
}
