package com.jizhang.app.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import com.jizhang.app.R
import com.jizhang.app.databinding.ActivityOnboardingBinding
import com.jizhang.app.notify.NotifAccess
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.importbill.ImportActivity

/**
 * 首次启动引导：开启通知监听、导入历史账单。
 */
class OnboardingActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.grantButton.setOnClickListener {
            NotifAccess.openSettings(this)
        }
        binding.importButton.setOnClickListener {
            startActivity(Intent(this, ImportActivity::class.java))
        }
        binding.startButton.setOnClickListener { finishOnboarding() }
        binding.skipButton.setOnClickListener { finishOnboarding() }
    }

    override fun onResume() {
        super.onResume()
        // 可能是刚在系统设置页开了开关再回来：这里会检测到「刚授权」并补一次重绑
        val enabled = NotifAccess.syncOnResume(this)
        binding.notifStatus.setText(
            if (enabled) R.string.onboarding_notif_enabled else R.string.onboarding_notif_not_enabled,
        )
        binding.notifStatus.setTextColor(getColor(if (enabled) R.color.income else R.color.expense))
        binding.grantButton.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    private fun finishOnboarding() {
        appContainer.settings.onboardingDone = true
        if (NotifAccess.isEnabled(this)) NotifAccess.requestRebind(this)
        finish()
    }
}
