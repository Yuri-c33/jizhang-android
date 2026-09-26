package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条账目记录。金额一律用「分」存 Long。
 */
@Entity(
    tableName = "tx",
    indices = [
        Index(value = ["occurredAt"]),
        Index(value = ["source", "sourceRef"], unique = true),
        Index(value = ["categoryId"]),
        Index(value = ["accountId"]),
    ],
)
data class Transaction(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,

    /** 方向：支出/收入/转账/中性 */
    val kind: TxKind,

    /** 金额（分），始终为正数，方向由 kind 决定 */
    val cents: Long,

    /** 分类 id，转账/中性时可为空 */
    val categoryId: Long? = null,

    /** 资金账户 id */
    val accountId: Long? = null,

    /** 转账的目标账户 id（仅 kind == TRANSFER 时有意义） */
    val toAccountId: Long? = null,

    /** 发生时间（epoch millis） */
    val occurredAt: Long,

    /** 交易对方，如「星巴克」「张三」 */
    val counterparty: String? = null,

    /** 商品说明 */
    val item: String? = null,

    /** 用户备注 */
    val note: String? = null,

    val source: TxSource = TxSource.MANUAL,

    /**
     * 来源侧的唯一标识，用于去重。
     * 导入账单时用「微信交易单号」；通知记账时用生成的内容指纹。
     */
    val sourceRef: String? = null,

    /** 是否排除在统计之外（默认跟随 kind，中性交易永不参与统计） */
    val excludedFromStats: Boolean = false,

    /** 该笔是否为自动识别产生且已经过用户确认 */
    val confirmed: Boolean = true,

    val createdAt: Long = System.currentTimeMillis(),
)
