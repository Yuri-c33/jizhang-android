package com.jizhang.app.ui.common

import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.data.prefs.UiStyle

/**
 * 所有 Activity 的统一入口。样式在视图创建前生效，切换后由当前页面重建。
 */
abstract class ThemedActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        applyUiStyle()
        super.onCreate(savedInstanceState)
        disableNavigationBarScrim()
    }

    protected fun applyUiStyle() {
        setTheme(com.jizhang.app.R.style.Theme_Jizhang)
        val settings = SettingsStore(this)
        // 先叠加结构层（圆角/间距），再叠加配色层。
        theme.applyStyle(settings.uiStyle.themeOverlay, true)
        // 经典风格必须保持原有品牌绿，因此只在 MD3 风格下叠加配色方案。
        if (settings.uiStyle != UiStyle.CLASSIC) {
            theme.applyStyle(settings.colorPalette.themeOverlay, true)
        }
    }

    /**
     * 关掉系统给导航栏补的对比度蒙版（那层深色就是「黑边」）。
     *
     * 主题里已经写了 android:enforceNavigationBarContrast=false，这里再走一次运行时开关，
     * 是因为该属性是 API 29 才有的、且在部分厂商 ROM / 强制边到边的 Android 15+ 上
     * 主题属性不一定被读到；运行时开关是官方给的兜底入口。
     * 关掉之后导航栏区域完全由我们自己的玻璃负责，小白条浮在内容之上。
     */
    private fun disableNavigationBarScrim() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            window.isNavigationBarContrastEnforced = false
        }
    }
}

/** 读取当前主题里某个颜色属性的实际值。 */
internal fun Context.themeColor(attr: Int): Int {
    val value = TypedValue()
    theme.resolveAttribute(attr, value, true)
    return if (value.resourceId != 0) getColor(value.resourceId) else value.data
}
