package com.jizhang.app.data.db

import androidx.room.TypeConverter
import com.jizhang.app.data.db.entity.AccountType
import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.PendingStatus
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource

/**
 * 枚举一律以名称字符串存库，保证以后新增枚举值时旧数据仍可读。
 */
class Converters {

    @TypeConverter
    fun txKindToString(value: TxKind?): String? = value?.name

    @TypeConverter
    fun stringToTxKind(value: String?): TxKind? =
        value?.let { runCatching { TxKind.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun txSourceToString(value: TxSource?): String? = value?.name

    @TypeConverter
    fun stringToTxSource(value: String?): TxSource? =
        value?.let { runCatching { TxSource.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun accountTypeToString(value: AccountType?): String? = value?.name

    @TypeConverter
    fun stringToAccountType(value: String?): AccountType? =
        value?.let { runCatching { AccountType.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun confidenceToString(value: Confidence?): String? = value?.name

    @TypeConverter
    fun stringToConfidence(value: String?): Confidence? =
        value?.let { runCatching { Confidence.valueOf(it) }.getOrNull() }

    @TypeConverter
    fun pendingStatusToString(value: PendingStatus?): String? = value?.name

    @TypeConverter
    fun stringToPendingStatus(value: String?): PendingStatus? =
        value?.let { runCatching { PendingStatus.valueOf(it) }.getOrNull() }
}
