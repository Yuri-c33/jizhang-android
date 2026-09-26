package com.jizhang.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.jizhang.app.data.SeedData
import com.jizhang.app.data.db.dao.AccountDao
import com.jizhang.app.data.db.dao.CategoryDao
import com.jizhang.app.data.db.dao.MerchantRuleDao
import com.jizhang.app.data.db.dao.NotificationLogDao
import com.jizhang.app.data.db.dao.PendingDao
import com.jizhang.app.data.db.dao.TransactionDao
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.MerchantRule
import com.jizhang.app.data.db.entity.NotificationLog
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind

@Database(
    entities = [
        Transaction::class,
        Category::class,
        Account::class,
        PendingItem::class,
        MerchantRule::class,
        NotificationLog::class,
    ],
    version = 4,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun transactionDao(): TransactionDao

    abstract fun categoryDao(): CategoryDao

    abstract fun accountDao(): AccountDao

    abstract fun pendingDao(): PendingDao

    abstract fun merchantRuleDao(): MerchantRuleDao

    abstract fun notificationLogDao(): NotificationLogDao

    companion object {
        const val DB_NAME = "jizhang.db"

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE pending ADD COLUMN suggestedToAccountId INTEGER")
            }
        }

        /**
         * 补齐内置分类与关键词规则。
         *
         * 用 INSERT OR IGNORE 逐条补种，只新增缺失项，(name, kind) 唯一索引
         * 保证不会覆盖或重复用户已有的数据。可重复执行，因此各次迁移共用。
         */
        private fun seedMissingBuiltins(db: SupportSQLiteDatabase) {
            SeedData.categories().forEach { category ->
                db.execSQL(
                    "INSERT OR IGNORE INTO category (name, emoji, kind, sortOrder, builtin) " +
                        "VALUES (?, ?, ?, ?, ?)",
                    arrayOf<Any>(
                        category.name,
                        category.emoji,
                        category.kind.name,
                        category.sortOrder,
                        if (category.builtin) 1 else 0,
                    ),
                )
                // 已有内置分类的排列顺序同样按最新种子顺序归一，用户自建分类不受影响。
                if (category.builtin) {
                    db.execSQL(
                        "UPDATE category SET sortOrder = ? WHERE name = ? AND kind = ? AND builtin = 1",
                        arrayOf<Any>(category.sortOrder, category.name, category.kind.name),
                    )
                }
            }

            SeedData.keywordRules.forEach { (keyword, categoryName, kind) ->
                val cursor = db.query(
                    "SELECT id FROM category WHERE name = ? AND kind = ? LIMIT 1",
                    arrayOf<Any>(categoryName, kind.name),
                )
                cursor.use {
                    if (it.moveToFirst()) {
                        val categoryId = it.getLong(0)
                        db.execSQL(
                            "INSERT OR IGNORE INTO merchant_rule " +
                                "(keyword, categoryId, hits, builtin, updatedAt) " +
                                "VALUES (?, ?, ?, ?, ?)",
                            arrayOf<Any>(keyword, categoryId, 1, 1, System.currentTimeMillis()),
                        )
                    }
                }
            }
        }

        /** 2 → 3：补齐内置分类与关键词规则。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) = seedMissingBuiltins(db)
        }

        /** 3 → 4：补种新增的内置分类与关键词规则，保留全部用户数据。 */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) = seedMissingBuiltins(db)
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        private fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .addCallback(SeedCallback)
                .build()

        fun reset(context: Context) {
            synchronized(this) {
                instance?.close()
                context.applicationContext.deleteDatabase(DB_NAME)
                instance = null
            }
        }

        /**
         * 首次创建数据库时写入默认分类/账户/规则。
         * 用 onCreate 回调而非仓库层判断，保证只执行一次且是同步完成的。
         */
        private object SeedCallback : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)

                SeedData.categories().forEach { category ->
                    db.execSQL(
                        "INSERT INTO category (name, emoji, kind, sortOrder, builtin) VALUES (?, ?, ?, ?, ?)",
                        arrayOf<Any>(
                            category.name,
                            category.emoji,
                            category.kind.name,
                            category.sortOrder,
                            if (category.builtin) 1 else 0,
                        ),
                    )
                }

                SeedData.accounts().forEach { account ->
                    db.execSQL(
                        "INSERT INTO account (name, type, initialCents, sortOrder, builtin, archived) VALUES (?, ?, ?, ?, ?, ?)",
                        arrayOf<Any>(
                            account.name,
                            account.type.name,
                            account.initialCents,
                            account.sortOrder,
                            if (account.builtin) 1 else 0,
                            0,
                        ),
                    )
                }

                // 规则需要分类 id，从刚写入的分类里查
                SeedData.keywordRules.forEach { (keyword, categoryName, kind) ->
                    val cursor = db.query(
                        "SELECT id FROM category WHERE name = ? AND kind = ? LIMIT 1",
                        arrayOf<Any>(categoryName, kind.name),
                    )
                    cursor.use {
                        if (it.moveToFirst()) {
                            val categoryId = it.getLong(0)
                            db.execSQL(
                                "INSERT OR IGNORE INTO merchant_rule (keyword, categoryId, hits, builtin, updatedAt) VALUES (?, ?, ?, ?, ?)",
                                arrayOf<Any>(keyword, categoryId, 1, 1, System.currentTimeMillis()),
                            )
                        }
                    }
                }
            }
        }
    }
}
