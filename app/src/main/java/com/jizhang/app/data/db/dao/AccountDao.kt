package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.AccountType
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(account: Account): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Account>): List<Long>

    @Update
    suspend fun update(account: Account)

    @Delete
    suspend fun delete(account: Account)

    @Query("DELETE FROM account WHERE id = :id AND builtin = 0")
    suspend fun deleteIfNotBuiltin(id: Long): Int

    @Query("SELECT * FROM account WHERE archived = 0 ORDER BY sortOrder, id")
    fun observeActive(): Flow<List<Account>>

    @Query("SELECT * FROM account ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<Account>>

    @Query("SELECT * FROM account WHERE archived = 0 ORDER BY sortOrder, id")
    suspend fun listActive(): List<Account>

    @Query("SELECT * FROM account ORDER BY sortOrder, id")
    suspend fun listAll(): List<Account>

    @Query("SELECT * FROM account WHERE id = :id")
    suspend fun findById(id: Long): Account?

    @Query("SELECT * FROM account WHERE type = :type AND archived = 0 ORDER BY sortOrder, id LIMIT 1")
    suspend fun findFirstByType(type: AccountType): Account?

    @Query("SELECT COUNT(*) FROM account")
    suspend fun count(): Int

    @Query("DELETE FROM account")
    suspend fun clearAll()
}
