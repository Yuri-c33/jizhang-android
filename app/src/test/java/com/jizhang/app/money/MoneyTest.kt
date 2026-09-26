package com.jizhang.app.money

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun `解析带货币符号的金额`() {
        assertEquals(1500L, Money.parseYuanToCents("¥15.00"))
        assertEquals(1500L, Money.parseYuanToCents("￥15.00"))
        assertEquals(1500L, Money.parseYuanToCents("15.00"))
        assertEquals(1500L, Money.parseYuanToCents("15.00元"))
    }

    @Test
    fun `解析带千分位的金额`() {
        assertEquals(123456L, Money.parseYuanToCents("1,234.56"))
        assertEquals(123456L, Money.parseYuanToCents("1，234.56"))
        assertEquals(100000000L, Money.parseYuanToCents("1,000,000.00"))
    }

    @Test
    fun `解析整数与负数金额`() {
        assertEquals(1500L, Money.parseYuanToCents("15"))
        assertEquals(-1500L, Money.parseYuanToCents("-15.00"))
        assertEquals(0L, Money.parseYuanToCents("0"))
        assertEquals(0L, Money.parseYuanToCents("0.00"))
    }

    @Test
    fun `四舍五入到分`() {
        assertEquals(1501L, Money.parseYuanToCents("15.005"))
        assertEquals(1500L, Money.parseYuanToCents("15.004"))
        assertEquals(1L, Money.parseYuanToCents("0.005"))
    }

    @Test
    fun `非法输入返回null`() {
        assertNull(Money.parseYuanToCents(""))
        assertNull(Money.parseYuanToCents("abc"))
        assertNull(Money.parseYuanToCents("¥"))
        assertNull(Money.parseYuanToCents("15.00.00"))
        assertNull(Money.parseYuanToCents(null))
    }

    @Test
    fun `格式化分为元字符串`() {
        assertEquals("15.00", Money.formatCents(1500))
        assertEquals("0.00", Money.formatCents(0))
        assertEquals("0.05", Money.formatCents(5))
        assertEquals("0.50", Money.formatCents(50))
        assertEquals("1234.56", Money.formatCents(123456))
        assertEquals("-15.00", Money.formatCents(-1500))
    }

    @Test
    fun `格式化带符号金额`() {
        assertEquals("-15.00", Money.formatSigned(1500, negative = true))
        assertEquals("+15.00", Money.formatSigned(1500, negative = false))
    }

    @Test
    fun `键盘输入基本序列`() {
        var v = ""
        v = Money.appendKey(v, "1")
        v = Money.appendKey(v, "2")
        v = Money.appendKey(v, ".")
        v = Money.appendKey(v, "3")
        assertEquals("12.3", v)
    }

    @Test
    fun `键盘不允许两个小数点`() {
        var v = "12.3"
        v = Money.appendKey(v, ".")
        assertEquals("12.3", v)
    }

    @Test
    fun `键盘小数点开头补零`() {
        val v = Money.appendKey("", ".")
        assertEquals("0.", v)
    }

    @Test
    fun `键盘最多两位小数`() {
        var v = "12.34"
        v = Money.appendKey(v, "5")
        assertEquals("12.34", v)
    }

    @Test
    fun `键盘前导零被替换`() {
        var v = "0"
        v = Money.appendKey(v, "5")
        assertEquals("5", v)
    }

    @Test
    fun `键盘删除`() {
        var v = "12.3"
        v = Money.appendKey(v, "del")
        assertEquals("12.", v)
        v = Money.appendKey(v, "del")
        assertEquals("12", v)
        assertEquals("", Money.appendKey("", "del"))
    }

    @Test
    fun `键盘限制整数位长度`() {
        var v = "123456789"
        v = Money.appendKey(v, "0")
        assertEquals("123456789", v)
    }

    @Test
    fun `键盘值转换为分`() {
        assertEquals(1234L, Money.keyboardToCents("12.34"))
        assertEquals(0L, Money.keyboardToCents(""))
        assertEquals(0L, Money.keyboardToCents("."))
    }
}
