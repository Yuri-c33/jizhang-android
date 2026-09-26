package com.jizhang.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.NotificationLog
import com.jizhang.app.databinding.ActivityDiagnosticBinding
import com.jizhang.app.databinding.ItemNotificationLogBinding
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import com.jizhang.app.util.Dates
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter

/**
 * 通知诊断页。
 * 识别规则对不上时，用户可在这里复制微信原始通知文本反馈，以便调整规则。
 */
class DiagnosticActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityDiagnosticBinding
    private lateinit var adapter: LogAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDiagnosticBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.clearButton.setOnClickListener {
            MaterialAlertDialogBuilder(this)
                .setMessage(R.string.diag_clear_question)
                .setPositiveButton(R.string.action_delete) { _, _ ->
                    lifecycleScope.launch { appContainer.pendingRepo.clearLogs() }
                }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        adapter = LogAdapter { log -> copy(log) }
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        lifecycleScope.launch {
            appContainer.pendingRepo.observeLogs().collect { logs ->
                adapter.submitList(logs)
                binding.emptyView.visibility = if (logs.isEmpty()) View.VISIBLE else View.GONE
                binding.list.visibility = if (logs.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun copy(log: NotificationLog) {
        val text = buildString {
            appendLine("时间：${Dates.fromEpochMillis(log.capturedAt).format(TIME_FORMAT)}")
            appendLine("包名：${log.packageName}")
            appendLine("标题：${log.title.orEmpty()}")
            appendLine("正文：${log.text.orEmpty()}")
            append("规则：${log.matchedRule ?: "未匹配"}")
        }
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("微信通知", text))
        binding.root.snack(getString(R.string.diag_copied))
    }

    private inner class LogAdapter(
        private val onCopy: (NotificationLog) -> Unit,
    ) : ListAdapter<NotificationLog, LogHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): LogHolder =
            LogHolder(
                ItemNotificationLogBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: LogHolder, position: Int) {
            holder.bind(getItem(position), onCopy)
        }
    }

    private class LogHolder(private val binding: ItemNotificationLogBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(log: NotificationLog, onCopy: (NotificationLog) -> Unit) {
            binding.timeView.text = Dates.fromEpochMillis(log.capturedAt).format(TIME_FORMAT)
            binding.titleView.text = log.title.orEmpty()
            binding.textView.text = log.text.orEmpty()
            binding.statusView.setText(
                if (log.recognized) R.string.diag_recognized else R.string.diag_unrecognized,
            )
            binding.statusView.setTextColor(
                binding.root.context.getColor(
                    if (log.recognized) R.color.income else R.color.on_surface_variant,
                ),
            )
            binding.ruleView.text = log.matchedRule?.let { "规则：$it" } ?: "未匹配规则"
            binding.copyButton.setOnClickListener { onCopy(log) }
        }
    }

    private companion object {
        val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

        val DIFF = object : DiffUtil.ItemCallback<NotificationLog>() {
            override fun areItemsTheSame(oldItem: NotificationLog, newItem: NotificationLog) =
                oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: NotificationLog, newItem: NotificationLog) =
                oldItem == newItem
        }
    }
}
