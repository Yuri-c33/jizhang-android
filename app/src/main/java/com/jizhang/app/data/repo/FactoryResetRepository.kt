package com.jizhang.app.data.repo

import androidx.room.withTransaction
import com.jizhang.app.data.SeedData
import com.jizhang.app.data.db.AppDatabase
import com.jizhang.app.data.db.dao.AccountDao
import com.jizhang.app.data.db.dao.CategoryDao
import com.jizhang.app.data.db.dao.MerchantRuleDao
import com.jizhang.app.data.db.dao.NotificationLogDao
import com.jizhang.app.data.db.dao.PendingDao
import com.jizhang.app.data.db.dao.TransactionDao
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.prefs.SettingsStore

/**
 * 原地恢复出厂数据。
 *
 * 所有业务表和偏好在一个事务中重置，随后写回内置分类、账户与规则；
 * 不重建数据库实例，已有 Flow 会继续观察同一组 DAO。
 */
class FactoryResetRepository(
    private val database: AppDatabase,
    private val txDao: TransactionDao,
    private val pendingDao: PendingDao,
    private val logDao: NotificationLogDao,
    private val ruleDao: MerchantRuleDao,
    private val categoryDao: CategoryDao,
    private val accountDao: AccountDao,
    private val settings: SettingsStore,
) {

    suspend fun reset() {
        database.withTransaction {
            pendingDao.clearAll()
            logDao.clear()
            txDao.clear()
            ruleDao.clearAll()
            categoryDao.clearAll()
            accountDao.clearAll()

            categoryDao.insertAll(SeedData.categories())
            val categoryIds = categoryDao.listAll().associate { "${it.kind}:${it.name}" to it.id }
            accountDao.insertAll(SeedData.accounts())

            val rules = SeedData.merchantRules { name, kind: TxKind ->
                categoryIds["$kind:$name"]
            }
            ruleDao.insertAll(rules)
        }

        // 偏好不是 Room 事务的一部分，放在数据写入成功之后提交。
        settings.resetForFactory()
    }
}
