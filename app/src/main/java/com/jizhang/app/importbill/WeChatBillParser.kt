package com.jizhang.app.importbill

import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.money.Money
import com.jizhang.app.util.Dates
import java.time.LocalDateTime

/** 从账单文件里解析出的一条原始记录。 */
data class ParsedBillRow(
    val occurredAt: Long,
    val kind: TxKind,
    val cents: Long,
    val counterparty: String?,
    val item: String?,
    val payMethod: String?,
    val status: String?,
    val tradeNo: String?,
    val merchantNo: String?,
    val remark: String?,
    val tradeType: String?,
)

/** 解析结果：成功行 + 问题行统计。 */
data class ParseResult(
    val rows: List<ParsedBillRow>,
    val skipped: Int,
    val headerFields: List<String>,
    val mapping: ColumnMapping?,
)

/**
 * 表头列映射。微信不同版本/不同导出入口的列名略有差别，
 * 这里做成可配置的，并在自动识别失败时支持用户手动指定。
 */
data class ColumnMapping(
    val timeIndex: Int,
    val amountIndex: Int,
    val kindIndex: Int,
    val counterpartyIndex: Int,
    val itemIndex: Int,
    val statusIndex: Int,
    val tradeNoIndex: Int,
    val merchantNoIndex: Int,
    val payMethodIndex: Int,
    val remarkIndex: Int,
    val tradeTypeIndex: Int,
) {
    companion object {
        const val NONE = -1
    }
}

/**
 * 微信支付账单解析器。
 *
 * 微信导出的账单文件长这样：
 * ```
 * 微信支付账单明细
 * 微信昵称：[xxx]
 * 起始时间：[2024-01-01 00:00:00] 终止时间：[2024-03-31 23:59:59]
 * 导出类型：[全部]
 * 共 100 笔记录
 * 收入：20 笔 1000.00 元
 * 支出：80 笔 5000.00 元
 * 中性交易：0 笔 0.00 元
 * ----------------------微信支付账单明细列表--------------------
 * 交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
 * 2024-03-15 14:32:07,商户消费,星巴克,拿铁,支出,¥33.00,零钱,支付成功,42000...,...,/
 * ```
 */
object WeChatBillParser {

    /** 表头行之后可能出现、需要跳过的说明行。 */
    private val FOOTER_HINTS = listOf(
        "微信支付账单明细列表",
        "以上是您的账单明细",
        "微信支付账单",
        "账单明细",
    )

    /** 元信息行前缀，出现在表头之前。 */
    private val META_PREFIXES = listOf(
        "微信昵称", "起始时间", "导出类型", "共", "收入：", "支出：",
        "中性交易", "微信支付", "导出时间", "账单",
    )

    /**
     * 解析账单文本。
     *
     * @param text 已按正确编码解码的账单全文
     * @param override 用户手动指定的列映射（自动识别失败时）
     */
    fun parse(text: String, override: ColumnMapping? = null): ParseResult {
        if (text.isBlank()) return ParseResult(emptyList(), 0, emptyList(), null)

        val lines = text.lineSequence()
            .map { it.trimEnd('\r', '\n') }
            .filter { it.isNotBlank() }
            .toList()

        if (lines.isEmpty()) return ParseResult(emptyList(), 0, emptyList(), null)

        val headerIndex = override?.let { -1 } ?: findHeaderIndex(lines)
        val headerLine = if (headerIndex >= 0) lines[headerIndex] else null
        val headerFields = headerLine?.let { Csv.parseLine(it) }?.map { it.trim() } ?: emptyList()

        val mapping = override ?: inferMapping(headerFields)
            ?: return ParseResult(emptyList(), 0, headerFields, null)

        val dataStart = if (headerIndex >= 0) headerIndex + 1 else 0

        val rows = mutableListOf<ParsedBillRow>()
        var skipped = 0

        for (i in dataStart until lines.size) {
            val line = lines[i]
            if (isNoiseLine(line)) {
                continue
            }
            val fields = Csv.parseLine(line)
            val row = parseRow(fields, mapping)
            if (row == null) {
                skipped++
            } else {
                rows += row
            }
        }

        return ParseResult(rows, skipped, headerFields, mapping)
    }

    /**
     * 找到表头行下标，找不到返回 -1。
     *
     * 判定条件放宽到「同时出现时间列与金额列」，
     * 这样列名与官方略有差异的变体也能自动识别，少让用户手动映射。
     */
    private fun findHeaderIndex(lines: List<String>): Int {
        val limit = minOf(lines.size, 40)
        for (i in 0 until limit) {
            val fields = Csv.parseLine(lines[i])
            if (fields.size < 3) continue
            val hasTime = fields.any { it.contains("时间") }
            val hasAmount = fields.any { it.contains("金额") }
            if (hasTime && hasAmount) return i
        }
        return -1
    }

