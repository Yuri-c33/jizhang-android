package com.jizhang.app.importbill

import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.AccountType
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import com.jizhang.app.data.repo.AccountRepository
import com.jizhang.app.data.repo.CategoryRepository
import com.jizhang.app.data.repo.TransactionRepository
import java.security.MessageDigest

/** 一条待导入的候选记录（含解析出的分类/账户建议）。 */
data class ImportCandidate(
    val parsed: ParsedBillRow,
    val categoryId: Long?,
    val accountId: Long?,
    val toAccountId: Long?,
    val sourceRef: String?,
    val duplicate: Boolean,
)

/** 导入预览统计。 */
data class ImportPreview(
    val total: Int,
    val newCount: Int,
    val duplicateCount: Int,
    val unrecognized: Int,
    val candidates: List<ImportCandidate>,
    val earliest: Long?,
    val latest: Long?,
)

/**
 * 把解析出的账单行转成可写入的账目，并做去重。
 */
class ImportUseCase(
    private val txRepo: TransactionRepository,
    private val categoryRepo: CategoryRepository,
    private val accountRepo: AccountRepository,
) {

    /**
     * 生成预览：解析每一行，匹配分类与账户，标记重复项。
     */
    suspend fun preview(result: ParseResult): ImportPreview {
        val existingRefs = txRepo.existingSourceRefs()
        val accounts = accountRepo.listActive()
        val categories = categoryRepo.listAll()

        // 已有的 (时间,金额,对方) 三元组，用于兜底去重（老数据可能没有单号）
        val existingTriples = txRepo.listAll()
            .map { triple(it.occurredAt, it.cents, it.counterparty) }
            .toHashSet()

        val seenRefs = HashSet<String>()
        val seenTriples = HashSet<String>()

        val candidates = result.rows.map { row ->
            val ref = row.tradeNo?.takeIf { it.isNotBlank() }?.let { "wechat:$it" }
            val matchAccount = matchAccount(row.payMethod, accounts)
            val (categoryId, toAccountId) = matchCategoryAndTransfer(row, categories, accounts, matchAccount)

            val dupByRef = ref != null && (existingRefs.contains(ref) || seenRefs.contains(ref))
            if (ref != null) seenRefs += ref

            val triple = triple(row.occurredAt, row.cents, row.counterparty)
            val dupByTriple = !dupByRef && (existingTriples.contains(triple) || seenTriples.contains(triple))
            if (!dupByRef) seenTriples += triple

            val duplicate = dupByRef || dupByTriple

            ImportCandidate(
                parsed = row,
                categoryId = categoryId,
                accountId = matchAccount?.id,
                toAccountId = toAccountId,
                sourceRef = ref,
                duplicate = duplicate,
            )
        }

        val times = result.rows.map { it.occurredAt }
        return ImportPreview(
            total = result.rows.size,
            newCount = candidates.count { !it.duplicate },
            duplicateCount = candidates.count { it.duplicate },
            unrecognized = result.skipped,
            candidates = candidates,
            earliest = times.minOrNull(),
            latest = times.maxOrNull(),
        )
    }

    /**
     * 执行导入。只写入未重复的条目，返回实际新增数量。
     */
    suspend fun commit(preview: ImportPreview): Int {
        val toInsert = preview.candidates
            .filter { !it.duplicate }
            .map { candidate ->
                Transaction(
                    kind = candidate.parsed.kind,
                    cents = candidate.parsed.cents,
                    categoryId = candidate.categoryId,
                    accountId = candidate.accountId,
                    toAccountId = candidate.toAccountId,
                    occurredAt = candidate.parsed.occurredAt,
                    counterparty = candidate.parsed.counterparty,
                    item = candidate.parsed.item,
                    note = candidate.parsed.remark,
                    source = TxSource.IMPORT,
                    sourceRef = candidate.sourceRef ?: fallbackRef(candidate.parsed),
                    excludedFromStats = candidate.parsed.kind == TxKind.NEUTRAL,
                    confirmed = true,
                )
            }

        // 分批插入，避免单条 SQL 过大
        return toInsert.chunked(200).sumOf { batch -> txRepo.addAll(batch) }
    }

    /** 没有交易单号时，用内容摘要做去重键。 */
    private fun fallbackRef(row: ParsedBillRow): String {
        val raw = "${row.occurredAt}|${row.cents}|${row.counterparty.orEmpty()}|${row.item.orEmpty()}"
        val digest = MessageDigest.getInstance("MD5").digest(raw.toByteArray(Charsets.UTF_8))
        return "wechat:auto:" + digest.joinToString("") { "%02x".format(it) }
    }

    private fun triple(at: Long, cents: Long, counterparty: String?): String =
        "$at|$cents|${counterparty.orEmpty()}"

    /** 按支付方式匹配账户。 */
    private fun matchAccount(payMethod: String?, accounts: List<Account>): Account? {
        if (accounts.isEmpty()) return null
        val method = payMethod.orEmpty()

        fun firstOf(type: AccountType): Account? = accounts.firstOrNull { it.type == type }

        return when {
            method.contains("零钱通") -> firstOf(AccountType.WECHAT_LICAITONG) ?: firstOf(AccountType.WECHAT)
            method.contains("零钱") -> firstOf(AccountType.WECHAT)
            method.contains("工商") || method.contains("建设") || method.contains("农业") ||
                method.contains("中国银行") || method.contains("招商") || method.contains("交通银行") ||
                method.contains("邮储") || method.contains("银行") -> {
                // 优先按名称精确匹配用户自定义的卡
                accounts.firstOrNull { method.contains(it.name) } ?: firstOf(AccountType.BANK_CARD)
            }

            method.contains("信用卡") -> firstOf(AccountType.CREDIT_CARD)
            method.contains("支付宝") -> firstOf(AccountType.ALIPAY)
            method.contains("现金") -> firstOf(AccountType.CASH)
            else -> accounts.firstOrNull { it.type == AccountType.WECHAT } ?: accounts.first()
        }
    }

    /**
     * 匹配分类；转账类记录需要判断转入账户。
     */
    private suspend fun matchCategoryAndTransfer(
        row: ParsedBillRow,
        categories: List<Category>,
        accounts: List<Account>,
        sourceAccount: Account?,
    ): Pair<Long?, Long?> {
        if (row.kind == TxKind.TRANSFER) {
            val to = when {
                row.tradeType?.contains("提现") == true -> accounts.firstOrNull { it.type == AccountType.BANK_CARD }
                row.tradeType?.contains("充值") == true -> accounts.firstOrNull { it.type == AccountType.WECHAT }
                row.tradeType?.contains("还款") == true -> accounts.firstOrNull { it.type == AccountType.CREDIT_CARD }
                row.tradeType?.contains("零钱通") == true || row.tradeType?.contains("理财通") == true ->
                    accounts.firstOrNull { it.type == AccountType.WECHAT_LICAITONG }

                else -> null
            }
            return null to to?.id
        }

        if (row.kind == TxKind.NEUTRAL) return null to null

        val targetKind = if (row.kind == TxKind.INCOME) TxKind.INCOME else TxKind.EXPENSE
        val text = listOfNotNull(row.counterparty, row.item, row.tradeType).joinToString(" ")

        // 1) 用户学过的规则优先
        val learned = categoryRepo.suggestCategoryId(text, targetKind)
        if (learned != null) return learned to null

        // 2) 按交易类型做兜底
        val byTradeType = when {
            row.tradeType?.contains("转账") == true -> "人情往来"
            row.tradeType?.contains("红包") == true ->
                if (targetKind == TxKind.INCOME) "红包" else "人情往来"

            row.tradeType?.contains("退款") == true -> "退款"
            row.tradeType?.contains("群收款") == true -> "人情往来"
            else -> null
        }
        val fallback = byTradeType?.let { name -> categories.firstOrNull { it.name == name && it.kind == targetKind } }
        if (fallback != null) return fallback.id to null

        // 3) 兜底到「其他」
        val other = categories.firstOrNull {
            it.kind == targetKind && (it.name == "其他支出" || it.name == "其他收入")
        }
        return other?.id to null
    }
}
