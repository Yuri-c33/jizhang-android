package com.jizhang.app.ui.importbill

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.databinding.ActivityImportBinding
import com.jizhang.app.databinding.ItemImportPreviewBinding
import com.jizhang.app.importbill.BillReader
import com.jizhang.app.importbill.ColumnMapping
import com.jizhang.app.importbill.ImportCandidate
import com.jizhang.app.importbill.ImportPreview
import com.jizhang.app.importbill.ImportUseCase
import com.jizhang.app.importbill.ParseResult
import com.jizhang.app.importbill.PasswordRequiredException
import com.jizhang.app.importbill.WeChatBillParser
import com.jizhang.app.importbill.WrongPasswordException
import com.jizhang.app.money.Money
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import com.jizhang.app.util.Dates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.format.DateTimeFormatter

/** 导入微信账单：选文件 → 解析 → 预览 → 写入。 */
class ImportActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityImportBinding

    private val useCase by lazy {
        ImportUseCase(
            appContainer.transactionRepo,
            appContainer.categoryRepo,
            appContainer.accountRepo,
        )
    }

    private lateinit var adapter: PreviewAdapter
    private var preview: ImportPreview? = null
    private var parsedText: String? = null

    private val pickFile =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            readAndParse(uri)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityImportBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.pickButton.setOnClickListener { launchPicker() }
        binding.importButton.setOnClickListener { commit() }

        adapter = PreviewAdapter()
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter
    }

    private fun launchPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                    "text/csv",
                    "text/comma-separated-values",
                    "text/plain",
                ),
            )
        }
        pickFile.launch(intent)
    }

    private fun readAndParse(uri: Uri) {
        showStep(binding.loadingStep)
        lifecycleScope.launch {
            val bytes = withContext(Dispatchers.IO) {
                runCatching { contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
            }
            if (bytes == null) {
                showStep(binding.pickStep)
                binding.root.snack(getString(R.string.import_parse_failed, "无法读取文件"))
                return@launch
            }
            parse(bytes, queryFileName(uri), password = null)
        }
    }

    private fun queryFileName(uri: Uri): String {
        val projection = arrayOf(android.provider.OpenableColumns.DISPLAY_NAME)
        contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index) ?: FALLBACK_NAME
            }
        }
        return uri.lastPathSegment ?: FALLBACK_NAME
    }

    private fun parse(bytes: ByteArray, name: String, password: String?) {
        showStep(binding.loadingStep)
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val text = BillReader.read(bytes, name, password)
                    parsedText = text
                    WeChatBillParser.parse(text)
                }
            }

            outcome.fold(
                onSuccess = { result ->
                    if (result.rows.isEmpty() && result.mapping == null) {
                        showStep(binding.pickStep)
                        if (result.headerFields.isEmpty()) {
                            binding.root.snack(getString(R.string.import_bad_format))
                        } else {
                            askColumnMapping(result.headerFields)
                        }
                        return@fold
                    }
                    if (result.rows.isEmpty()) {
                        showStep(binding.pickStep)
                        binding.root.snack(getString(R.string.import_preview_empty))
                        return@fold
                    }
                    buildPreview(result)
                },
                onFailure = { e ->
                    when (e) {
                        is PasswordRequiredException -> askPassword(bytes, name)
                        is WrongPasswordException -> {
                            showStep(binding.pickStep)
                            binding.root.snack(getString(R.string.import_password_wrong))
                        }

                        else -> {
                            showStep(binding.pickStep)
                            binding.root.snack(
                                getString(R.string.import_parse_failed, e.message.orEmpty()),
                            )
                        }
                    }
                },
            )
        }
    }

    private fun askPassword(bytes: ByteArray, name: String) {
        showStep(binding.pickStep)
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.import_password_hint)
            setPadding(48, 24, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            maxLines = 1
        }
        layout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_password_title)
            .setMessage(R.string.import_help)
            .setView(layout)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                parse(bytes, name, input.text?.toString()?.trim())
            }
            .setNegativeButton(R.string.action_cancel, null)
            .setCancelable(false)
            .show()
    }

    /**
     * 自动识别表头失败时，让用户依次指定关键列。
     */
    private fun askColumnMapping(headerFields: List<String>) {
        val labels = listOf(
            getString(R.string.import_col_time),
            getString(R.string.import_col_amount),
            getString(R.string.import_col_kind),
            getString(R.string.import_col_counterparty),
            getString(R.string.import_col_item),
        )
        val indices = IntArray(labels.size) { ColumnMapping.NONE }
        val options = arrayOf(getString(R.string.import_col_none)) + headerFields

        fun askFor(position: Int) {
            if (position >= labels.size) {
                if (indices[0] == ColumnMapping.NONE || indices[1] == ColumnMapping.NONE) {
                    binding.root.snack(getString(R.string.import_bad_format))
                    return
                }
                val mapping = ColumnMapping(
                    timeIndex = indices[0],
                    amountIndex = indices[1],
                    kindIndex = indices[2],
                    counterpartyIndex = indices[3],
                    itemIndex = indices[4],
                    statusIndex = ColumnMapping.NONE,
                    tradeNoIndex = ColumnMapping.NONE,
                    merchantNoIndex = ColumnMapping.NONE,
                    payMethodIndex = ColumnMapping.NONE,
                    remarkIndex = ColumnMapping.NONE,
                    tradeTypeIndex = ColumnMapping.NONE,
                )
                val text = parsedText
                if (text == null) {
                    binding.root.snack(getString(R.string.import_bad_format))
                    return
                }
                val result = WeChatBillParser.parse(text, mapping)
                if (result.rows.isEmpty()) {
                    binding.root.snack(getString(R.string.import_preview_empty))
                } else {
                    buildPreview(result)
                }
                return
            }

            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.import_map_columns) + "：" + labels[position])
                .setItems(options) { _, which -> indices[position] = which - 1; askFor(position + 1) }
                .setNegativeButton(R.string.action_cancel, null)
                .show()
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.import_map_columns)
            .setMessage(R.string.import_map_columns_hint)
            .setPositiveButton(R.string.action_confirm) { _, _ -> askFor(0) }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun buildPreview(result: ParseResult) {
        showStep(binding.loadingStep)
        lifecycleScope.launch {
            val p = withContext(Dispatchers.IO) { useCase.preview(result) }
            preview = p
            renderPreview(p)
        }
    }

    private fun renderPreview(p: ImportPreview) {
        binding.totalView.text = p.total.toString()
        binding.newView.text = p.newCount.toString()
        binding.dupView.text = p.duplicateCount.toString()
        binding.skippedView.text = p.unrecognized.toString()
        binding.skippedGroup.visibility = if (p.unrecognized > 0) View.VISIBLE else View.GONE

        binding.rangeView.text = if (p.earliest != null && p.latest != null) {
            getString(
                R.string.import_range,
                Dates.formatFullDate(p.earliest),
                Dates.formatFullDate(p.latest),
            )
        } else {
            ""
        }
        binding.dupNotice.visibility = if (p.duplicateCount > 0) View.VISIBLE else View.GONE

        binding.importButton.text = getString(R.string.import_confirm_button, p.newCount)
        binding.importButton.isEnabled = p.newCount > 0

        adapter.submitList(p.candidates)
        showStep(binding.previewStep)
    }

    private fun commit() {
        val p = preview ?: return
        if (p.newCount == 0) {
            binding.root.snack(getString(R.string.import_nothing_new))
            return
        }
        showStep(binding.loadingStep)
        lifecycleScope.launch {
            val inserted = withContext(Dispatchers.IO) { useCase.commit(p) }
            appContainer.settings.hasImported = true
            binding.root.snack(getString(R.string.import_done, inserted, p.duplicateCount))
            finish()
        }
    }

    private fun showStep(step: View) {
        binding.pickStep.visibility = if (step === binding.pickStep) View.VISIBLE else View.GONE
        binding.previewStep.visibility = if (step === binding.previewStep) View.VISIBLE else View.GONE
        binding.loadingStep.visibility = if (step === binding.loadingStep) View.VISIBLE else View.GONE
    }

    private class PreviewAdapter : ListAdapter<ImportCandidate, PreviewHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PreviewHolder =
            PreviewHolder(
                ItemImportPreviewBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: PreviewHolder, position: Int) {
            holder.bind(getItem(position))
        }
    }

    private class PreviewHolder(private val binding: ItemImportPreviewBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(candidate: ImportCandidate) {
            val row = candidate.parsed
            binding.dateView.text = Dates.fromEpochMillis(row.occurredAt).format(SHORT_DATE)

            binding.titleView.text = row.counterparty?.takeIf { it.isNotBlank() }
                ?: row.item?.takeIf { it.isNotBlank() }
                ?: row.tradeType.orEmpty()

            val subs = mutableListOf<String>()
            row.tradeType?.takeIf { it.isNotBlank() }?.let { subs += it }
            row.payMethod?.takeIf { it.isNotBlank() }?.let { subs += it }
            binding.subView.text = subs.joinToString(" · ")

            binding.amountView.text = when (row.kind) {
                TxKind.EXPENSE -> Money.formatSigned(row.cents, negative = true)
                TxKind.INCOME -> Money.formatSigned(row.cents, negative = false)
                else -> Money.formatCents(row.cents)
            }
            binding.amountView.setTextColor(
                binding.root.context.getColor(
                    when (row.kind) {
                        TxKind.EXPENSE -> R.color.expense
                        TxKind.INCOME -> R.color.income
                        TxKind.TRANSFER -> R.color.transfer
                        TxKind.NEUTRAL -> R.color.on_surface_variant
                    },
                ),
            )

            binding.dupBadge.visibility = if (candidate.duplicate) View.VISIBLE else View.GONE
            binding.root.alpha = if (candidate.duplicate) 0.45f else 1f
        }
    }

    private companion object {
        const val FALLBACK_NAME = "bill.csv"
        val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd")

        val DIFF = object : DiffUtil.ItemCallback<ImportCandidate>() {
            override fun areItemsTheSame(oldItem: ImportCandidate, newItem: ImportCandidate): Boolean {
                val a = oldItem.sourceRef
                val b = newItem.sourceRef
                if (a != null && b != null) return a == b
                return oldItem.parsed.occurredAt == newItem.parsed.occurredAt &&
                    oldItem.parsed.cents == newItem.parsed.cents
            }

            override fun areContentsTheSame(oldItem: ImportCandidate, newItem: ImportCandidate) =
                oldItem == newItem
        }
    }
}
