package com.jizhang.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.ui.MainActivity
import com.jizhang.app.ui.OnboardingActivity
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 真机 UI 冒烟测试：只点击真实控件，不直接写数据库。
 * 运行前会保存引导状态，结束后恢复，避免测试改变用户数据。
 */
@RunWith(AndroidJUnit4::class)
class UiSmokeTest {

    private val instrumentation: Instrumentation =
        InstrumentationRegistry.getInstrumentation()

    private val screenshotDir: File by lazy {
        val context: Context = ApplicationProvider.getApplicationContext()
        File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "JizhangTest")
    }

    @Test
    fun onboardingToMainAndSwitchTabs() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = SettingsStore(context)
        val saved = settings.onboardingDone
        try {
            settings.onboardingDone = false

            ActivityScenario.launch(OnboardingActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.findViewById<android.view.View>(R.id.startButton).performClick()
                }
            }

            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    val nav = activity.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                        R.id.bottomNav,
                    )
                    val tabs = linkedMapOf(
                        R.id.tab_ledger to "ledger",
                        R.id.tab_stats to "stats",
                        R.id.tab_pending to "pending",
                        R.id.tab_settings to "settings",
                    )
                    tabs.forEach { (id, name) ->
                        nav.selectedItemId = id
                        instrumentation.waitForIdleSync()
                        screenshot(activity, "elegant_$name")
                    }
                    listOf(
                        R.id.tab_ledger,
                    ).forEach { id ->
                        nav.selectedItemId = id
                        instrumentation.waitForIdleSync()
                    }
                }
            }
            assertTrue(settings.onboardingDone)
        } finally {
            settings.onboardingDone = saved
        }
    }

    private fun screenshot(activity: Activity, name: String) {
        val latch = CountDownLatch(1)
        activity.runOnUiThread {
            val view = activity.window.decorView
            view.measure(
                View.MeasureSpec.makeMeasureSpec(view.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(view.height, View.MeasureSpec.EXACTLY),
            )
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            view.draw(canvas)
            screenshotDir.mkdirs()
            FileOutputStream(File(screenshotDir, "$name.png")).use { output ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            }
            bitmap.recycle()
            latch.countDown()
        }
        assertTrue("screenshot $name timed out", latch.await(5, TimeUnit.SECONDS))
    }
}
