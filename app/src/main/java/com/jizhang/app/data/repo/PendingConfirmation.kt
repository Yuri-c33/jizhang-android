package com.jizhang.app.data.repo

import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource

/**
 * 待确认条目转成账目时的纯规则校验。
 *
 * UI 只负责收集用户选择，最终能否入账由这里统一判定，
 * 避免确认按钮和批量确认走两套不一致的规则。
 */
object PendingConfirmation {

    sealed interface Result {
        data class Valid(val transaction: Transaction) : Result

        data object MissingAmount : Result

        data object MissingAccount : Result

        data object MissingCategory : Result

        data object MissingToAccount : Result

        data object SameAccount : Result
    }

    fun build(
        item: PendingItem,
        fallbackCategoryId: Long?,
        fallbackAccountId: Long?,
    ): Result {
        val cents = item.cents ?: return Result.MissingAmount
        if (cents <= 0) return Result.MissingAmount

        val kind = item.kind ?: TxKind.EXPENSE
        val fromAccountId = item.suggestedAccountId ?: fallbackAccountId
            ?: return Result.MissingAccount
        val categoryId = item.suggestedCategoryId ?: fallbackCategoryId

        if (kind == TxKind.TRANSFER) {
            val toAccountId = item.suggestedToAccountId ?: return Result.MissingToAccount
            if (fromAccountId == toAccountId) return Result.SameAccount
            return Result.Valid(
                Transaction(
                    kind = kind,
                    cents = cents,
                    categoryId = null,
                    accountId = fromAccountId,
                    toAccountId = toAccountId,
                    occurredAt = item.postedAt,
                    counterparty = item.counterparty,
                    item = item.item,
                    source = TxSource.NOTIFICATION,
                    sourceRef = "notify:${item.fingerprint}",
                    excludedFromStats = true,
                    confirmed = true,
                ),
            )
        }

        if (categoryId == null) return Result.MissingCategory

        return Result.Valid(
            Transaction(
                kind = kind,
                cents = cents,
                categoryId = categoryId,
                accountId = fromAccountId,
                occurredAt = item.postedAt,
                counterparty = item.counterparty,
                item = item.item,
                source = TxSource.NOTIFICATION,
                sourceRef = "notify:${item.fingerprint}",
                excludedFromStats = kind == TxKind.NEUTRAL,
                confirmed = true,
            ),
        )
    }
}
