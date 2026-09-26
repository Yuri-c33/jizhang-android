package com.jizhang.app.data.repo

import androidx.room.withTransaction
import com.jizhang.app.data.db.AppDatabase
import com.jizhang.app.data.db.dao.AccountDao
import com.jizhang.app.data.db.dao.PendingDao
import com.jizhang.app.data.db.dao.TransactionDao
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.AccountType
import kotlinx.coroutines.flow.Flow

/** 账户余额快照。 */
data class AccountBalance(
    val account: Account,
    val cents: Long,
)

class AccountRepository(
    private val database: AppDatabase,
    private val accountDao: AccountDao,
    private val txDao: TransactionDao,
    private val pendingDao: PendingDao,
    private val settings: SettingsStore,
) {

    fun observeActive(): Flow<List<Account>> = accountDao.observeActive()

    fun observeAll(): Flow<List<Account>> = accountDao.observeAll()

    suspend fun listActive(): List<Account> = accountDao.listActive()

    suspend fun listAll(): List<Account> = accountDao.listAll()

    suspend fun findById(id: Long): Account? = accountDao.findById(id)

    /** 找到默认建议账户（微信零钱优先）。 */
    suspend fun defaultAccount(): Account? =
        accountDao.findFirstByType(AccountType.WECHAT)
            ?: accountDao.listActive().firstOrNull()

    suspend fun add(account: Account): Long = accountDao.insert(account)

    suspend fun update(account: Account) = accountDao.update(account)

    suspend fun referenceCount(account: Account): Int =
        txDao.countByAccount(account.id) +
            pendingDao.countBySuggestedAccount(account.id) +
            if (settings.defaultAccountId == account.id) 1 else 0

    /** 仅在账户未被业务数据或设置引用时删除。 */
    suspend fun delete(account: Account): DeleteResult = database.withTransaction {
        if (account.builtin) {
            return@withTransaction DeleteResult.Builtin
        }
        val references = referenceCount(account)
        if (references > 0) {
            return@withTransaction DeleteResult.Referenced(references)
        }
        if (accountDao.deleteIfNotBuiltin(account.id) > 0) {
            DeleteResult.Deleted
        } else {
            DeleteResult.NotFound
        }
    }

    /**
     * 计算账户余额 = 初始余额 − 支出 + 收入 + 转入 − 转出。
     */
    suspend fun balanceOf(account: Account): Long {
        val outflow = txDao.netOutflowOfAccount(account.id)
        val transferIn = txDao.transferInOfAccount(account.id)
        val transferOut = txDao.transferOutOfAccount(account.id)
        return account.initialCents - outflow + transferIn - transferOut
    }

    suspend fun balances(): List<AccountBalance> =
        listActive().map { AccountBalance(it, balanceOf(it)) }

    /** 全部账户净额合计（信用卡为负）。 */
    suspend fun totalBalance(): Long = balances().sumOf { it.cents }
}
