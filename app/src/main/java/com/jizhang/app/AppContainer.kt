package com.jizhang.app

import android.content.Context
import com.jizhang.app.data.db.AppDatabase
import com.jizhang.app.data.prefs.SettingsStore
import com.jizhang.app.data.repo.AccountRepository
import com.jizhang.app.data.repo.CategoryRepository
import com.jizhang.app.data.repo.FactoryResetRepository
import com.jizhang.app.data.repo.PendingRepository
import com.jizhang.app.data.repo.TransactionRepository

/**
 * 手写依赖容器。规模不大的项目用不上 DI 框架。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    val database: AppDatabase by lazy { AppDatabase.get(appContext) }

    val settings: SettingsStore by lazy { SettingsStore(appContext) }

    val transactionRepo: TransactionRepository by lazy {
        TransactionRepository(database.transactionDao())
    }

    val categoryRepo: CategoryRepository by lazy {
        CategoryRepository(
            database = database,
            categoryDao = database.categoryDao(),
            ruleDao = database.merchantRuleDao(),
            txDao = database.transactionDao(),
            pendingDao = database.pendingDao(),
        )
    }

    val accountRepo: AccountRepository by lazy {
        AccountRepository(
            database = database,
            accountDao = database.accountDao(),
            txDao = database.transactionDao(),
            pendingDao = database.pendingDao(),
            settings = settings,
        )
    }

    val pendingRepo: PendingRepository by lazy {
        PendingRepository(
            database = database,
            pendingDao = database.pendingDao(),
            logDao = database.notificationLogDao(),
            txRepo = transactionRepo,
        )
    }

    val factoryResetRepo: FactoryResetRepository by lazy {
        FactoryResetRepository(
            database = database,
            txDao = database.transactionDao(),
            pendingDao = database.pendingDao(),
            logDao = database.notificationLogDao(),
            ruleDao = database.merchantRuleDao(),
            categoryDao = database.categoryDao(),
            accountDao = database.accountDao(),
            settings = settings,
        )
    }
}
