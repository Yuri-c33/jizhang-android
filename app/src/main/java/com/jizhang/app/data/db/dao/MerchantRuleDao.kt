package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.jizhang.app.data.db.entity.MerchantRule

@Dao
interface MerchantRuleDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(rule: MerchantRule): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<MerchantRule>): List<Long>

    @Query("SELECT * FROM merchant_rule ORDER BY hits DESC, updatedAt DESC")
    suspend fun listAll(): List<MerchantRule>

    @Query("SELECT * FROM merchant_rule WHERE keyword = :keyword LIMIT 1")
    suspend fun findByKeyword(keyword: String): MerchantRule?

    @Query("UPDATE merchant_rule SET hits = hits + 1, categoryId = :categoryId, updatedAt = :now WHERE keyword = :keyword")
    suspend fun reinforce(keyword: String, categoryId: Long, now: Long = System.currentTimeMillis())

    @Query("DELETE FROM merchant_rule WHERE builtin = 0")
    suspend fun clearLearned()

    @Query("SELECT COUNT(*) FROM merchant_rule")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM merchant_rule WHERE categoryId = :categoryId")
    suspend fun countByCategory(categoryId: Long): Int

    @Query("DELETE FROM merchant_rule")
    suspend fun clearAll()
}
