package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.PendingStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(item: PendingItem): Long

    @Query("SELECT * FROM pending WHERE status = :status ORDER BY postedAt DESC, id DESC")
    fun observeByStatus(status: PendingStatus): Flow<List<PendingItem>>

    @Query("SELECT * FROM pending WHERE status = 'PENDING' ORDER BY postedAt DESC, id DESC")
    suspend fun listPending(): List<PendingItem>

    @Query("SELECT * FROM pending WHERE id = :id")
    suspend fun findById(id: Long): PendingItem?

    @Query("SELECT COUNT(*) FROM pending WHERE status = 'PENDING'")
    fun observePendingCount(): Flow<Int>

    /** 指纹唯一，最多命中一行；用于判断旧记录是否只是重复推送。 */
    @Query("SELECT * FROM pending WHERE fingerprint = :fingerprint")
    suspend fun findByFingerprint(fingerprint: String): PendingItem?

    /** 无条件删除：仅用于回收「已处理且超过去重窗口」的旧行。 */
    @Query("DELETE FROM pending WHERE id = :id")
    suspend fun deleteById(id: Long): Int

    /**
     * 清理已处理（确认/忽略）且通知时间早于 [cutoff] 的历史行。
     * 待确认行不受影响，且清理后对应指纹可以被新通知重新占用。
     */
    @Query("DELETE FROM pending WHERE status != 'PENDING' AND postedAt < :cutoff")
    suspend fun deleteResolvedBefore(cutoff: Long): Int

    @Query("UPDATE pending SET status = :status WHERE id = :id AND status = 'PENDING'")
    suspend fun setStatusIfPending(id: Long, status: PendingStatus): Int

    @Query("UPDATE pending SET transactionId = :txId WHERE id = :id AND status = 'CONFIRMED'")
    suspend fun setTransactionId(id: Long, txId: Long): Int

    @Query(
        """
        UPDATE pending SET suggestedCategoryId = :categoryId
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun setSuggestedCategoryIfPending(id: Long, categoryId: Long?): Int

    @Query(
        """
        UPDATE pending SET suggestedAccountId = :accountId
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun setSuggestedAccountIfPending(id: Long, accountId: Long?): Int

    @Query(
        """
        UPDATE pending SET suggestedToAccountId = :toAccountId
        WHERE id = :id AND status = 'PENDING'
        """,
    )
    suspend fun setSuggestedToAccountIfPending(id: Long, toAccountId: Long?): Int

    /** 工厂重置用：清空整张表。 */
    @Query("DELETE FROM pending")
    suspend fun clearAll()

    @Query(
        """
        SELECT COUNT(*) FROM pending
        WHERE suggestedCategoryId = :categoryId AND status = 'PENDING'
        """,
    )
    suspend fun countBySuggestedCategory(categoryId: Long): Int

    @Query(
        """
        SELECT COUNT(*) FROM pending
        WHERE (suggestedAccountId = :accountId OR suggestedToAccountId = :accountId)
          AND status = 'PENDING'
        """,
    )
    suspend fun countBySuggestedAccount(accountId: Long): Int
}
