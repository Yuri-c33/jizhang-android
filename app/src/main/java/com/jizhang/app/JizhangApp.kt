package com.jizhang.app

import android.app.Application
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.notify.NotifAccess

class JizhangApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        val settings = SettingsStore(this)
        settings.initializeUiStyleForExistingInstall()
        settings.themeMode.apply()
        // 修复历史版本可能在监听组件上遗留的「禁用」状态（正常情况是空操作）
        NotifAccess.healState(this)
    }
}
