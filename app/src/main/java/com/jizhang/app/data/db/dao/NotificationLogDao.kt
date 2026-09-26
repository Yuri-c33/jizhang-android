package com.jizhang.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.jizhang.app.data.db.entity.NotificationLog
import kotlinx.coroutines.flow.Flow

@Dao
interface NotificationLogDao {

    @Insert
    suspend fun insert(log: NotificationLog): Long

    @Query("SELECT * FROM notif_log ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 200): Flow<List<NotificationLog>>

    @Query("SELECT * FROM notif_log ORDER BY capturedAt DESC, id DESC LIMIT :limit")
    suspend fun listRecent(limit: Int = 200): List<NotificationLog>

    /** 只保留最近 N 条，避免无限增长。 */
    @Query(
        """
        DELETE FROM notif_log WHERE id NOT IN (
            SELECT id FROM notif_log ORDER BY capturedAt DESC, id DESC LIMIT :keep
        )
        """,
    )
    suspend fun trim(keep: Int = 200)

    @Query("DELETE FROM notif_log")
    suspend fun clear()
}
