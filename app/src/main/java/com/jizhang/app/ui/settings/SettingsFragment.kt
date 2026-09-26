package com.jizhang.app.ui.settings

import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jizhang.app.BuildConfig
import com.jizhang.app.R
import com.jizhang.app.data.prefs.ThemeMode
import com.jizhang.app.data.prefs.UiStyle
import com.jizhang.app.data.prefs.ColorPalette
import com.jizhang.app.databinding.FragmentSettingsBinding
import com.jizhang.app.databinding.ItemSettingRowBinding
import com.jizhang.app.notify.NotifAccess
import com.jizhang.app.ui.AccountManageActivity
import com.jizhang.app.ui.CategoryManageActivity
import com.jizhang.app.ui.DiagnosticActivity
import com.jizhang.app.ui.OnboardingActivity
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import com.jizhang.app.ui.export.ExportUseCase
import com.jizhang.app.ui.importbill.ImportActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 设置页。 */
class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        buildNotifListenerRow()
        buildAutoConfirmRow()
        buildAutoConfidenceRow()
        buildNotifAccountRow()
        buildDiagnosticsRow()
        buildImportRow()
        buildExportRow()
        buildClearRow()
        buildCategoryRow()
        buildAccountRow()
        buildThemeRow()
        buildUiStyleRow()
        buildPaletteRow()
        buildRememberRow()

        applyDividers()

        binding.versionRow.text = getString(R.string.settings_version) + " " + BuildConfig.VERSION_NAME
    }

    override fun onResume() {
        super.onResume()
        // 从系统设置返回后刷新授权状态
        refreshNotifRow()
    }

    private fun row(): ItemSettingRowBinding = ItemSettingRowBinding.inflate(layoutInflater)

    /**
     * 卡片内相邻行之间显示分割线，最后一行的分割线隐藏。
     * 与 Google 设置页一致：分组靠留白和卡片区分，行与行之间只用极细的线。
     */
    private fun applyDividers() {
        GROUP_CONTAINER_IDS.forEach { groupId ->
            val group = binding.root.findViewById<LinearLayout>(groupId) ?: return@forEach
            // 只有可见的行参与分割线计算：最后一行不画线，被隐藏的行（经典风格下的
            // 配色入口）也不会在中间留下一条悬空的线。
            val visibleRows = (0 until group.childCount)
                .map { group.getChildAt(it) }
                .filter { it.visibility == View.VISIBLE }
            visibleRows.forEachIndexed { index, rowView ->
                val divider = rowView.findViewById<View>(R.id.dividerView) ?: return@forEachIndexed
                divider.visibility = if (index < visibleRows.lastIndex) View.VISIBLE else View.GONE
            }
        }
    }

    private companion object {
        /** 每张设置卡片内部承载若干行，分割线按卡片分组计算。 */
        val GROUP_CONTAINER_IDS = intArrayOf(
            R.id.autoGroup,
            R.id.dataGroup,
            R.id.categoryGroup,
            R.id.appearanceGroup,
        )
    }

    // ---- 自动记账 ----

    private fun buildNotifListenerRow() {
        val row = row()
        row.titleView.setText(R.string.settings_notif_listener)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_notif_listener_desc)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            if (NotifAccess.isEnabled(requireContext())) {
                NotifAccess.openSettings(requireContext())
            } else {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_notif_howto)
                    .setMessage(
                        getString(R.string.onboarding_step1_desc) + "\n\n" +
                            getString(R.string.onboarding_notif_warning),
                    )
                    .setPositiveButton(R.string.onboarding_grant_now) { _, _ ->
                        NotifAccess.openSettings(requireContext())
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        binding.notifListenerRow.addView(row.root)
    }

    private fun refreshNotifRow() {
        if (_binding == null) return
        val row = binding.notifListenerRow.getChildAt(0) ?: return
        val value = row.findViewById<TextView>(R.id.valueView) ?: return
        // 可能是刚从系统的「通知使用权」页回来：检测到「刚授权」会补一次重绑
        val on = NotifAccess.syncOnResume(requireContext())
        value.setText(
            if (on) R.string.settings_notif_listener_on else R.string.settings_notif_listener_off,
        )
        value.setTextColor(requireContext().getColor(if (on) R.color.income else R.color.expense))
    }

    private fun buildAutoConfirmRow() {
        val row = row()
        row.titleView.setText(R.string.settings_auto_confirm)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_auto_confirm_desc)
        row.switchView.visibility = View.VISIBLE
        row.switchView.isChecked = appContainer.settings.autoConfirmNotifications
        row.switchView.setOnCheckedChangeListener { _, checked ->
            appContainer.settings.autoConfirmNotifications = checked
        }
        row.root.setOnClickListener { row.switchView.toggle() }
        binding.autoConfirmRow.addView(row.root)
    }

    private fun buildAutoConfidenceRow() {
        val row = row()
        row.titleView.setText(R.string.settings_auto_confidence)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE

        val labels = listOf("高", "中", "低")
        val values = listOf("HIGH", "MEDIUM", "LOW")

        fun currentLabel(): String =
            labels[values.indexOf(appContainer.settings.autoConfirmMinConfidence).coerceAtLeast(0)]

        row.valueView.text = currentLabel()
        row.root.setOnClickListener {
            val current = values.indexOf(appContainer.settings.autoConfirmMinConfidence).coerceAtLeast(0)
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_auto_confidence)
                .setSingleChoiceItems(labels.toTypedArray(), current) { dialog, which ->
                    appContainer.settings.autoConfirmMinConfidence = values[which]
                    row.valueView.text = labels[which]
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.autoConfidenceRow.addView(row.root)
    }

    private fun buildNotifAccountRow() {
        val row = row()
        row.titleView.setText(R.string.settings_notif_account)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE

        fun refresh() {
            lifecycleScope.launch {
                val accounts = appContainer.accountRepo.listActive()
                val id = appContainer.settings.defaultAccountId
                row.valueView.text = accounts.firstOrNull { it.id == id }?.name
                    ?: accounts.firstOrNull()?.name
                    ?: getString(R.string.empty_no_account)
            }
        }
        refresh()

        row.root.setOnClickListener {
            lifecycleScope.launch {
                val accounts = appContainer.accountRepo.listActive()
                if (accounts.isEmpty()) {
                    startActivity(Intent(requireContext(), AccountManageActivity::class.java))
                    return@launch
                }
                val names = accounts.map { it.name }.toTypedArray()
                val current = accounts.indexOfFirst { it.id == appContainer.settings.defaultAccountId }
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.settings_notif_account)
                    .setSingleChoiceItems(names, current) { dialog, which ->
                        appContainer.settings.defaultAccountId = accounts[which].id
                        row.valueView.text = accounts[which].name
                        dialog.dismiss()
                    }
                    .setNegativeButton(R.string.action_cancel, null)
                    .show()
            }
        }
        binding.notifAccountRow.addView(row.root)
    }

    private fun buildDiagnosticsRow() {
        val row = row()
        row.titleView.setText(R.string.settings_diagnostics)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_diagnostics_desc)
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            startActivity(Intent(requireContext(), DiagnosticActivity::class.java))
        }
        binding.diagnosticsRow.addView(row.root)
    }

    // ---- 数据 ----

    private fun buildImportRow() {
        val row = row()
        row.titleView.setText(R.string.settings_import)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_import_desc)
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            startActivity(Intent(requireContext(), ImportActivity::class.java))
        }
        binding.importRow.addView(row.root)
    }

    private fun buildExportRow() {
        val row = row()
        row.titleView.setText(R.string.settings_export)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_export_desc)
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) {
                    ExportUseCase.run(requireContext(), appContainer)
                }
                result.fold(
                    onSuccess = { name -> binding.root.snack(getString(R.string.settings_exported, name)) },
                    onFailure = { e ->
                        val message = if (e is ExportUseCase.EmptyDataException) {
                            getString(R.string.settings_export_empty)
                        } else {
                            getString(R.string.settings_export_failed, e.message.orEmpty())
                        }
                        binding.root.snack(message)
                    },
                )
            }
        }
        binding.exportRow.addView(row.root)
    }

    private fun buildClearRow() {
        val row = row()
        row.titleView.setText(R.string.settings_clear_data)
        row.titleView.setTextColor(requireContext().getColor(R.color.expense))
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_clear_data_desc)
        row.root.setOnClickListener {
            confirmFactoryReset()
        }
        binding.clearRow.addView(row.root)
    }

    private fun confirmFactoryReset() {
        val checks = listOf(
            R.string.settings_reset_item_tx,
            R.string.settings_reset_item_pending,
            R.string.settings_reset_item_logs,
            R.string.settings_reset_item_rules,
            R.string.settings_reset_item_settings,
        ).joinToString("\n") { "• " + getString(it) }

        val messageView = LinearLayout(requireContext()).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(
                TextView(requireContext()).apply {
                    text = getString(R.string.settings_reset_confirm)
                    textSize = 15f
                },
            )
            addView(
                TextView(requireContext()).apply {
                    text = checks
                    textSize = 14f
                    setTextColor(requireContext().getColor(R.color.expense))
                    setPadding(0, 16, 0, 0)
                    gravity = Gravity.START
                },
            )
        }

        var dialog: AlertDialog? = null
        dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_reset_title)
            .setView(messageView)
            .setPositiveButton(R.string.settings_reset_action) { _, _ ->
                lifecycleScope.launch {
                    appContainer.factoryResetRepo.reset()
                    val intent = Intent(requireContext(), OnboardingActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    requireContext().startActivity(intent)
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    // ---- 分类与账户 ----

    private fun buildCategoryRow() {
        val row = row()
        row.titleView.setText(R.string.settings_category_manage)
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            startActivity(Intent(requireContext(), CategoryManageActivity::class.java))
        }
        binding.categoryRow.addView(row.root)
    }

    private fun buildAccountRow() {
        val row = row()
        row.titleView.setText(R.string.settings_account_manage)
        row.chevronView.visibility = View.VISIBLE
        row.root.setOnClickListener {
            startActivity(Intent(requireContext(), AccountManageActivity::class.java))
        }
        binding.accountRow.addView(row.root)
    }

    // ---- 外观 ----

    private fun buildThemeRow() {
        val row = row()
        row.titleView.setText(R.string.settings_theme)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE
        val modes = ThemeMode.entries
        row.valueView.text = appContainer.settings.themeMode.label
        row.root.setOnClickListener {
            val labels = modes.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_theme)
                .setSingleChoiceItems(
                    labels,
                    modes.indexOf(appContainer.settings.themeMode),
                ) { dialog, which ->
                    appContainer.settings.themeMode = modes[which]
                    modes[which].apply()
                    row.valueView.text = modes[which].label
                    dialog.dismiss()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.themeRow.addView(row.root)
    }

    private fun buildRememberRow() {
        val row = row()
        row.titleView.setText(R.string.settings_remember)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_remember_desc)
        row.switchView.visibility = View.VISIBLE
        row.switchView.isChecked = appContainer.settings.rememberLastSelection
        row.switchView.setOnCheckedChangeListener { _, checked ->
            appContainer.settings.rememberLastSelection = checked
        }
        row.root.setOnClickListener { row.switchView.toggle() }
        binding.rememberRow.addView(row.root)
    }

    private fun buildUiStyleRow() {
        val row = row()
        row.titleView.setText(R.string.settings_ui_style)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_ui_style_desc)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE
        val styles = UiStyle.entries
        row.valueView.text = appContainer.settings.uiStyle.label
        row.root.setOnClickListener {
            val labels = styles.map { it.label }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_ui_style)
                .setSingleChoiceItems(
                    labels,
                    styles.indexOf(appContainer.settings.uiStyle),
                ) { dialog, which ->
                    val picked = styles[which]
                    appContainer.settings.uiStyle = picked
                    row.valueView.text = picked.label
                    dialog.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.uiStyleRow.addView(row.root)
    }

    private fun buildPaletteRow() {
        val row = row()
        row.titleView.setText(R.string.settings_palette)
        row.descView.visibility = View.VISIBLE
        row.descView.setText(R.string.settings_palette_desc)
        row.valueView.visibility = View.VISIBLE
        row.chevronView.visibility = View.VISIBLE

        /** 每个方案一枚色点，一眼能看出可选范围；当前方案跟在后面。 */
        fun palettePreview(selected: ColorPalette): CharSequence {
            val dots = ColorPalette.entries.joinToString("") { "\u25CF" }
            val text = SpannableString("$dots  ${selected.label}")
            var index = 0
            ColorPalette.entries.forEach { palette ->
                text.setSpan(
                    ForegroundColorSpan(requireContext().getColor(palette.dotColor)),
                    index,
                    index + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                index++
            }
            return text
        }

        fun refresh() {
            row.valueView.text = palettePreview(appContainer.settings.colorPalette)
        }
        refresh()

        // 隐藏整行容器（而不是行本身），分割线统计可见行时才能正确跳过它。
        binding.paletteRow.visibility =
            if (appContainer.settings.uiStyle == UiStyle.ELEGANT) View.VISIBLE else View.GONE

        row.root.setOnClickListener {
            val palettes = ColorPalette.entries
            val labels = palettes.map { palette ->
                val text = SpannableString("\u25CF  ${palette.label}")
                text.setSpan(
                    ForegroundColorSpan(requireContext().getColor(palette.dotColor)),
                    0,
                    1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                text as CharSequence
            }.toTypedArray()
            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.settings_palette)
                .setSingleChoiceItems(
                    labels,
                    palettes.indexOf(appContainer.settings.colorPalette),
                ) { dialog, which ->
                    appContainer.settings.colorPalette = palettes[which]
                    refresh()
                    dialog.dismiss()
                    requireActivity().recreate()
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }
        binding.paletteRow.addView(row.root)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
