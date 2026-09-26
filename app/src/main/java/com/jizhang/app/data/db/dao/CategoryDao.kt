package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.TxKind
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(category: Category): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(list: List<Category>): List<Long>

    @Update
    suspend fun update(category: Category)

    @Delete
    suspend fun delete(category: Category)

    @Query("DELETE FROM category WHERE id = :id AND builtin = 0")
    suspend fun deleteIfNotBuiltin(id: Long): Int

    @Query("SELECT * FROM category ORDER BY kind, sortOrder, id")
    fun observeAll(): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE kind = :kind ORDER BY sortOrder, id")
    fun observeByKind(kind: TxKind): Flow<List<Category>>

    @Query("SELECT * FROM category WHERE kind = :kind ORDER BY sortOrder, id")
    suspend fun listByKind(kind: TxKind): List<Category>

    @Query("SELECT * FROM category ORDER BY kind, sortOrder, id")
    suspend fun listAll(): List<Category>

    @Query("SELECT * FROM category WHERE id = :id")
    suspend fun findById(id: Long): Category?

    @Query("SELECT * FROM category WHERE name = :name AND kind = :kind LIMIT 1")
    suspend fun findByName(name: String, kind: TxKind): Category?

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    @Query("DELETE FROM category")
    suspend fun clearAll()
}
