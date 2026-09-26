package com.jizhang.app.importbill

import com.jizhang.app.data.db.entity.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BillReaderTest {

    @Test
    fun `带BOM的UTF8被正确解码`() {
        val text = "交易时间,金额"
        val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val bytes = bom + text.toByteArray(Charsets.UTF_8)
        assertEquals(text, BillReader.decode(bytes))
    }

    @Test
    fun `GBK编码的中文被正确解码`() {
        val text = "交易时间,交易对方,金额\n2024-03-15,星巴克,33.00"
        val bytes = text.toByteArray(charset("GBK"))
        val decoded = BillReader.decode(bytes)
        assertEquals(text, decoded)
    }

    @Test
    fun `UTF8编码的中文被正确解码`() {
        val text = "交易时间,交易对方\n2024-03-15,星巴克"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, BillReader.decode(bytes))
    }

    @Test
    fun `纯ASCII内容两种编码都不出错`() {
        val text = "time,amount\n2024-03-15,33.00"
        val bytes = text.toByteArray(Charsets.UTF_8)
        assertEquals(text, BillReader.decode(bytes))
    }

    @Test
    fun `CSV文件直接按文本解码`() {
        val text = "交易时间,金额\n2024-03-15,33.00"
        val bytes = text.toByteArray(charset("GBK"))
        assertEquals(text, BillReader.read(bytes, "bill.csv"))
    }

    @Test
    fun `txt文件也能读取`() {
        val text = "交易时间,金额\n2024-03-15,33.00"
        val bytes = text.toByteArray(charset("GBK"))
        assertEquals(text, BillReader.read(bytes, "bill.txt"))
    }

    @Test
    fun `未知扩展名按内容判断-文本`() {
        val text = "交易时间,金额\n2024-03-15,33.00"
        val bytes = text.toByteArray(charset("GBK"))
        assertEquals(text, BillReader.read(bytes, "bill.dat"))
    }

    @Test
    fun `GBK中文解码后能被账单解析器识别`() {
        // 注意：半角 ¥ (U+00A5) 无法用 GBK 表示，真实微信账单里用的是
        // 全角 ￥ (U+FFE5) 或不带符号的纯数字，这里按真实情况写。
        val text = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2024-03-15 14:32:07,商户消费,星巴克,拿铁,支出,￥33.00,零钱,支付成功,4200001111111111,1620001111,/
        """.trimIndent()
        val bytes = text.toByteArray(charset("GBK"))
        val decoded = BillReader.read(bytes, "bill.csv")
        val result = WeChatBillParser.parse(decoded)
        assertEquals(1, result.rows.size)
        assertEquals(3300L, result.rows.first().cents)
        assertEquals("星巴克", result.rows.first().counterparty)
        assertEquals(TxKind.EXPENSE, result.rows.first().kind)
    }

    @Test
    fun `金额列是全角￥时也能解析`() {
        val text = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2024-03-15 14:32:07,商户消费,星巴克,拿铁,支出,￥33.00,零钱,支付成功,4200001111111111,1620001111,/
        """.trimIndent()
        val result = WeChatBillParser.parse(text)
        assertEquals(3300L, result.rows.first().cents)
    }

    @Test
    fun `空字节数组不会崩溃`() {
        val decoded = BillReader.decode(ByteArray(0))
        assertTrue(decoded.isEmpty())
    }
}
