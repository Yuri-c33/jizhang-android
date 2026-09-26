package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 通知识别出的待确认条目。
 * 用户确认后才写入 [Transaction]，或标记为忽略。
 */
@Entity(
    tableName = "pending",
    indices = [
        Index(value = ["status"]),
        Index(value = ["fingerprint"], unique = true),
    ],
)
data class PendingItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 通知标题（通常是「微信支付」或对方昵称） */
    val rawTitle: String? = null,

    /** 通知正文全文（已把 text/bigText/textLines 拼接） */
    val rawText: String? = null,

    /** 通知时间（epoch millis） */
    val postedAt: Long = System.currentTimeMillis(),

    /** 内容指纹，用于防止同一条通知被反复入队 */
    val fingerprint: String,

    /** 解析出的方向 */
    val kind: TxKind? = null,

    /** 解析出的金额（分） */
    val cents: Long? = null,

    /** 解析出的交易对方 */
    val counterparty: String? = null,

    /** 解析出的商品说明 */
    val item: String? = null,

    /** 命中的规则名，便于诊断 */
    val matchedRule: String? = null,

    val confidence: Confidence = Confidence.LOW,

    /** 建议分类 */
    val suggestedCategoryId: Long? = null,

    /** 建议账户 */
    val suggestedAccountId: Long? = null,

    /** 建议转入账户（仅转账待确认项有意义） */
    val suggestedToAccountId: Long? = null,

    val status: PendingStatus = PendingStatus.PENDING,

    /** 确认后生成的账目 id */
    val transactionId: Long? = null,
)
