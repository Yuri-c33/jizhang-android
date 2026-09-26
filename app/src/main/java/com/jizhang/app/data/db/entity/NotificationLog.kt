package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 最近捕获的微信原始通知，供「诊断」页查看。
 * 识别规则对不上时，用户可以在这里复制原文反馈。
 */
@Entity(tableName = "notif_log")
data class NotificationLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val title: String?,
    val text: String?,
    val capturedAt: Long = System.currentTimeMillis(),
    /** 是否被解析器识别为金额类通知 */
    val recognized: Boolean = false,
    /** 命中的规则名 */
    val matchedRule: String? = null,
)
