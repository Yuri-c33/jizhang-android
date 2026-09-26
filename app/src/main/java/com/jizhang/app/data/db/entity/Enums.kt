package com.jizhang.app.data.db.entity

/** 交易方向。 */
enum class TxKind {
    /** 支出 */
    EXPENSE,

    /** 收入 */
    INCOME,

    /** 账户间转账（不计入收支统计） */
    TRANSFER,

    /** 中性/不计收支（如退款冲抵、提现） */
    NEUTRAL,
    ;

    val isStatRelevant: Boolean get() = this == EXPENSE || this == INCOME

    val label: String
        get() = when (this) {
            EXPENSE -> "支出"
            INCOME -> "收入"
            TRANSFER -> "转账"
            NEUTRAL -> "不计收支"
        }
}

/** 记账来源。 */
enum class TxSource {
    MANUAL,
    NOTIFICATION,
    IMPORT,
    ;

    val label: String
        get() = when (this) {
            MANUAL -> "手动"
            NOTIFICATION -> "自动"
            IMPORT -> "导入"
        }
}

/** 账户类型。 */
enum class AccountType {
    WECHAT,
    WECHAT_LICAITONG,
    ALIPAY,
    BANK_CARD,
    CREDIT_CARD,
    CASH,
    OTHER,
    ;

    val label: String
        get() = when (this) {
            WECHAT -> "微信零钱"
            WECHAT_LICAITONG -> "零钱通"
            ALIPAY -> "支付宝"
            BANK_CARD -> "银行卡"
            CREDIT_CARD -> "信用卡"
            CASH -> "现金"
            OTHER -> "其他"
        }

    /** 是否为负债类账户（信用卡）：余额显示为欠款。 */
    val isLiability: Boolean get() = this == CREDIT_CARD
}

/** 识别结果的置信度。 */
enum class Confidence {
    HIGH,
    MEDIUM,
    LOW,
    ;

    val label: String
        get() = when (this) {
            HIGH -> "高"
            MEDIUM -> "中"
            LOW -> "低"
        }
}

/** 待确认条目的处理状态。 */
enum class PendingStatus {
    PENDING,
    CONFIRMED,
    IGNORED,
}
