package com.jizhang.app.importbill

import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatBillParserTest {

    @Test
    fun `解析完整账单得到8条记录`() {
        val result = WeChatBillParser.parse(WeChatBillSample.FULL)
        assertEquals(8, result.rows.size)
        assertEquals(0, result.skipped)
        assertNotNull(result.mapping)
    }

    @Test
    fun `自动识别表头各列位置`() {
        val result = WeChatBillParser.parse(WeChatBillSample.FULL)
        val m = result.mapping!!
        assertEquals(0, m.timeIndex)
        assertEquals(1, m.tradeTypeIndex)
        assertEquals(2, m.counterpartyIndex)
        assertEquals(3, m.itemIndex)
        assertEquals(4, m.kindIndex)
        assertEquals(5, m.amountIndex)
        assertEquals(6, m.payMethodIndex)
        assertEquals(7, m.statusIndex)
        assertEquals(8, m.tradeNoIndex)
        assertEquals(9, m.merchantNoIndex)
    }

    @Test
    fun `跳过开头的元信息行`() {
        val result = WeChatBillParser.parse(WeChatBillSample.FULL)
        // 元信息里的「共 8 笔记录」不应变成账目
        assertTrue(result.rows.none { it.cents == 0L })
        assertEquals(8, result.rows.size)
    }

    @Test
    fun `第一条记录的字段正确`() {
        val row = WeChatBillParser.parse(WeChatBillSample.FULL).rows.first()
        assertEquals(TxKind.EXPENSE, row.kind)
        assertEquals(3300L, row.cents)
        assertEquals("星巴克", row.counterparty)
        assertEquals("拿铁大杯", row.item)
        assertEquals("零钱", row.payMethod)
        assertEquals("4200001111111111", row.tradeNo)
        assertEquals("商户消费", row.tradeType)

        val dt = Dates.fromEpochMillis(row.occurredAt)
        assertEquals(2024, dt.year)
        assertEquals(3, dt.monthValue)
        assertEquals(15, dt.dayOfMonth)
        assertEquals(14, dt.hour)
        assertEquals(32, dt.minute)
    }

    @Test
    fun `带逗号的引号字段被正确解析`() {
        val row = WeChatBillParser.parse(WeChatBillSample.FULL).rows[1]
        assertEquals("肯德基, 中关村店", row.counterparty)
        assertEquals(4550L, row.cents)
    }

    @Test
    fun `收入支出方向判定正确`() {
        val rows = WeChatBillParser.parse(WeChatBillSample.FULL).rows
        assertEquals(TxKind.EXPENSE, rows[0].kind) // 星巴克
        assertEquals(TxKind.EXPENSE, rows[3].kind) // 转账给张三
        assertEquals(TxKind.INCOME, rows[4].kind)  // 红包
        assertEquals(TxKind.INCOME, rows[5].kind)  // 退款
    }

    @Test
    fun `提现被判为转账`() {
        val row = WeChatBillParser.parse(WeChatBillSample.FULL).rows[6]
        assertEquals(TxKind.TRANSFER, row.kind)
        assertEquals(50000L, row.cents)
    }

    @Test
    fun `备注列的斜杠被识别为占位符清空`() {
        val result = WeChatBillParser.parse(WeChatBillSample.FULL)
        // 微信账单的备注列常整列填「/」，此时应视为没有备注
        assertNull(result.rows.first().remark)
    }

    @Test
    fun `金额一律为正数`() {
        val rows = WeChatBillParser.parse(WeChatBillSample.FULL).rows
        assertTrue(rows.all { it.cents > 0 })
    }

    @Test
    fun `没有收支列时按交易类型判断`() {
        val result = WeChatBillParser.parse(WeChatBillSample.NO_KIND_COLUMN)
        assertNotNull(result.mapping)
        assertEquals(2, result.rows.size)
        assertEquals(TxKind.EXPENSE, result.rows[0].kind)  // 商户消费
        assertEquals(TxKind.TRANSFER, result.rows[1].kind) // 提现
    }

    @Test
    fun `列名不同的变体也能自动识别表头`() {
        // 「时间/金额/方向/对方」这类别名应被自动识别，无需用户手动映射
        val result = WeChatBillParser.parse(WeChatBillSample.ODD_HEADER)
        assertNotNull(result.mapping)
        assertEquals(2, result.rows.size)
        assertEquals(3300L, result.rows[0].cents)
        assertEquals("星巴克", result.rows[0].counterparty)
        assertEquals(TxKind.EXPENSE, result.rows[0].kind)
        assertEquals(TxKind.INCOME, result.rows[1].kind)
    }

    @Test
    fun `完全没有表头时返回空的映射`() {
        val result = WeChatBillParser.parse("这是一段没有表头的文本\n随便写点什么")
        assertNull(result.mapping)
        assertTrue(result.rows.isEmpty())
    }

    @Test
    fun `手动指定列映射后能解析`() {
        val mapping = ColumnMapping(
            timeIndex = 0,
            amountIndex = 2,
            kindIndex = 3,
            counterpartyIndex = 4,
            itemIndex = 1,
            statusIndex = ColumnMapping.NONE,
            tradeNoIndex = ColumnMapping.NONE,
            merchantNoIndex = ColumnMapping.NONE,
            payMethodIndex = ColumnMapping.NONE,
            remarkIndex = ColumnMapping.NONE,
            tradeTypeIndex = ColumnMapping.NONE,
        )
        val result = WeChatBillParser.parse(WeChatBillSample.ODD_HEADER, mapping)
        assertEquals(2, result.rows.size)
        assertEquals(3300L, result.rows[0].cents)
        assertEquals("星巴克", result.rows[0].counterparty)
        assertEquals(TxKind.EXPENSE, result.rows[0].kind)
        assertEquals(8800L, result.rows[1].cents)
        assertEquals(TxKind.INCOME, result.rows[1].kind)
    }

    @Test
    fun `坏行被计入skipped不影响其他行`() {
        val text = """
            交易时间,交易类型,交易对方,商品,收/支,金额(元),支付方式,当前状态,交易单号,商户单号,备注
            2024-03-15 14:32:07,商户消费,星巴克,拿铁,支出,¥33.00,零钱,支付成功,4200001111111111,1620001111,/
            这不是一行有效数据
            2024-03-16 10:00:00,商户消费,超市,蔬菜,支出,¥50.00,零钱,支付成功,4200009999999999,1620009999,/
        """.trimIndent()
        val result = WeChatBillParser.parse(text)
        assertEquals(2, result.rows.size)
        assertEquals(1, result.skipped)
    }

    @Test
    fun `空文本返回空结果`() {
        val result = WeChatBillParser.parse("")
        assertTrue(result.rows.isEmpty())
        assertNull(result.mapping)
    }

    @Test
    fun `解析方向的各种写法`() {
        assertEquals(TxKind.EXPENSE, WeChatBillParser.resolveKind("支出", null))
        assertEquals(TxKind.INCOME, WeChatBillParser.resolveKind("收入", null))
        // 收/支列为「/」时按交易类型细分
        assertEquals(TxKind.TRANSFER, WeChatBillParser.resolveKind("/", "零钱提现"))
        assertEquals(TxKind.TRANSFER, WeChatBillParser.resolveKind("/", "信用卡还款"))
        assertEquals(TxKind.NEUTRAL, WeChatBillParser.resolveKind("/", "其他"))
        // 没有收/支列时完全靠交易类型推断
        assertEquals(TxKind.EXPENSE, WeChatBillParser.resolveKind(null, "商户消费"))
        assertEquals(TxKind.EXPENSE, WeChatBillParser.resolveKind(null, "扫二维码付款"))
        assertEquals(TxKind.INCOME, WeChatBillParser.resolveKind(null, "退款"))
        assertEquals(TxKind.TRANSFER, WeChatBillParser.resolveKind(null, "零钱提现"))
        assertEquals(TxKind.NEUTRAL, WeChatBillParser.resolveKind(null, null))
    }
}
