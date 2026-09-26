package com.jizhang.app.notify

import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.TxKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 通知识别规则测试。
 *
 * 这些文本是按微信常见通知文案构造的样本。真机上的实际文案可能不同，
 * 所以 App 里保留了「待确认」队列和诊断页来兜底。
 */
class WeChatNotificationParserTest {

    // ---- 付款 ----

    @Test
    fun `识别付款成功通知`() {
        val result = WeChatNotificationParser.parse("微信支付", "付款成功 ￥25.00")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(2500L, result.cents)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `识别已支付通知`() {
        val result = WeChatNotificationParser.parse("微信支付", "已支付 ¥15.80")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(1580L, result.cents)
    }

    @Test
    fun `识别带元的付款通知`() {
        val result = WeChatNotificationParser.parse("微信支付", "微信支付付款成功，金额33.5元")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(3350L, result.cents)
    }

    @Test
    fun `识别自动扣费`() {
        val result = WeChatNotificationParser.parse("微信支付", "已扣款 ￥19.00，自动续费")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(1900L, result.cents)
    }

    // ---- 收款 ----

    @Test
    fun `识别收款到账通知`() {
        val result = WeChatNotificationParser.parse("微信支付", "收款到账 15.00 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(1500L, result.cents)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    @Test
    fun `识别微信支付收款通知`() {
        val result = WeChatNotificationParser.parse("微信支付", "微信支付收款 88.88 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(8888L, result.cents)
    }

    @Test
    fun `收款通知能抽出商家名`() {
        val result = WeChatNotificationParser.parse("微信支付", "张三的收款 20.00 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(2000L, result.cents)
        assertEquals("张三", result.counterparty)
    }

    // ---- 转账 ----

    @Test
    fun `识别转账支出`() {
        val result = WeChatNotificationParser.parse("微信支付", "转账 100.00 元 给张三")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(10000L, result.cents)
    }

    @Test
    fun `识别收到转账`() {
        val result = WeChatNotificationParser.parse("微信支付", "收到转账 200.00 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(20000L, result.cents)
    }

    @Test
    fun `转账通知能抽出对方名字`() {
        val result = WeChatNotificationParser.parse("微信支付", "向李四转账 50.00 元")
        assertNotNull(result)
        assertEquals("李四", result!!.counterparty)
    }

    // ---- 红包 / 退款 ----

    @Test
    fun `识别红包`() {
        val result = WeChatNotificationParser.parse("微信支付", "你领取了王五的红包 6.66 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(666L, result.cents)
    }

    @Test
    fun `识别退款`() {
        val result = WeChatNotificationParser.parse("微信支付", "退款 33.00 元 已到账")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(3300L, result.cents)
        assertEquals(Confidence.HIGH, result.confidence)
    }

    // ---- 账户间操作判为转账 ----

    @Test
    fun `提现判为转账`() {
        val result = WeChatNotificationParser.parse("微信支付", "提现 500.00 元 已到银行卡")
        assertNotNull(result)
        assertEquals(TxKind.TRANSFER, result!!.kind)
        assertEquals(50000L, result.cents)
    }

    @Test
    fun `信用卡还款判为转账`() {
        val result = WeChatNotificationParser.parse("微信支付", "信用卡还款 1000.00 元 成功")
        assertNotNull(result)
        assertEquals(TxKind.TRANSFER, result!!.kind)
        assertEquals(100000L, result.cents)
    }

    // ---- 应当忽略的内容 ----

    @Test
    fun `普通聊天消息不被识别`() {
        assertNull(WeChatNotificationParser.parse("张三", "晚上一起吃饭吗？"))
        assertNull(WeChatNotificationParser.parse("工作群", "明天的会议改到三点"))
        assertNull(WeChatNotificationParser.parse("李四", "收到，谢谢！"))
    }

    @Test
    fun `个人聊天里的付款文案不被识别`() {
        // v1.8 放宽的是「转账 / 红包 / 转给 / 收款」这四类字眼，
        // 聊天里复述一句「付款成功 ￥25.00」依旧不算一笔账。
        assertNull(WeChatNotificationParser.parse("张三", "付款成功 ￥25.00"))
    }

    @Test
    fun `群聊里的转账文案也识别但只进待确认`() {
        // v1.8 起放宽：聊天里带金额的转账/红包文案不再直接丢弃，
        // 但一律 LOW 置信度 + 「聊天·」前缀，只进待确认队列、绝不自动入账。
        val result = WeChatNotificationParser.parse("工作群", "转账 100.00 元")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(10000L, result.cents)
        assertEquals(Confidence.LOW, result.confidence)
        assertEquals(NotifyScope.CHAT, result.scope)
        assertTrue(result.ruleName.startsWith("聊天·"))
    }

    @Test
    fun `聊天中提到微信支付但不以来源开头时不被识别`() {
        assertNull(WeChatNotificationParser.parse("张三", "他说微信支付付款成功 ￥25.00"))
    }

    @Test
    fun `没有金额的支付类消息不被识别`() {
        assertNull(WeChatNotificationParser.parse("微信支付", "你有新的消息"))
    }

    @Test
    fun `没有支付关键词的纯数字消息不被识别`() {
        assertNull(WeChatNotificationParser.parse("张三", "明天记得带 3 份材料"))
    }

    @Test
    fun `空输入返回null`() {
        assertNull(WeChatNotificationParser.parse(null, null))
        assertNull(WeChatNotificationParser.parse("", ""))
        assertNull(WeChatNotificationParser.parse("   ", "  "))
    }

    // ---- 边界情况 ----

    @Test
    fun `金额为零不被识别`() {
        assertNull(WeChatNotificationParser.parse("微信支付", "付款成功 ￥0.00"))
    }

    @Test
    fun `整数金额正确解析`() {
        val result = WeChatNotificationParser.parse("微信支付", "付款成功 ￥100")
        assertNotNull(result)
        assertEquals(10000L, result!!.cents)
    }

    @Test
    fun `两位小数金额正确解析`() {
        val result = WeChatNotificationParser.parse("微信支付", "付款成功 ￥0.01")
        assertNotNull(result)
        assertEquals(1L, result!!.cents)
    }

    @Test
    fun `命中的规则名被记录`() {
        val result = WeChatNotificationParser.parse("微信支付", "付款成功 ￥25.00")
        assertNotNull(result)
        assertTrue(result!!.ruleName.isNotBlank())
    }

    @Test
    fun `标题和正文拼接后被匹配`() {
        // 金额在标题、动词在正文的情况
        val result = WeChatNotificationParser.parse("收款到账 ￥30.00", "微信支付")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(3000L, result.cents)
    }

    // ---- 指纹去重 ----

    @Test
    fun `同一时间窗口内相同内容指纹一致`() {
        val a = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥25.00", 1000L)
        val b = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥25.00", 30_000L)
        assertEquals(a, b)
    }

    @Test
    fun `不同时间窗口指纹不同`() {
        val a = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥25.00", 1000L)
        val b = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥25.00", 61_000L)
        assertTrue(a != b)
    }

    @Test
    fun `内容不同指纹不同`() {
        val a = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥25.00", 1000L)
        val b = WeChatNotificationParser.fingerprint("微信支付", "付款成功 ￥26.00", 1000L)
        assertTrue(a != b)
    }

    @Test
    fun `稳定key相同且内容相同视为同一通知更新`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = "pay-001",
        )
        val update = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功　￥25.00",
            80_000L,
            tag = null,
            id = 2,
            key = "pay-001",
        )
        assertEquals(first, update)
    }

    @Test
    fun `稳定key相同但内容变化视为新交易`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = "pay-001",
        )
        val second = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥26.00",
            1_100L,
            tag = null,
            id = 2,
            key = "pay-001",
        )
        assertTrue(first != second)
    }

    @Test
    fun `稳定key不同视为不同通知`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = "pay-001",
        )
        val second = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_100L,
            tag = null,
            id = 2,
            key = "pay-002",
        )
        assertTrue(first != second)
    }

    @Test
    fun `无key时tag与id可稳定区分通知且内容参与去重`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = "wechat-pay",
            id = 7,
            key = null,
        )
        val same = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            90_000L,
            tag = "wechat-pay",
            id = 7,
            key = " ",
        )
        val changed = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥26.00",
            90_000L,
            tag = "wechat-pay",
            id = 7,
            key = null,
        )
        val other = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = "wechat-pay",
            id = 8,
            key = null,
        )
        assertEquals(first, same)
        assertTrue(first != changed)
        assertTrue(first != other)
    }

    @Test
    fun `稳定key指纹包含规范化内容`() {
        val normalized = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款  成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = "pay-001",
        )
        val other = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款 成功 ￥25.00",
            2_000L,
            tag = null,
            id = 2,
            key = "pay-001",
        )
        assertEquals(normalized, other)
    }

    @Test
    fun `无稳定标识时同分钟相同内容去重`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = null,
        )
        val repeated = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            30_000L,
            tag = null,
            id = 1,
            key = null,
        )
        assertEquals(first, repeated)
    }

    @Test
    fun `无稳定标识时不同分钟相同内容不误判`() {
        val first = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            1_000L,
            tag = null,
            id = 1,
            key = null,
        )
        val later = WeChatNotificationParser.fingerprint(
            "微信支付",
            "付款成功 ￥25.00",
            61_000L,
            tag = null,
            id = 1,
            key = null,
        )
        assertTrue(first != later)
    }

    // ---- 文本归一化 ----

    @Test
    fun `归一化合并多余空白`() {
        val normalized = WeChatNotificationParser.normalize("微信支付", "付款   成功\n￥25.00")
        assertEquals("微信支付 付款 成功 ￥25.00", normalized)
    }

    @Test
    fun `归一化处理不间断空格`() {
        val normalized = WeChatNotificationParser.normalize(null, "付款\u00A0成功 ￥25.00")
        assertEquals("付款 成功 ￥25.00", normalized)
    }

    // ---- 红包必须区分发出与领取 ----

    @Test
    fun `发出红包判为支出`() {
        val result = WeChatNotificationParser.parse("微信红包", "你发了一个红包 ￥6.66")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(666L, result.cents)
    }

    @Test
    fun `红包已发出判为支出`() {
        val result = WeChatNotificationParser.parse("微信支付", "红包已发出 ￥88.00")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(8800L, result.cents)
    }

    @Test
    fun `领取红包仍判为收入`() {
        val result = WeChatNotificationParser.parse("微信红包", "你领取了王五的红包 6.66 元")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(666L, result.cents)
    }

    // ---- 权益/营销类通知不记账 ----

    @Test
    fun `立减金到账不被识别`() {
        assertNull(WeChatNotificationParser.parse("微信支付", "微信支付立减金 ¥5.00 已到账"))
    }

    @Test
    fun `优惠券即将过期不被识别`() {
        assertNull(WeChatNotificationParser.parse("微信支付", "你有1张优惠券即将过期"))
    }

    @Test
    fun `带权益字样的真实支付仍被识别`() {
        val result = WeChatNotificationParser.parse("微信支付", "已支付 ¥15.00，使用立减金2.00元")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(1500L, result.cents)
    }

    // ---- 账户间周转算转账 ----

    @Test
    fun `零钱通转入判为转账`() {
        val result = WeChatNotificationParser.parse("微信支付", "零钱通 转入 1000.00 元")
        assertNotNull(result)
        assertEquals(TxKind.TRANSFER, result!!.kind)
        assertEquals(100000L, result.cents)
    }

    // ---- 交易对方抽取（⑥⑦） ----

    @Test
    fun `红包通知抽出领取人而不是来源名`() {
        val result = WeChatNotificationParser.parse("微信红包", "你领取了王五的红包 6.66 元")
        assertNotNull(result)
        assertEquals("王五", result!!.counterparty)
    }

    @Test
    fun `来源名不会被当成交易对方`() {
        // 「服务通知」是通知来源，不是商户/人名
        val fromService = WeChatNotificationParser.parse("服务通知", "微信支付收款 8.80 元")
        assertNotNull(fromService)
        assertNull(fromService!!.counterparty)

        val fromRedPacket = WeChatNotificationParser.parse("微信红包", "你发了一个红包 ￥6.66")
        assertNotNull(fromRedPacket)
        assertNull(fromRedPacket!!.counterparty)
    }

    @Test
    fun `人名在句尾也能抽出`() {
        val result = WeChatNotificationParser.parse("微信支付", "转账 100.00 元 给张三")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals("张三", result.counterparty)
    }

    // ---- 群收款命中自己的规则（⑧） ----

    @Test
    fun `群收款不再被通用收款规则抢走`() {
        val result = WeChatNotificationParser.parse("微信支付", "群收款 30.00 元 已到账")
        assertNotNull(result)
        assertEquals(TxKind.INCOME, result!!.kind)
        assertEquals(3000L, result.cents)
        assertEquals("群收款", result.ruleName)
        // 方向本身有歧义，只进待确认队列，不自动入账
        assertEquals(Confidence.MEDIUM, result.confidence)
    }

    // ---- 聊天通道：好友之间的转账与红包（v1.8） ----

    @Test
    fun `聊天里好友转账给你识别为收入`() {
        val result = WeChatNotificationParser.parse("李四", "李四向你转账 200.00 元")
        assertNotNull(result)
        assertEquals(NotifyScope.CHAT, result!!.scope)
        assertEquals(TxKind.INCOME, result.kind)
        assertEquals(20000L, result.cents)
        assertEquals(Confidence.LOW, result.confidence)
        assertEquals("聊天·转账收入", result.ruleName)
        assertEquals("李四", result.counterparty)
    }

    @Test
    fun `聊天里转给好友识别为支出`() {
        val result = WeChatNotificationParser.parse("王五", "转给王五 88.00 元")
        assertNotNull(result)
        assertEquals(NotifyScope.CHAT, result!!.scope)
        assertEquals(TxKind.EXPENSE, result.kind)
        assertEquals(8800L, result.cents)
        assertEquals("聊天·转账支出", result.ruleName)
    }

    @Test
    fun `聊天里方括号转账文案也能识别`() {
        val result = WeChatNotificationParser.parse("张三", "[转账] 100.00 元")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(10000L, result.cents)
        assertEquals(NotifyScope.CHAT, result.scope)
    }

    @Test
    fun `聊天里好友发来的红包识别为收入`() {
        val result = WeChatNotificationParser.parse("张三", "张三给你发了一个红包 6.66 元")
        assertNotNull(result)
        assertEquals(NotifyScope.CHAT, result!!.scope)
        assertEquals(TxKind.INCOME, result.kind)
        assertEquals(666L, result.cents)
        assertEquals("聊天·红包收入", result.ruleName)
        // 「给你发了一个红包」不能被抓成商户名
        assertEquals("张三", result.counterparty)
    }

    @Test
    fun `聊天里自己发出的红包识别为支出`() {
        val result = WeChatNotificationParser.parse("张三", "你发了一个红包 8.88 元")
        assertNotNull(result)
        assertEquals(TxKind.EXPENSE, result!!.kind)
        assertEquals(888L, result.cents)
        assertEquals(Confidence.LOW, result.confidence)
        assertEquals("聊天·红包支出", result.ruleName)
    }

    @Test
    fun `聊天里没有金额的转账字样不识别`() {
        assertNull(WeChatNotificationParser.parse("李四", "我明天转账给你"))
        assertNull(WeChatNotificationParser.parse("李四", "转给你一个红包，自己抢"))
    }

    @Test
    fun `聊天里与钱无关的数字消息不识别`() {
        assertNull(WeChatNotificationParser.parse("李四", "转给我 3 份材料"))
    }

    @Test
    fun `支付来源的通知仍走支付通道`() {
        val result = WeChatNotificationParser.parse("微信支付", "付款成功 ￥25.00")
        assertNotNull(result)
        assertEquals(NotifyScope.PAYMENT, result!!.scope)
        assertEquals(Confidence.HIGH, result.confidence)
        assertEquals("付款成功", result.ruleName)
    }

    // ---- 诊断日志的采集范围 ----
    //
    // v1.4 曾加过 isDiagnosticCandidate()，只让支付类来源和带金额的文案落库。
    // v1.9 按用户要求撤掉这道闸、回到「所有微信通知都读、都留样本」的原始行为，
    // 采集范围不再由解析器过滤，所以相关的三个用例连同这个函数一起删了。
    // 现在守这条边界的是监听器里的无条件 logNotification（见 verify_notify_fix.py §9）。
}
