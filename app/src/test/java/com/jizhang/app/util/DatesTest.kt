package com.jizhang.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class DatesTest {

    @Test
    fun `解析标准账单时间格式`() {
        val dt = Dates.parseDateTime("2024-03-15 14:32:07")
        assertNotNull(dt)
        assertEquals(2024, dt!!.year)
        assertEquals(3, dt.monthValue)
        assertEquals(15, dt.dayOfMonth)
        assertEquals(14, dt.hour)
        assertEquals(32, dt.minute)
        assertEquals(7, dt.second)
    }

    @Test
    fun `解析斜杠与中文格式`() {
        assertNotNull(Dates.parseDateTime("2024/03/15 14:32"))
        assertNotNull(Dates.parseDateTime("2024年3月15日 14:32:07"))
        assertNotNull(Dates.parseDateTime("2024年3月15日"))
    }

    @Test
    fun `解析纯日期与紧凑格式`() {
        val a = Dates.parseDateTime("2024-03-15")
        assertEquals(0, a!!.hour)
        assertNotNull(Dates.parseDateTime("2024/03/15"))
        assertNotNull(Dates.parseDateTime("20240315"))
    }

    @Test
    fun `清理引号与空白`() {
        assertNotNull(Dates.parseDateTime("  \"2024-03-15 14:32:07\"  "))
    }

    @Test
    fun `非法输入返回null`() {
        assertNull(Dates.parseDateTime(""))
        assertNull(Dates.parseDateTime("   "))
        assertNull(Dates.parseDateTime("不是时间"))
        assertNull(Dates.parseDateTime(null))
    }

    @Test
    fun `时间戳往返转换保持到秒`() {
        val dt = Dates.parseDateTime("2024-03-15 14:32:07")!!
        val millis = Dates.toEpochMillis(dt)
        val back = Dates.fromEpochMillis(millis)
        assertEquals(dt.withNano(0), back.withNano(0))
    }
}
