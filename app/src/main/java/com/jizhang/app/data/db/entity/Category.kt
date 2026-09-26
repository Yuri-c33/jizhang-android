package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 分类。图标用 emoji 字符串存储，省去大量矢量图资源。
 */
@Entity(
    tableName = "category",
    indices = [Index(value = ["name", "kind"], unique = true)],
)
data class Category(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val emoji: String,
    /** 所属方向：支出分类或收入分类 */
    val kind: TxKind,
    val sortOrder: Int = 0,
    /** 内置分类不可删除 */
    val builtin: Boolean = false,
)
