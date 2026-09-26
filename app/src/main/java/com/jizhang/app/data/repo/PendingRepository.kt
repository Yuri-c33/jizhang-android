package com.jizhang.app.data.repo

import androidx.room.withTransaction
import com.jizhang.app.data.db.AppDatabase
import com.jizhang.app.data.db.dao.NotificationLogDao
import com.jizhang.app.data.db.dao.PendingDao
import com.jizhang.app.data.db.entity.NotificationLog
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.PendingStatus
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxSource
import kotlinx.coroutines.flow.Flow

class PendingRepository(
    private val database: AppDatabase,
    private val pendingDao: PendingDao,
    private val logDao: NotificationLogDao,
    private val txRepo: TransactionRepository,
) {

    fun observePending(): Flow<List<PendingItem>> = pendingDao.observeByStatus(PendingStatus.PENDING)

    fun observePendingCount(): Flow<Int> = pendingDao.observePendingCount()

    fun observeLogs(): Flow<List<NotificationLog>> = logDao.observeRecent()

    suspend fun listPending(): List<PendingItem> = pendingDao.listPending()

    suspend fun findById(id: Long): PendingItem? = pendingDao.findById(id)

    /** 只更新仍处于待确认状态的分类建议，避免把已处理条目改回待确认。 */
    suspend fun setSuggestedCategory(id: Long, categoryId: Long?): Boolean =
        pendingDao.setSuggestedCategoryIfPending(id, categoryId) > 0

    /** 只更新仍处于待确认状态的转出账户建议。 */
    suspend fun setSuggestedAccount(id: Long, accountId: Long?): Boolean =
        pendingDao.setSuggestedAccountIfPending(id, accountId) > 0

    /** 只更新仍处于待确认状态的转入账户建议。 */
    suspend fun setSuggestedToAccount(id: Long, accountId: Long?): Boolean =
        pendingDao.setSuggestedToAccountIfPending(id, accountId) > 0

    suspend fun ignore(id: Long): Boolean =
        pendingDao.setStatusIfPending(id, PendingStatus.IGNORED) > 0

    /**
     * 确认入账：写入账目并标记待确认条目已完成。
     */
    suspend fun confirm(item: PendingItem, tx: Transaction): ConfirmResult =
        database.withTransaction {
            // 先抢占 PENDING 状态；后续任一步失败都会回滚这次抢占。
            if (pendingDao.setStatusIfPending(item.id, PendingStatus.CONFIRMED) == 0) {
                return@withTransaction ConfirmResult.AlreadyResolved
            }
            // 同一来源的账目已经在库里：只把条目标记成已处理，不再写第二条
            if (txRepo.hasSourceRef(tx.source, tx.sourceRef)) {
                return@withTransaction ConfirmResult.Duplicate
            }
            val txId = txRepo.add(tx)
            if (pendingDao.setTransactionId(item.id, txId) == 0) {
                throw IllegalStateException("待确认记录已被处理")
            }
            ConfirmResult.Confirmed(txId)
        }

    /**
     * 自动入账时原子完成去重、交易写入和已确认记录创建。
     *
     * 返回 null 表示「这次不该自动入账」：可能是重复推送，也可能是同来源账目
     * 已经存在（旧记录被清理后再次推送）。两种情况都不写账，由调用方决定是否
     * 落回待确认队列。
     */
    suspend fun autoConfirm(item: PendingItem, tx: Transaction): Long? =
        database.withTransaction {
            if (shouldSkipAsDuplicate(item)) return@withTransaction null
            if (txRepo.hasSourceRef(tx.source, tx.sourceRef)) return@withTransaction null
            val txId = txRepo.add(tx)
            val rowId = pendingDao.insert(item.copy(status = PendingStatus.CONFIRMED, transactionId = txId))
            if (rowId == -1L) {
                throw IllegalStateException("通知记录重复")
            }
            txId
        }

    /**
     * 这个指纹是否应该按「重复通知」跳过；顺带回收过期旧行。
     *
     * 指纹的唯一索引跨越全部状态，所以一条已确认/已忽略的记录会永久占住指纹，
     * 让之后内容完全相同的新通知被静默吞掉。这里把去重收窄成「时间窗口内」：
     *
     * - 命中 PENDING：同一条通知被系统重复推送，跳过；
     * - 命中已处理行且在 [DEDUP_WINDOW_MS] 内：按重复推送处理，跳过；
     * - 命中已处理行但已超出窗口：更可能是新的一笔相同金额交易，回收旧行后放行。
     */
    private suspend fun shouldSkipAsDuplicate(item: PendingItem): Boolean {
        val existing = pendingDao.findByFingerprint(item.fingerprint) ?: return false
        if (existing.status == PendingStatus.PENDING) return true
        if (item.postedAt - existing.postedAt <= DEDUP_WINDOW_MS) return true
        pendingDao.deleteById(existing.id)
        return false
    }

    /** 未自动入账的通知只入队；重复通知按 [shouldSkipAsDuplicate] 判断。 */
    suspend fun enqueueNotification(item: PendingItem): Long {
        return database.withTransaction {
            if (shouldSkipAsDuplicate(item)) return@withTransaction -1L
            pendingDao.insert(item)
        }
    }

    /**
     * 顺带清理已处理的历史行，避免 pending 表无限增长。
     * 清理后对应指纹可以被新通知重新占用。
     */
    suspend fun trimResolved(now: Long = System.currentTimeMillis()): Int =
        pendingDao.deleteResolvedBefore(now - RESOLVED_RETENTION_MS)

    suspend fun logNotification(log: NotificationLog) {
        logDao.insert(log)
        logDao.trim()
        // 通知到达是最自然且稳定会触发的时机，顺带回收已处理的历史条目
        trimResolved(log.capturedAt)
    }

    suspend fun clearLogs() = logDao.clear()

    suspend fun existingRefs(): Set<String> = txRepo.existingSourceRefs()

    fun sourceRefOf(raw: String?): String? = TransactionRepository.sourceRefOf(TxSource.NOTIFICATION, raw)

    private companion object {
        /**
         * 同一条通知被系统重复推送的去重窗口。
         * 微信刷新同一条通知通常在数秒到数分钟内完成，10 分钟足够覆盖；
         * 超出该窗口的相同指纹更可能是一笔新的、金额相同的交易。
         */
        const val DEDUP_WINDOW_MS = 10 * 60 * 1000L

        /** 已处理（确认/忽略）记录的保留时长，到期自动清理。 */
        const val RESOLVED_RETENTION_MS = 30L * 24 * 60 * 60 * 1000
    }
}

sealed interface ConfirmResult {
    data class Confirmed(val transactionId: Long) : ConfirmResult

    data object AlreadyResolved : ConfirmResult

    /** 同来源账目已存在，本次未重复写入。 */
    data object Duplicate : ConfirmResult
}
