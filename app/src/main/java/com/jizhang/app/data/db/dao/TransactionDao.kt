package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import kotlinx.coroutines.flow.Flow

/** 分类维度汇总（用于统计页排行榜与环形图）。 */
data class CategorySum(
    val categoryId: Long?,
    val cents: Long,
    val count: Int,
)

/** 月份维度汇总。 */
data class MonthSum(
    val ym: String,
    val cents: Long,
)

@Dao
interface TransactionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(tx: Transaction): Long

    /** 批量插入，唯一索引冲突时跳过（用于账单导入去重）。 */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Transaction>): List<Long>

    @Update
    suspend fun update(tx: Transaction)

    @Delete
    suspend fun delete(tx: Transaction)

    @Query("DELETE FROM tx WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM tx WHERE id = :id")
    suspend fun findById(id: Long): Transaction?

    @Query("SELECT * FROM tx ORDER BY occurredAt DESC, id DESC")
    fun observeAll(): Flow<List<Transaction>>

    @Query(
        """
        SELECT * FROM tx
        WHERE occurredAt >= :from AND occurredAt < :to
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    fun observeBetween(from: Long, to: Long): Flow<List<Transaction>>

    @Query(
        """
        SELECT * FROM tx
        WHERE occurredAt >= :from AND occurredAt < :to
        ORDER BY occurredAt DESC, id DESC
        """,
    )
    suspend fun listBetween(from: Long, to: Long): List<Transaction>

    @Query("SELECT * FROM tx ORDER BY occurredAt DESC, id DESC")
    suspend fun listAll(): List<Transaction>

    @Query("SELECT sourceRef FROM tx WHERE sourceRef IS NOT NULL")
    suspend fun allSourceRefs(): List<String>

    /**
     * 该来源标识是否已经有账目。
     *
     * [sourceRef] 为 null 时 SQL 的 `= NULL` 永远不成立、返回 0，
     * 也就是「无从判断重复」时不会误判成已存在。
     */
    @Query("SELECT COUNT(*) FROM tx WHERE source = :source AND sourceRef = :sourceRef")
    suspend fun countBySource(source: TxSource, sourceRef: String?): Int

    @Query("SELECT COUNT(*) FROM tx")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM tx WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM tx
        WHERE accountId = :accountId OR toAccountId = :accountId
        """,
    )
    suspend fun countByAccount(accountId: Long): Int

    /** 统计：某时间段内按分类汇总（只含参与统计的方向）。 */
    @Query(
        """
        SELECT categoryId AS categoryId, SUM(cents) AS cents, COUNT(*) AS count
        FROM tx
        WHERE occurredAt >= :from AND occurredAt < :to
          AND kind = :kind AND excludedFromStats = 0
        GROUP BY categoryId
        ORDER BY cents DESC
        """,
    )
    suspend fun sumByCategory(from: Long, to: Long, kind: TxKind): List<CategorySum>

    /** 统计：某时间段内某方向的总金额。 */
    @Query(
        """
        SELECT COALESCE(SUM(cents), 0) FROM tx
        WHERE occurredAt >= :from AND occurredAt < :to
          AND kind = :kind AND excludedFromStats = 0
        """,
    )
    suspend fun sumOfKind(from: Long, to: Long, kind: TxKind): Long

    /** 统计：近 N 个月逐月汇总。 */
    @Query(
        """
        SELECT strftime('%Y-%m', occurredAt / 1000, 'unixepoch', 'localtime') AS ym,
               SUM(cents) AS cents
        FROM tx
        WHERE occurredAt >= :from AND occurredAt < :to
          AND kind = :kind AND excludedFromStats = 0
        GROUP BY ym
        ORDER BY ym
        """,
    )
    suspend fun sumByMonth(from: Long, to: Long, kind: TxKind): List<MonthSum>

    /** 某时间段内的流水条数（空状态判断）。 */
    @Query("SELECT COUNT(*) FROM tx WHERE occurredAt >= :from AND occurredAt < :to")
    suspend fun countBetween(from: Long, to: Long): Int

    /** 账户净流出：支出为 +，收入为 −，用于算余额。 */
    @Query(
        """
        SELECT COALESCE(SUM(CASE
            WHEN kind = 'EXPENSE' THEN cents
            WHEN kind = 'INCOME' THEN -cents
            ELSE 0 END), 0)
        FROM tx WHERE accountId = :accountId
        """,
    )
    suspend fun netOutflowOfAccount(accountId: Long): Long

    /** 转入某账户的金额。 */
    @Query("SELECT COALESCE(SUM(cents), 0) FROM tx WHERE kind = 'TRANSFER' AND toAccountId = :accountId")
    suspend fun transferInOfAccount(accountId: Long): Long

    /** 从某账户转出的金额。 */
    @Query("SELECT COALESCE(SUM(cents), 0) FROM tx WHERE kind = 'TRANSFER' AND accountId = :accountId")
    suspend fun transferOutOfAccount(accountId: Long): Long

    @Query("DELETE FROM tx")
    suspend fun clear()
}
