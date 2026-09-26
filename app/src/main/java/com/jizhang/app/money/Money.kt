package com.jizhang.app.money

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 金额工具。内部一律以「分」为单位用 Long 存储，避免浮点误差。
 */
object Money {

    /**
     * 元 → 分。用于解析账单/通知里的字符串金额。
     *
     * 只保留数字、小数点和负号，其余字符（货币符号、千分位、单位、
     * 以及某些编码下 `¥` 被替换成的 `?`）一律丢弃，这样对真实账单里
     * 各种写法都成立。
     */
    fun parseYuanToCents(raw: String?): Long? {
        if (raw == null) return null
        val cleaned = raw.filter { it.isDigit() || it == '.' || it == '-' }.trim()
        if (cleaned.isEmpty()) return null
        if (!cleaned.matches(Regex("""-?\d+(\.\d+)?"""))) return null
        return try {
            BigDecimal(cleaned)
                .setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .toLong()
        } catch (_: NumberFormatException) {
            null
        }
    }

    /** 分 → 元字符串，保留两位小数。 */
    fun formatCents(cents: Long): String {
        val negative = cents < 0
        val abs = if (negative) -cents else cents
        val yuan = abs / 100
        val fen = abs % 100
        val body = "$yuan.${fen.toString().padStart(2, '0')}"
        return if (negative) "-$body" else body
    }

    /** 分 → 带货币符号的字符串。 */
    fun formatCentsWithSymbol(cents: Long): String = "¥" + formatCents(cents)

    /** 分 → 带正负号的字符串（支出显示 -，收入显示 +）。 */
    fun formatSigned(cents: Long, negative: Boolean): String {
        val prefix = if (negative) "-" else "+"
        val abs = if (cents < 0) -cents else cents
        return prefix + formatCents(abs)
    }

    /** 金额输入的键盘状态机：把按键序列规范成合法的两位小数输入。 */
    fun appendKey(current: String, key: String): String {
        return when (key) {
            "." -> if (current.contains(".")) current else if (current.isEmpty()) "0." else "$current."
            "del" -> if (current.isEmpty()) "" else current.dropLast(1)
            else -> {
                if (!key.matches(Regex("""\d"""))) return current
                val dotIndex = current.indexOf('.')
                if (dotIndex >= 0 && current.length - dotIndex > 2) return current
                // 去掉前导零：0 后面直接跟数字时替换掉
                if (current == "0") key else {
                    val result = current + key
                    // 限制整数部分长度，避免溢出
                    val intPart = result.substringBefore('.')
                    if (intPart.length > 9) current else result
                }
            }
        }
    }

    /** 键盘当前值 → 分。空或非法时返回 0。 */
    fun keyboardToCents(value: String): Long = parseYuanToCents(value) ?: 0L
}
