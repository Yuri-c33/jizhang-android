package com.jizhang.app.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 资金账户。余额不落库，由「初始余额 + 流水累加」实时算出。
 */
@Entity(tableName = "account")
data class Account(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: AccountType,
    /** 初始余额（分），可为负（信用卡欠款） */
    val initialCents: Long = 0,
    val sortOrder: Int = 0,
    val builtin: Boolean = false,
    /** 是否在界面上隐藏 */
    val archived: Boolean = false,
)
