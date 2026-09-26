package com.jizhang.app.data.repo

import com.jizhang.app.data.db.dao.TransactionDao
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import com.jizhang.app.util.Dates
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.YearMonth

/** 明细页的筛选条件。 */
data class LedgerFilter(
    val kinds: Set<TxKind> = emptySet(),
    val categoryId: Long? = null,
    val accountId: Long? = null,
    val keyword: String = "",
) {
    val isActive: Boolean
        get() = kinds.isNotEmpty() || categoryId != null || accountId != null || keyword.isNotBlank()
}

class TransactionRepository(private val dao: TransactionDao) {

    fun observeAll(): Flow<List<Transaction>> = dao.observeAll()

    fun observeMonth(yearMonth: YearMonth): Flow<List<Transaction>> {
        val (from, to) = monthRange(yearMonth)
        return dao.observeBetween(from, to)
    }

    suspend fun listMonth(yearMonth: YearMonth): List<Transaction> {
        val (from, to) = monthRange(yearMonth)
        return dao.listBetween(from, to)
    }

    suspend fun listAll(): List<Transaction> = dao.listAll()

    suspend fun findById(id: Long): Transaction? = dao.findById(id)

    suspend fun add(tx: Transaction): Long = dao.insert(tx)

    suspend fun update(tx: Transaction) = dao.update(tx)

    suspend fun delete(tx: Transaction) = dao.delete(tx)

    suspend fun deleteById(id: Long) = dao.deleteById(id)

    /** 已存在的来源标识集合，用于导入去重。 */
    suspend fun existingSourceRefs(): Set<String> = dao.allSourceRefs().toSet()

    /**
     * 该来源标识是否已经写进账目。
     * `sourceRef` 为空表示无从判断，一律视为「不存在」，避免误挡住新账目。
     */
    suspend fun hasSourceRef(source: TxSource, sourceRef: String?): Boolean =
        sourceRef != null && dao.countBySource(source, sourceRef) > 0

    /** 批量插入，返回实际写入条数（唯一索引冲突会被跳过）。 */
    suspend fun addAll(list: List<Transaction>): Int {
        if (list.isEmpty()) return 0
        val ids = dao.insertAll(list)
        return ids.count { it != -1L }
    }

    suspend fun sumOfKind(yearMonth: YearMonth, kind: TxKind): Long {
        val (from, to) = monthRange(yearMonth)
        return dao.sumOfKind(from, to, kind)
    }

    suspend fun sumByCategory(yearMonth: YearMonth, kind: TxKind) = run {
        val (from, to) = monthRange(yearMonth)
        dao.sumByCategory(from, to, kind)
    }

    /**
     * 近 [months] 个月的逐月汇总，缺失的月份补 0。
     */
    suspend fun monthlyTotals(months: Int, kind: TxKind): List<Pair<YearMonth, Long>> {
        val end = YearMonth.now(Dates.ZONE)
        val start = end.minusMonths((months - 1).toLong())
        val from = start.atDay(1).atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()
        val to = end.plusMonths(1).atDay(1).atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()

        val raw = dao.sumByMonth(from, to, kind).associate { it.ym to it.cents }
        return (0 until months).map { offset ->
            val ym = start.plusMonths(offset.toLong())
            ym to (raw[ym.toString()] ?: 0L)
        }
    }

    suspend fun countInMonth(yearMonth: YearMonth): Int {
        val (from, to) = monthRange(yearMonth)
        return dao.countBetween(from, to)
    }

    suspend fun clearAll() = dao.clear()

    companion object {
        fun monthRange(yearMonth: YearMonth): Pair<Long, Long> {
            val from = yearMonth.atDay(1).atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()
            val to = yearMonth.plusMonths(1).atDay(1).atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()
            return from to to
        }

        fun dayRange(date: LocalDate): Pair<Long, Long> {
            val from = date.atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()
            val to = date.plusDays(1).atStartOfDay(Dates.ZONE).toInstant().toEpochMilli()
            return from to to
        }

        /** 生成去重用的来源标识：优先用微信交易单号。 */
        fun sourceRefOf(source: TxSource, rawRef: String?): String? =
            rawRef?.trim()?.takeIf { it.isNotEmpty() }?.let { "$source:$it" }
    }
}
