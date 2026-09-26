package com.jizhang.app.ui.export

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.jizhang.app.AppContainer
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxSource
import com.jizhang.app.money.Money
import com.jizhang.app.util.Dates
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 导出全部账目为 CSV，写入系统「下载」目录。
 * 用 MediaStore，Android 10 以上无需存储权限。
 */
object ExportUseCase {

    class EmptyDataException : Exception()

    private val HEADERS = listOf(
        "交易时间", "类型", "分类", "金额(元)", "账户", "转入账户",
        "交易对方", "商品说明", "备注", "来源", "交易单号",
    )

    private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    suspend fun run(context: Context, container: AppContainer): Result<String> = runCatching {
        val txs = container.transactionRepo.listAll()
        if (txs.isEmpty()) throw EmptyDataException()

        val categories = container.categoryRepo.listAll().associateBy { it.id }
        val accounts = container.accountRepo.listAll().associateBy { it.id }

        val fileName = "记账本备份_" +
            LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".csv"

        val content = buildString {
            append('\uFEFF') // BOM，让 Excel 正确识别 UTF-8
            appendLine(HEADERS.joinToString(","))
            txs.sortedBy { it.occurredAt }.forEach { tx ->
                val row = listOf(
                    Dates.fromEpochMillis(tx.occurredAt).format(TIME_FORMAT),
                    tx.kind.label,
                    categories[tx.categoryId]?.name.orEmpty(),
                    Money.formatCents(tx.cents),
                    accounts[tx.accountId]?.name.orEmpty(),
                    accounts[tx.toAccountId]?.name.orEmpty(),
                    tx.counterparty.orEmpty(),
                    tx.item.orEmpty(),
                    tx.note.orEmpty(),
                    tx.source.label,
                    cleanRef(tx.sourceRef),
                )
                appendLine(row.joinToString(",") { escape(it) })
            }
        }.toByteArray(Charsets.UTF_8)

        writeToDownloads(context, fileName, content)
        container.settings.lastBackupAt = System.currentTimeMillis()
        fileName
    }

    /** 去掉内部前缀，只保留原始单号。 */
    private fun cleanRef(ref: String?): String {
        if (ref.isNullOrBlank()) return ""
        return ref
            .removePrefix("${TxSource.IMPORT}:")
            .removePrefix("${TxSource.NOTIFICATION}:")
    }

    private fun escape(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }

    private fun writeToDownloads(context: Context, fileName: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.MIME_TYPE, "text/csv")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri: Uri = context.contentResolver
                .insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("无法创建文件")
            context.contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: throw IllegalStateException("无法写入文件")
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!dir.exists()) dir.mkdirs()
            java.io.File(dir, fileName).outputStream().use { it.write(bytes) }
        }
    }

    /** 生成 CSV 文本，便于单元测试。 */
    fun buildCsv(txs: List<Transaction>): String = buildString {
        append('\uFEFF')
        appendLine(HEADERS.joinToString(","))
        txs.forEach { tx ->
            val row = listOf(
                Dates.fromEpochMillis(tx.occurredAt).format(TIME_FORMAT),
                tx.kind.label,
                "",
                Money.formatCents(tx.cents),
                "",
                "",
                tx.counterparty.orEmpty(),
                tx.item.orEmpty(),
                tx.note.orEmpty(),
                tx.source.label,
                cleanRef(tx.sourceRef),
            )
            appendLine(row.joinToString(",") { escape(it) })
        }
    }
}
