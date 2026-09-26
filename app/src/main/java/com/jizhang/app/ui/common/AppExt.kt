package com.jizhang.app.ui.common

import android.content.Context
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.CreationExtras
import com.jizhang.app.AppContainer
import com.jizhang.app.JizhangApp

/**
 * 依赖容器的统一入口。
 *
 * Activity 本身就是 Context，所以 `appContainer` 直接可用；
 * Fragment 需要 `requireContext().appContainer`。
 */
val Context.appContainer: AppContainer
    get() = (applicationContext as JizhangApp).container

val Fragment.appContainer: AppContainer
    get() = requireContext().appContainer

/** 用 lambda 构造 ViewModel 的工厂，避免为每个 ViewModel 写一个 Factory 类。 */
class Factory(private val create: () -> ViewModel) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>, extras: CreationExtras): T =
        create() as T
}
