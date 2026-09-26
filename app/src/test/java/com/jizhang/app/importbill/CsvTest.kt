package com.jizhang.app.importbill

import org.junit.Assert.assertEquals
import org.junit.Test

class CsvTest {

    @Test
    fun `解析简单行`() {
        val fields = Csv.parseLine("a,b,c")
        assertEquals(listOf("a", "b", "c"), fields)
    }

    @Test
    fun `引号内的逗号不分隔`() {
        val line = "2024-01-01,星巴克,\"拿铁,大杯\",支出"
        val fields = Csv.parseLine(line)
        assertEquals(listOf("2024-01-01", "星巴克", "拿铁,大杯", "支出"), fields)
    }

    @Test
    fun `连续引号转义为一个引号`() {
        val line = "a,\"say \"\"hi\"\"\",c"
        val fields = Csv.parseLine(line)
        assertEquals(listOf("a", "say \"hi\"", "c"), fields)
    }

    @Test
    fun `空字段被保留`() {
        val fields = Csv.parseLine("a,,c")
        assertEquals(listOf("a", "", "c"), fields)
    }

    @Test
    fun `尾部空字段被保留`() {
        val fields = Csv.parseLine("a,b,")
        assertEquals(listOf("a", "b", ""), fields)
    }

    @Test
    fun `解析多行文本并跳过空行`() {
        val rows = Csv.parse("a,b\n\nc,d\n\n")
        assertEquals(2, rows.size)
        assertEquals(listOf("a", "b"), rows[0])
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `剥离回车符`() {
        val rows = Csv.parse("a,b\r\nc,d\r\n")
        assertEquals(listOf("c", "d"), rows[1])
    }

    @Test
    fun `转义输出`() {
        assertEquals("abc", Csv.escapeField("abc"))
        assertEquals("\"a,b\"", Csv.escapeField("a,b"))
        assertEquals("\"say \"\"hi\"\"\"", Csv.escapeField("say \"hi\""))
    }

    @Test
    fun `含中文与货币符号的行`() {
        val line = "2024-03-15 14:32:07,商户消费,星巴克,拿铁,支出,¥33.00,零钱,支付成功,4200001,1620001,/"
        val fields = Csv.parseLine(line)
        assertEquals(11, fields.size)
        assertEquals("¥33.00", fields[5])
        assertEquals("/", fields[10])
    }

    @Test
    fun `引号包裹的整行视为单字段`() {
        val fields = Csv.parseLine("\"a,b,c\"")
        assertEquals(listOf("a,b,c"), fields)
    }
}
