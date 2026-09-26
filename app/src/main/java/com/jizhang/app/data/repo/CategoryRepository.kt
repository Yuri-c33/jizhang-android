package com.jizhang.app.data.repo

import androidx.room.withTransaction
import com.jizhang.app.data.db.AppDatabase
import com.jizhang.app.data.db.dao.CategoryDao
import com.jizhang.app.data.db.dao.PendingDao
import com.jizhang.app.data.db.dao.TransactionDao
import com.jizhang.app.data.db.dao.MerchantRuleDao
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.MerchantRule
import com.jizhang.app.data.db.entity.TxKind
import kotlinx.coroutines.flow.Flow

class CategoryRepository(
    private val database: AppDatabase,
    private val categoryDao: CategoryDao,
    private val ruleDao: MerchantRuleDao,
    private val txDao: TransactionDao,
    private val pendingDao: PendingDao,
) {

    fun observeAll(): Flow<List<Category>> = categoryDao.observeAll()

    fun observeByKind(kind: TxKind): Flow<List<Category>> = categoryDao.observeByKind(kind)

    suspend fun listByKind(kind: TxKind): List<Category> = categoryDao.listByKind(kind)

    suspend fun listAll(): List<Category> = categoryDao.listAll()

    suspend fun findById(id: Long): Category? = categoryDao.findById(id)

    suspend fun findByName(name: String, kind: TxKind): Category? = categoryDao.findByName(name, kind)

    suspend fun add(category: Category): Long = categoryDao.insert(category)

    suspend fun update(category: Category) = categoryDao.update(category)

    suspend fun referenceCount(category: Category): Int =
        txDao.countByCategory(category.id) +
            ruleDao.countByCategory(category.id) +
            pendingDao.countBySuggestedCategory(category.id)

    /** 仅在分类未被任何业务数据引用时删除。 */
    suspend fun delete(category: Category): DeleteResult = database.withTransaction {
        if (category.builtin) {
            return@withTransaction DeleteResult.Builtin
        }
        val references = referenceCount(category)
        if (references > 0) {
            return@withTransaction DeleteResult.Referenced(references)
        }
        if (categoryDao.deleteIfNotBuiltin(category.id) > 0) {
            DeleteResult.Deleted
        } else {
            DeleteResult.NotFound
        }
    }

    suspend fun listRules(): List<MerchantRule> = ruleDao.listAll()

    /**
     * 根据文本推荐分类：命中规则里关键词最长的优先（更具体的规则胜出）。
     */
    suspend fun suggestCategoryId(text: String?, kind: TxKind): Long? {
        if (text.isNullOrBlank()) return null
        val rules = ruleDao.listAll()
        val matched = rules
            .filter { it.keyword.isNotEmpty() && text.contains(it.keyword, ignoreCase = true) }
            .maxByOrNull { it.keyword.length }
        return matched?.categoryId?.takeIf { id -> categoryDao.findById(id) != null }
    }

    /** 用户确认分类后记住这条对应关系。 */
    suspend fun learnKeyword(keyword: String?, categoryId: Long) {
        val key = keyword?.trim()?.takeIf { it.length >= 2 } ?: return
        val existing = ruleDao.findByKeyword(key)
        if (existing != null) {
            ruleDao.reinforce(key, categoryId)
        } else {
            ruleDao.insert(MerchantRule(keyword = key, categoryId = categoryId, hits = 1, builtin = false))
        }
    }
}

sealed interface DeleteResult {
    data object Deleted : DeleteResult

    data object Builtin : DeleteResult

    data object NotFound : DeleteResult

    data class Referenced(val count: Int) : DeleteResult
}
