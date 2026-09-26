package com.jizhang.app.notify

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/** 通知使用权的检测、跳转与重绑。 */
object NotifAccess {

    /**
     * 上一次观察到的授权状态，用于识别「刚刚拿到授权」这一瞬间。
     * 只在内存里，进程重启后重新开始观察。
     */
    private var lastEnabled: Boolean? = null

    /** 本应用的通知监听服务是否已被系统启用。 */
    fun isEnabled(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** 跳转到系统的「通知使用权」设置页。 */
    fun openSettings(context: Context) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        } catch (_: Exception) {
            // 个别 ROM 没有这个页面，退回到应用详情页
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(android.net.Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /**
     * 界面 onResume 时调用：记住授权状态，并在「刚拿到授权」这一拍顺带做一次健康检查。
     *
     * 用户刚在系统设置页把开关打开、回到应用 —— 这是最需要确认的时刻。
     * [requestRebind] 只在发现组件残留禁用态时才会动系统，健康时是空操作，
     * 所以这里调用它不会对正常绑定产生扰动。
     *
     * @return 当前是否已授权，调用方可直接用它刷新界面。
     */
    fun syncOnResume(context: Context): Boolean {
        val enabled = isEnabled(context)
        val justGranted = enabled && lastEnabled == false
        lastEnabled = enabled
        if (justGranted) requestRebind(context)
        return enabled
    }

    /**
     * 应用启动时调用：修复历史版本可能遗留的「监听组件被禁用」状态。
     *
     * 状态正常时一次写入都不做（见 [healDisabledComponent]），因此可以放心在每次启动时调用。
     */
    fun healState(context: Context) {
        healDisabledComponent(context, componentOf(context))
    }

    /**
     * 请求系统重新绑定监听服务。
     *
     * 旧实现走的是「先置 DISABLED，再置 ENABLED」两拍切换，想借此逼系统重绑。
     * 但这恰好会造成「设置里显示已授权、服务却始终不起作用」：
     * **把组件置为 DISABLED 会触发 PACKAGE_CHANGED，并把它从系统的已授权列表里移除**，
     * 而紧接着的 ENABLED 并不保证把授权与绑定都拿回来（部分 ROM 只在那一拍判断一次，
     * 看到的是禁用态）；若进程恰好在两拍之间被杀，还会永久留下一个被禁用的组件。
     *
     * 现在不再制造「禁用」这个中间态，并且**只在必要时才动系统**：
     *   · 组件状态健康（默认/已启用）→ 什么都不做。
     *     不去碰正在工作的绑定：少一次主动干预，就少一分在个别 ROM 上把好绑定弄坏的机会。
     *     系统本来就会在 PACKAGE_CHANGED、开机、监听服务进程死亡时自行重绑。
     *   · 组件确实残留着禁用状态 → 先修回 ENABLED，再让系统重绑一次
     *     （那种状态下监听确实是坏的，修完必须重新绑上）。
     */
    fun requestRebind(context: Context) {
        val component = componentOf(context)

        // 只有真的修复了坏状态才值得惊动系统
        val repaired = healDisabledComponent(context, component)
        if (!repaired || !isEnabled(context)) return

        try {
            // 官方 API（API 24+）：由系统直接重绑它内存里的监听器列表，无中间态、无竞态。
            // 未授权时系统会拒绝，所以上面先判断了 isEnabled。
            NotificationListenerService.requestRebind(component)
        } catch (_: Exception) {
            // 失败无妨：系统会在下次 PACKAGE_CHANGED 或开机时自行绑定
        }
    }

    /**
     * 把被残留禁用的监听组件修回 ENABLED。
     *
     * 状态为默认或已启用时直接返回 false、不做任何写入 —— 这一点很重要：
     * 此时组件是好的，任何多余的写入都会触发 PACKAGE_CHANGED，
     * 反而可能扰动已经建立好的绑定与授权。
     *
     * @return 是否真的做了修复（调用方据此决定要不要请求系统重绑）
     */
    private fun healDisabledComponent(context: Context, component: ComponentName): Boolean {
        val pm = context.packageManager
        val stuckDisabled = try {
            when (pm.getComponentEnabledSetting(component)) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED -> true

                else -> false
            }
        } catch (_: Exception) {
            false
        }
        if (!stuckDisabled) return false

        return try {
            pm.setComponentEnabledSetting(
                component,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP,
            )
            true
        } catch (_: Exception) {
            // 个别 ROM 不允许应用修改自身组件状态，忽略
            false
        }
    }

    private fun componentOf(context: Context): ComponentName =
        ComponentName(context, WeChatNotificationListener::class.java)
}
