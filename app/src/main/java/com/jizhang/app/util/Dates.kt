package com.jizhang.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 日期时间工具。全部基于 java.time（minSdk 26 起可直接使用）。 */
object Dates {

    val ZONE: ZoneId = ZoneId.systemDefault()

    private val DATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        // 中文格式月份/日期可能是个位数，需要单字符模式
        "yyyy年M月d日 HH:mm:ss",
        "yyyy年M月d日 HH:mm",
        "yyyy年M月d日H时m分s秒",
        "yyyy-MM-dd'T'HH:mm:ss",
    )

    private val DATE_ONLY_PATTERNS = listOf(
        "yyyy-MM-dd",
        "yyyy/MM/dd",
        "yyyy/M/d",
        "yyyy年M月d日",
        "yyyyMMdd",
    )

    private val dateDisplay: DateTimeFormatter = DateTimeFormatter.ofPattern("M月d日")
    private val dateFull: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月d日")
    private val timeDisplay: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val monthDisplay: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy年M月")

    /** 解析账单里的时间字符串，容忍多种格式。 */
    fun parseDateTime(raw: String?): LocalDateTime? {
        if (raw == null) return null
        val text = raw.trim().trim('"')
        if (text.isEmpty()) return null
        for (pattern in DATE_TIME_PATTERNS) {
            try {
                return LocalDateTime.parse(text, DateTimeFormatter.ofPattern(pattern))
            } catch (_: Exception) {
                // 尝试下一个
            }
        }
        for (pattern in DATE_ONLY_PATTERNS) {
            try {
                return LocalDate.parse(text, DateTimeFormatter.ofPattern(pattern)).atStartOfDay()
            } catch (_: Exception) {
                // 尝试下一个
            }
        }
        return null
    }

    fun toEpochMillis(dateTime: LocalDateTime): Long = dateTime.atZone(ZONE).toInstant().toEpochMilli()

    fun fromEpochMillis(millis: Long): LocalDateTime =
        Instant.ofEpochMilli(millis).atZone(ZONE).toLocalDateTime()

    fun today(): LocalDate = LocalDate.now(ZONE)

    fun currentYearMonth(): YearMonth = YearMonth.now(ZONE)

    fun yearMonthOf(millis: Long): YearMonth = YearMonth.from(fromEpochMillis(millis))

    fun formatDate(millis: Long): String = fromEpochMillis(millis).format(dateDisplay)

    fun formatFullDate(millis: Long): String = fromEpochMillis(millis).format(dateFull)

    fun formatTime(millis: Long): String = fromEpochMillis(millis).format(timeDisplay)

    fun formatMonth(yearMonth: YearMonth): String = yearMonth.atDay(1).format(monthDisplay)

    /** 明细列表的分节标题：今天 / 昨天 / M月d日。 */
    fun sectionTitle(date: LocalDate): String {
        val today = today()
        return when (date) {
            today -> "今天"
            today.minusDays(1) -> "昨天"
            else -> if (date.year == today.year) {
                date.format(dateDisplay)
            } else {
                date.format(dateFull)
            }
        }
    }

    fun sectionTitleOfMillis(millis: Long): String = sectionTitle(fromEpochMillis(millis).toLocalDate())
}
