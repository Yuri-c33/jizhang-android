package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 「关键词 → 分类」记忆规则。
 * 用户在确认待入账条目时选择分类，系统据此记住，下次自动推荐。
 */
@Entity(
    tableName = "merchant_rule",
    indices = [Index(value = ["keyword"], unique = true)],
)
data class MerchantRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 关键词（交易对方或商品文本片段） */
    val keyword: String,
    val categoryId: Long,
    /** 命中次数，越多优先级越高 */
    val hits: Int = 1,
    val builtin: Boolean = false,
    val updatedAt: Long = System.currentTimeMillis(),
)