    /** 是否是应跳过的说明/统计行。 */
    private fun isNoiseLine(line: String): Boolean {
        if (line.startsWith("-")) return true
        if (FOOTER_HINTS.any { line.contains(it) } && !line.contains(",")) return true
        if (META_PREFIXES.any { line.startsWith(it) } && !line.contains(",")) return true
        return false
    }

    /**
     * 从表头字段推断列下标。识别不到关键列时返回 null。
     */
    fun inferMapping(header: List<String>): ColumnMapping? {
        if (header.isEmpty()) return null

        fun find(vararg names: String): Int =
            header.indexOfFirst { field -> names.any { field.contains(it) } }

        val timeIndex = find("交易时间", "时间")
        val amountIndex = find("金额")
        if (timeIndex < 0 || amountIndex < 0) return null

        // 「收/支」列在不同版本里叫法不一，这里把常见别名都算上
        val kindIndex = find("收/支", "收支", "收付", "方向", "收支类型")
        val counterpartyIndex = find("交易对方", "对方")
        val itemIndex = find("商品", "说明", "商品说明")
        val statusIndex = find("当前状态", "状态")
        val tradeNoIndex = find("交易单号", "单号")
        val merchantNoIndex = find("商户单号")
        val payMethodIndex = find("支付方式", "付款方式")
        val remarkIndex = find("备注")

        return ColumnMapping(
            timeIndex = timeIndex,
            amountIndex = amountIndex,
            kindIndex = kindIndex,
            counterpartyIndex = counterpartyIndex,
            itemIndex = itemIndex,
            statusIndex = statusIndex,
            tradeNoIndex = tradeNoIndex,
            merchantNoIndex = merchantNoIndex,
            payMethodIndex = payMethodIndex,
            remarkIndex = remarkIndex,
            tradeTypeIndex = find("交易类型", "类型"),
        )
    }

    private fun parseRow(fields: List<String>, m: ColumnMapping): ParsedBillRow? {
        fun at(index: Int): String? =
            if (index == ColumnMapping.NONE || index >= fields.size) null
            else fields[index].trim().trim('"').takeIf { it.isNotEmpty() }

        val timeText = at(m.timeIndex) ?: return null
        val dt: LocalDateTime = Dates.parseDateTime(timeText) ?: return null

        val amountText = at(m.amountIndex) ?: return null
        val cents = Money.parseYuanToCents(amountText) ?: return null

        val kind = resolveKind(at(m.kindIndex), at(m.tradeTypeIndex))

        return ParsedBillRow(
            occurredAt = Dates.toEpochMillis(dt),
            kind = kind,
            cents = kotlin.math.abs(cents),
            counterparty = at(m.counterpartyIndex),
            item = at(m.itemIndex),
            payMethod = at(m.payMethodIndex),
            status = at(m.statusIndex),
            tradeNo = at(m.tradeNoIndex),
            merchantNo = at(m.merchantNoIndex),
            // 微信账单的「备注」列常整列填「/」，视为没有备注
            remark = at(m.remarkIndex)?.takeIf { it != "/" && it != "-" },
            tradeType = at(m.tradeTypeIndex),
        )
    }

    /**
     * 判断交易方向。
     *
     * 「收/支」列是最权威的依据：
     *  - 收入 → 收入
     *  - 支出 → 支出
     *  - /    → 中性交易，再按交易类型细分
     *
     * 没有「收/支」列的旧版本账单，则完全依赖交易类型推断。
     */
    fun resolveKind(kindText: String?, tradeType: String?): TxKind {
        when (val k = kindText?.trim()) {
            null -> return kindOfTradeType(tradeType)
            else -> {
                if (k.contains("收入")) return TxKind.INCOME
                if (k.contains("支出")) return TxKind.EXPENSE
                // 「/」「-」或空：中性交易，按交易类型细分
                return kindOfTradeType(tradeType)
            }
        }
    }

    /**
     * 按交易类型推断方向。
     * 账户间划转（提现/充值/还款/零钱通）算转账，不计入收支统计。
     */
    private fun kindOfTradeType(tradeType: String?): TxKind {
        val t = tradeType?.trim().orEmpty()
        if (t.isEmpty()) return TxKind.NEUTRAL
        return when {
            t.contains("提现") || t.contains("充值") || t.contains("还款") ||
                t.contains("零钱通") || t.contains("理财通") ||
                t.contains("转入") || t.contains("转出") -> TxKind.TRANSFER

            t.contains("退款") || t.contains("收款") -> TxKind.INCOME

            t.contains("商户消费") || t.contains("消费") ||
                t.contains("付款") || t.contains("转账") ||
                t.contains("扫二维码") || t.contains("红包") -> TxKind.EXPENSE

            else -> TxKind.NEUTRAL
        }
    }
}
