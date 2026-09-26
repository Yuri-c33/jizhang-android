package com.jizhang.app.data.prefs

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import com.jizhang.app.R
import com.jizhang.app.data.db.AppDatabase
import java.io.File

/** 主题模式。 */
enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色"),
    ;

    fun apply() {
        AppCompatDelegate.setDefaultNightMode(
            when (this) {
                FOLLOW_SYSTEM -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                DARK -> AppCompatDelegate.MODE_NIGHT_YES
            },
        )
    }

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: FOLLOW_SYSTEM
    }
}

/** 界面视觉风格，独立于明暗模式。 */
enum class UiStyle(val label: String, val themeOverlay: Int) {
    CLASSIC("经典", R.style.ThemeOverlay_Jizhang_Classic),
    ELEGANT("MD3", R.style.ThemeOverlay_Jizhang_Elegant),
    ;

    companion object {
        fun fromName(name: String?): UiStyle =
            entries.firstOrNull { it.name == name } ?: ELEGANT

        fun forExistingInstall(name: String?): UiStyle =
            entries.firstOrNull { it.name == name } ?: CLASSIC
    }
}

/**
 * MD3 配色方案，独立于 [UiStyle] 和明暗主题。
 *
 * 只有 [UiStyle.ELEGANT] 会使用它；经典风格完全保留原来的品牌绿。
 */
enum class ColorPalette(val label: String, val dotColor: Int, val themeOverlay: Int) {
    PURPLE("靛紫", R.color.pal_purple_primary, R.style.ThemeOverlay_Jizhang_Palette_Purple),
    SAND("暖沙", R.color.pal_sand_primary, R.style.ThemeOverlay_Jizhang_Palette_Sand),
    BLUE("湖蓝", R.color.pal_blue_primary, R.style.ThemeOverlay_Jizhang_Palette_Blue),
    GREEN("松绿", R.color.pal_green_primary, R.style.ThemeOverlay_Jizhang_Palette_Green),
    ROSE("绯樱", R.color.pal_rose_primary, R.style.ThemeOverlay_Jizhang_Palette_Rose),
    TEAL("青瓷", R.color.pal_teal_primary, R.style.ThemeOverlay_Jizhang_Palette_Teal),
    INDIGO("星夜", R.color.pal_indigo_primary, R.style.ThemeOverlay_Jizhang_Palette_Indigo),
    CLAY("陶土", R.color.pal_clay_primary, R.style.ThemeOverlay_Jizhang_Palette_Clay),
    ;

    companion object {
        fun fromName(name: String?): ColorPalette =
            entries.firstOrNull { it.name == name } ?: PURPLE
    }
}

/**
 * 设置项。用 SharedPreferences，读写都在主线程之外调用。
 */
class SettingsStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs = context.applicationContext
        .getSharedPreferences("jizhang_settings", Context.MODE_PRIVATE)

    /** 是否已完成首次引导 */
    var onboardingDone: Boolean
        get() = prefs.getBoolean(KEY_ONBOARDING, false)
        set(value) = prefs.edit().putBoolean(KEY_ONBOARDING, value).apply()

    /** 是否已经由用户或升级流程确定过界面风格。 */
    val hasUiStyle: Boolean
        get() = prefs.contains(KEY_UI_STYLE)

    var uiStyle: UiStyle
        get() = if (prefs.contains(KEY_UI_STYLE)) {
            UiStyle.forExistingInstall(prefs.getString(KEY_UI_STYLE, null))
        } else {
            UiStyle.ELEGANT
        }
        set(value) {
            prefs.edit()
                .putString(KEY_UI_STYLE, value.name)
                .putBoolean(KEY_MD3_MIGRATION_DONE, true)
                .apply()
        }

    /** 当前 MD3 配色方案。 */
    var colorPalette: ColorPalette
        get() = ColorPalette.fromName(prefs.getString(KEY_COLOR_PALETTE, null))
        set(value) {
            prefs.edit().putString(KEY_COLOR_PALETTE, value.name).apply()
        }

    /**
     * 把首次带双风格版本的旧安装一次性迁移到 MD3。
     *
     * 只有迁移标记不存在时才会改写已有风格；用户之后手动切回经典，
     * 因为标记已经写入，不会再被升级流程覆盖。
     */
    fun initializeUiStyleForExistingInstall(): UiStyle {
        if (prefs.getBoolean(KEY_MD3_MIGRATION_DONE, false)) return uiStyle

        val hasExistingData = prefs.getBoolean(KEY_ONBOARDING, false) ||
            File(appContext.filesDir.parentFile, "databases/${AppDatabase.DB_NAME}").exists()
        val currentStyle = prefs.getString(KEY_UI_STYLE, null)
        val style = when {
            // 这一轮 MD3 重构是整体改版，旧安装首次升级时同步切到 MD3。
            hasExistingData && (currentStyle == null || currentStyle == UiStyle.CLASSIC.name) ->
                UiStyle.ELEGANT
            currentStyle != null -> UiStyle.forExistingInstall(currentStyle)
            else -> UiStyle.ELEGANT
        }
        prefs.edit()
            .putString(KEY_UI_STYLE, style.name)
            .putBoolean(KEY_MD3_MIGRATION_DONE, true)
            .apply()
        return uiStyle
    }

    /** 是否开启自动入账（关：识别结果先入待确认队列） */
    var autoConfirmNotifications: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CONFIRM, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_CONFIRM, value).apply()

    /** 只有置信度不低于该值时才自动入账 */
    var autoConfirmMinConfidence: String
        get() = prefs.getString(KEY_AUTO_CONFIDENCE, "HIGH") ?: "HIGH"
        set(value) = prefs.edit().putString(KEY_AUTO_CONFIDENCE, value).apply()

    /** 默认记账账户 id */
    var defaultAccountId: Long
        get() = prefs.getLong(KEY_DEFAULT_ACCOUNT, 0L)
        set(value) = prefs.edit().putLong(KEY_DEFAULT_ACCOUNT, value).apply()

    /** 通知监听是否已提示过授权 */
    var notificationPromptShown: Boolean
        get() = prefs.getBoolean(KEY_NOTIF_PROMPT, false)
        set(value) = prefs.edit().putBoolean(KEY_NOTIF_PROMPT, value).apply()

    /** 主题 */
    var themeMode: ThemeMode
        get() = ThemeMode.fromName(prefs.getString(KEY_THEME, null))
        set(value) = prefs.edit().putString(KEY_THEME, value.name).apply()

    /** 是否在记账时默认沿用上次选择 */
    var rememberLastSelection: Boolean
        get() = prefs.getBoolean(KEY_REMEMBER, true)
        set(value) = prefs.edit().putBoolean(KEY_REMEMBER, value).apply()

    var lastCategoryId: Long
        get() = prefs.getLong(KEY_LAST_CATEGORY, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_CATEGORY, value).apply()

    /** 是否已导入过账单 */
    var hasImported: Boolean
        get() = prefs.getBoolean(KEY_HAS_IMPORTED, false)
        set(value) = prefs.edit().putBoolean(KEY_HAS_IMPORTED, value).apply()

    /** 上次备份时间 */
    var lastBackupAt: Long
        get() = prefs.getLong(KEY_LAST_BACKUP, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BACKUP, value).apply()

    /** 工厂重置：清除所有 SharedPreferences，并保留已完成的初始化语义。 */
    fun resetForFactory() {
        // 恢复出厂后应呈现全新安装的默认风格，因此在同一次提交中显式写回雅致。
        prefs.edit()
            .clear()
            .putBoolean(KEY_ONBOARDING, false)
            .putString(KEY_UI_STYLE, UiStyle.ELEGANT.name)
            .putString(KEY_COLOR_PALETTE, ColorPalette.PURPLE.name)
            .putBoolean(KEY_MD3_MIGRATION_DONE, true)
            .commit()
    }

    private companion object {
        const val KEY_ONBOARDING = "onboarding_done"
        const val KEY_UI_STYLE = "ui_style"
        const val KEY_MD3_MIGRATION_DONE = "md3_migration_done"
        const val KEY_COLOR_PALETTE = "color_palette"
        const val KEY_AUTO_CONFIRM = "auto_confirm"
        const val KEY_AUTO_CONFIDENCE = "auto_confidence"
        const val KEY_DEFAULT_ACCOUNT = "default_account"
        const val KEY_NOTIF_PROMPT = "notif_prompt"
        const val KEY_THEME = "theme_mode"
        const val KEY_REMEMBER = "remember_selection"
        const val KEY_LAST_CATEGORY = "last_category"
        const val KEY_HAS_IMPORTED = "has_imported"
        const val KEY_LAST_BACKUP = "last_backup"
    }
}
