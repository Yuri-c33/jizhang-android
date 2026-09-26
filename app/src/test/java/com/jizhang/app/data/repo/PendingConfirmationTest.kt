package com.jizhang.app.data.repo

import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 待确认条目转账目规则的纯 JVM 测试。 */
class PendingConfirmationTest {

    private fun item(
        kind: TxKind = TxKind.EXPENSE,
        cents: Long? = 1200,
        categoryId: Long? = null,
        accountId: Long? = null,
        toAccountId: Long? = null,
    ) = PendingItem(
        fingerprint = "fp-1",
        kind = kind,
        cents = cents,
        suggestedCategoryId = categoryId,
        suggestedAccountId = accountId,
        suggestedToAccountId = toAccountId,
    )

    @Test
    fun `金额缺失或为零时拒绝入账`() {
        assertEquals(
            PendingConfirmation.Result.MissingAmount,
            PendingConfirmation.build(item(cents = null), null, 1L),
        )
        assertEquals(
            PendingConfirmation.Result.MissingAmount,
            PendingConfirmation.build(item(cents = 0), null, 1L),
        )
    }

    @Test
    fun `没有账户时不生成账目`() {
        assertEquals(
            PendingConfirmation.Result.MissingAccount,
            PendingConfirmation.build(item(), 7L, null),
        )
    }

    @Test
    fun `普通收支缺少分类时拒绝入账`() {
        assertEquals(
            PendingConfirmation.Result.MissingCategory,
            PendingConfirmation.build(item(), null, 1L),
        )
    }

    @Test
    fun `转账缺少转入账户时拒绝入账`() {
        assertEquals(
            PendingConfirmation.Result.MissingToAccount,
            PendingConfirmation.build(
                item(kind = TxKind.TRANSFER, accountId = 1L),
                null,
                null,
            ),
        )
    }

    @Test
    fun `转账转出转入相同时拒绝入账`() {
        assertEquals(
            PendingConfirmation.Result.SameAccount,
            PendingConfirmation.build(
                item(kind = TxKind.TRANSFER, accountId = 1L, toAccountId = 1L),
                null,
                null,
            ),
        )
    }

    @Test
    fun `转账不携带分类且不计入统计`() {
        val result = PendingConfirmation.build(
            item(kind = TxKind.TRANSFER, accountId = 1L, toAccountId = 2L),
            fallbackCategoryId = 9L,
            fallbackAccountId = null,
        )
        assertTrue(result is PendingConfirmation.Result.Valid)
        val tx = (result as PendingConfirmation.Result.Valid).transaction
        assertNull(tx.categoryId)
        assertEquals(1L, tx.accountId)
        assertEquals(2L, tx.toAccountId)
        assertTrue(tx.excludedFromStats)
        assertEquals("notify:fp-1", tx.sourceRef)
    }

    @Test
    fun `普通支出使用建议分类和账户`() {
        val result = PendingConfirmation.build(
            item(categoryId = 8L, accountId = 3L),
            fallbackCategoryId = 99L,
            fallbackAccountId = 4L,
        )
        val tx = (result as PendingConfirmation.Result.Valid).transaction
        assertEquals(8L, tx.categoryId)
        assertEquals(3L, tx.accountId)
        assertEquals(1200L, tx.cents)
        assertEquals(TxKind.EXPENSE, tx.kind)
    }

    @Test
    fun `缺少建议值时回退到首个分类和账户`() {
        val result = PendingConfirmation.build(
            item(categoryId = null, accountId = null),
            fallbackCategoryId = 6L,
            fallbackAccountId = 5L,
        )
        val tx = (result as PendingConfirmation.Result.Valid).transaction
        assertEquals(6L, tx.categoryId)
        assertEquals(5L, tx.accountId)
    }
}
