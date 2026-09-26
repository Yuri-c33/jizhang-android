package com.jizhang.app.notify

import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.money.Money

/**
 * 识别通道。
 *
 * 这两个通道的区别不在「能不能识别」，而在**可信度**：
 * 支付来源的通知是微信自己发的那句确定的话；聊天里的一句话则可能只是
 * 「我明天转账给你」这种聊天内容。所以聊天通道的结论一律降级为 LOW、
 * 只进待确认队列，绝不自动入账（见 [WeChatNotificationListener]）。
 */
enum class NotifyScope {
    /** 微信支付 / 收款助手 / 服务通知 / 微信转账 / 微信红包等支付来源。 */
    PAYMENT,

    /** 私聊/群聊里的转账、红包文案。方向靠措辞推断，不保证准确。 */
    CHAT,
}

/** 一条通知的解析结果。 */
data class NotifyParseResult(
    val kind: TxKind,
    val cents: Long,
    val counterparty: String?,
    val item: String?,
    val ruleName: String,
    val confidence: Confidence,
    /** 识别通道，默认按支付来源处理。 */
    val scope: NotifyScope = NotifyScope.PAYMENT,
)

/**
 * 微信通知解析器。
 *
 * 微信支付通知没有稳定公开的格式，各版本、各场景文案差异较大，
 * 所以这里用「规则表」而不是单一大正则：每条规则有名字、正则、方向和置信度。
 * 命中的规则名会一起存进待确认条目，方便诊断页排查与后续调整。
 *
 * 识别分两个通道（见 [NotifyScope]）：
 * - 支付来源（微信支付 / 收款助手 / 服务通知 / 微信转账 / 微信红包）按规则表原样给置信度；
 * - 聊天里的转账、红包文案也识别，但方向只能靠措辞猜，一律降级为 LOW +
 *   「聊天·」规则名前缀，只进待确认队列，绝不自动入账。
 *   前者是微信自己说的确定的话，后者可能只是「我明天转给你 200」这种聊天内容，
 *   两者必须区别对待，所以置信度不是「越准越高」的调参，而是这条边界。
 *
 * 注意：如果用户在微信里设置了「不显示消息详情」，通知里就不会有金额，
 * 此时任何规则都无解——这是系统层面的限制。
 */
object WeChatNotificationParser {

    /** 规则定义。 */
    private data class Rule(
        val name: String,
        val pattern: Regex,
        val kind: TxKind,
        val confidence: Confidence,
        /** 金额所在的正则分组序号。 */
        val amountGroup: Int = 2,
    )

    /** 归一到一行文本，便于正则匹配。 */
    fun normalize(title: String?, text: String?): String {
        val parts = listOfNotNull(title, text)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        val joined = parts.joinToString(" ")
        val flattened = StringBuilder(joined.length)
        for (ch in joined) {
            when {
                Character.isWhitespace(ch) || Character.isSpaceChar(ch) -> flattened.append(' ')
                ch == '\u200B' || ch == '\u200C' || ch == '\u200D' || ch == '\uFEFF' -> Unit
                else -> flattened.append(ch)
            }
        }
        return flattened.toString()
            .replace(Regex(" +"), " ")
            .trim()
    }

    /**
     * 解析通知文本。识别不到金额类内容时返回 null。
     *
     * 分两个通道：
     * - [NotifyScope.PAYMENT]：标题/正文以微信支付等可信来源开头，按规则表原样给置信度；
     * - [NotifyScope.CHAT]：私聊/群聊（标题是好友昵称或群名）里出现「转账/红包/收款/转给」
     *   且带金额。这类文案方向只能靠措辞猜，所以统一降级为 [Confidence.LOW]、
     *   规则名加「聊天·」前缀，只进待确认队列由用户裁决。
     */
    fun parse(title: String?, text: String?): NotifyParseResult? {
        val content = normalize(title, text)
        if (content.isBlank()) return null

        // 先定通道：可信来源，还是聊天。都不是就不必往下跑正则。
        val scope = resolveScope(title, text, content) ?: return null
        if (!looksLikeMoneyNotification(content)) return null
        // 营销/权益类通知也带「￥金额 + 到账」，交给兜底规则会被记成一笔支出
        if (isNonTransaction(content)) return null

        // 聊天通道先跑聊天专用规则（措辞方向和支付通知不一样，比如「转账给你」「[转账]」），
        // 没命中再回落到同一张规则表。
        val rules = if (scope == NotifyScope.CHAT) CHAT_RULES + RULES else RULES
        for (rule in rules) {
            val match = rule.pattern.find(content) ?: continue
            val amountText = match.groupValues.getOrNull(rule.amountGroup)
                ?: match.groupValues.lastOrNull()
            val cents = Money.parseYuanToCents(amountText) ?: continue
            if (cents <= 0) continue

            val (counterparty, item) = extractParties(content, title)

            // 聊天来源一律 LOW + 「聊天·」前缀：待确认页能看到它不是微信支付说的。
            // 前缀只在这里加一次——[CHAT_RULES] 的规则名刻意不带前缀，两处都加会变成
            // 「聊天·聊天·转账收入」。
            val confidence = if (scope == NotifyScope.CHAT) Confidence.LOW else rule.confidence
            val ruleName = if (scope == NotifyScope.CHAT) CHAT_RULE_PREFIX + rule.name else rule.name

            return NotifyParseResult(
                kind = rule.kind,
                cents = cents,
                counterparty = counterparty,
                item = item,
                ruleName = ruleName,
                confidence = confidence,
                scope = scope,
            )
        }
        return null
    }

    /** 判定识别通道；两边都不像就返回 null。 */
    private fun resolveScope(title: String?, text: String?, content: String): NotifyScope? = when {
        hasTrustedSource(title, text) -> NotifyScope.PAYMENT
        hasChatTransferSource(title, content) -> NotifyScope.CHAT
        else -> null
    }

    /** 聊天来源的规则名前缀，只有一个地方加（见 [parse]）。 */
    private const val CHAT_RULE_PREFIX = "聊天·"

    /** 标题或正文必须以可信的微信支付来源开头。 */
    private fun hasTrustedSource(title: String?, text: String?): Boolean =
        listOf(title, text).any { raw ->
            val candidate = raw?.trim().orEmpty()
            candidate.isNotEmpty() && TRUSTED_SOURCES.any { candidate.startsWith(it) }
        }

    /**
     * 是不是「聊天里在说钱」。
     *
     * 私聊/群聊通知的标题是好友昵称或群名，正文才是消息本身。三个条件同时成立才算：
     * 标题不像应用/支付来源（[APP_NAMES]、[TRUSTED_SOURCES] 之外）、带金额、
     * 且正文出现钱相关字眼（[CHAT_MONEY_HINTS] 强信号，或 [CHAT_WEAK_HINTS] + 货币标记）。
     *
     * 三个条件缺一不可：只靠「转账」二字会把「我明天转账给你」这种聊天也记成一笔账，
     * 只靠金额则等于把聊天原文全部拖进来。
     */
    private fun hasChatTransferSource(title: String?, content: String): Boolean {
        val t = title?.trim().orEmpty()
        if (t.isEmpty() || APP_NAMES.contains(t)) return false
        if (TRUSTED_SOURCES.any { t.startsWith(it) }) return false
        if (!hasAmount(content)) return false
        // 强信号：「转账」「红包」出现基本就是钱的事。
        if (CHAT_MONEY_HINTS.any { content.contains(it) }) return true
        // 弱信号：「转给」「收款」必须同时带「元」或 ¥/￥，否则「转给我 3 份材料」
        // 这种日常说法也会被判成转账。
        return hasCurrencyMarker(content) && CHAT_WEAK_HINTS.any { content.contains(it) }
    }

    /** 文本里是否有金额数字。 */
    private fun hasAmount(content: String): Boolean = AMOUNT_PATTERN.containsMatchIn(content)

    /** 文本里是否出现了明确的货币标记。 */
    private fun hasCurrencyMarker(content: String): Boolean =
        content.contains("元") || content.contains("¥") || content.contains("￥")

    private val AMOUNT_PATTERN = Regex("""\d+(?:\.\d{1,2})?""")

    /** 聊天里出现这些字眼基本可以确定在说钱。 */
    private val CHAT_MONEY_HINTS = listOf("转账", "红包")

    /**
     * 弱信号。加「转给」是因为好友之间常见的说法是「转给你 200 元」而不是
     * 「转账给你 200 元」，只认「转账」会漏掉一大半；但也正因为「转给」太常见，
     * 必须配上货币标记才认。
     */
    private val CHAT_WEAK_HINTS = listOf("转给", "收款")

    /**
     * 快速预判：文本里必须有金额特征，否则不必逐条跑正则。
     * 这样能避免把普通聊天消息也拖进来。
     */
    private fun looksLikeMoneyNotification(content: String): Boolean {
        if (!hasAmount(content)) return false
        val keywords = listOf(
            "支付", "付款", "收款", "转账", "红包", "退款", "已收钱",
            "到账", "扣款", "提现", "充值", "还款", "零钱", "消费", "收款方",
            "转给", "转出", "转入",
        )
        return keywords.any { content.contains(it) }
    }

    /**
     * 明确不是收支交易的通知。
     *
     * 「立减金 / 优惠券 / 积分」这类权益到账文案同样带金额，一旦落到兜底规则
     * 就会被记成一笔支出。只要文里同时出现「确实发生了交易」的字样（见
     * [TRANSACTION_GUARD]），就仍按交易处理——例如「已支付 ¥15.00，使用立减金 2.00 元」
     * 不能被误杀。
     */
    private fun isNonTransaction(content: String): Boolean {
        if (TRANSACTION_GUARD.any { content.contains(it) }) return false
        return NON_TRANSACTION_PATTERNS.any { it.containsMatchIn(content) }
    }

    /** 出现这些词说明确实发生了交易，不再套用上面的排除规则。 */
    private val TRANSACTION_GUARD = listOf(
        "已支付", "付款成功", "支付成功", "消费", "扣款",
        "收款", "转账", "退款", "提现", "还款", "充值",
    )

    /**
     * 权益/营销类文案：权益词与「到账/发放」之间允许夹着金额，
     * 所以中间用 `.{0,12}?` 而不是 `[^\d]{0,12}`。
     */
    private val NON_TRANSACTION_PATTERNS = listOf(
        Regex(
            """(?:立减金|抵用券|优惠券|代金券|支付券|红包封面|金币|积分|权益)""" +
                """.{0,12}?(?:到账|入账|发放|生效|可领取|待领取|过期)""",
        ),
        Regex("""(?:即将过期|到期提醒|有效期至)"""),
    )

    /** 微信支付相关通知可能使用的标题/正文来源前缀。 */
    private val TRUSTED_SOURCES = listOf(
        "微信支付",
        "微信支付通知",
        "微信支付凭证",
        "微信收款助手",
        "收款助手",
        "服务通知",
        "微信转账",
        "微信红包",
    )

    /**
     * 规则表。顺序敏感：越具体的规则越靠前。
     */
    private val RULES: List<Rule> = listOf(
        // 群收款：必须排在「收款到账」之前，否则文案里的「收款」会让通用规则
        // 先命中，诊断页看到的规则名就永远是「收款到账」，没法按规则排查。
        // 不收「收款已到账」这种通用说法，那种交给下面的通用收款规则。
        // 方向本身有歧义（你发起的收款 vs 别人发起你要付），所以保持 MEDIUM，
        // 只进待确认队列、不自动入账。
        Rule(
            name = "群收款",
            pattern = Regex("""(?:群收款|AA收款)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.INCOME,
            confidence = Confidence.MEDIUM,
            amountGroup = 1,
        ),
        // 收款：微信支付 收款到账通知
        Rule(
            name = "收款到账",
            pattern = Regex("""收款(?:到账|成功)?[，,\s]*(?:微信支付)?[^\d]{0,10}[¥￥]?\s*(\d+(?:\.\d{1,2})?)"""),
            kind = TxKind.INCOME,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 常见的「微信支付收款 X 元」
        Rule(
            name = "收款到账元",
            pattern = Regex("""收款[^\d]{0,8}(\d+(?:\.\d{1,2})?)\s*元"""),
            kind = TxKind.INCOME,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 付款成功
        Rule(
            name = "付款成功",
            pattern = Regex("""(?:付款|支付)(?:成功|完成|已支付)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 「微信支付：已支付 ¥15.00」
        Rule(
            name = "已支付",
            pattern = Regex("""已支付[^\d]{0,10}[¥￥]?\s*(\d+(?:\.\d{1,2})?)"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 扣款
        Rule(
            name = "扣款",
            pattern = Regex("""(?:扣款|已扣款|自动扣费|免密支付)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 转账：方向敏感的规则必须排在通用的前面。
        // 「收到转账」要先于「转账」匹配，否则会被当成支出。
        Rule(
            name = "转账收入",
            pattern = Regex("""(?:收到转账|对方已收钱|已收款|收款)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.INCOME,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        Rule(
            name = "转账支出",
            pattern = Regex("""(?:向[^\d]{1,12}转账|已转账|转账成功|转账)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.MEDIUM,
            amountGroup = 1,
        ),
        // 红包：必须按「发出」/「领取」区分方向。
        // 只认「红包」二字会把发出去的红包记成收入，所以支出规则排前面。
        Rule(
            name = "红包支出",
            pattern = Regex(
                """(?:红包\s*已(?:发出|发送)|(?:你)?发(?:出|了)?(?:一个)?红包|发出红包)""" +
                    """[^\d]{0,10}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.EXPENSE,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        Rule(
            name = "红包收入",
            pattern = Regex(
                """(?:领取了|已领取|收到|获得|抢到)[^\d]{0,12}红包""" +
                    """[^\d]{0,10}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.INCOME,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 退款
        Rule(
            name = "退款到账",
            pattern = Regex("""退款[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.INCOME,
            confidence = Confidence.HIGH,
            amountGroup = 1,
        ),
        // 提现：账户间转移，算转账
        Rule(
            name = "零钱提现",
            pattern = Regex("""提现[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.TRANSFER,
            confidence = Confidence.MEDIUM,
            amountGroup = 1,
        ),
        // 充值
        Rule(
            name = "充值",
            pattern = Regex("""充值[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.TRANSFER,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
        // 信用卡还款
        Rule(
            name = "信用卡还款",
            pattern = Regex("""还款[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?"""),
            kind = TxKind.TRANSFER,
            confidence = Confidence.MEDIUM,
            amountGroup = 1,
        ),
        // 账户间周转（零钱通/理财通/银行卡与零钱互转）：不是收支，算转账。
        // 必须排在「零钱提现」「信用卡还款」之后，否则会抢走它们的规则名。
        Rule(
            name = "账户间周转",
            pattern = Regex(
                """(?:零钱通|理财通|零钱|银行卡|储蓄卡)""" +
                    """[^\d]{0,8}(?:转入|转出|存入|取出)[^\d]{0,10}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.TRANSFER,
            confidence = Confidence.MEDIUM,
            amountGroup = 1,
        ),
        // 兜底：出现「微信支付」且有金额
        Rule(
            name = "通用兜底",
            pattern = Regex("""[¥￥]\s*(\d+(?:\.\d{1,2})?)"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
        Rule(
            name = "通用兜底元",
            pattern = Regex("""(\d+(?:\.\d{1,2})?)\s*元"""),
            kind = TxKind.EXPENSE,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
    )

    /**
     * 聊天通道专用规则，只在 [NotifyScope.CHAT] 下先跑一遍，没命中再回落到 [RULES]。
     *
     * 为什么不能直接用 [RULES]：
     * - 支付通知写「收到转账 200.00 元」，好友聊天写「转给你 200」「转给张三 88」，
     *   措辞完全不同；裸「转账」规则会把「向你转账」也吃成支出，方向正好反过来。
     * - 红包同理：「张三给你发了一个红包 6.66 元」是你**收**到，[RULES] 的
     *   「发(了)?红包」分支会把它记成支出。
     *
     * 方向推断不出来的（例如只写了「[转账] 100.00 元」），按「支出」预填——
     * 这类结果置信度一律 LOW、只进待确认队列，用户在那里改一下即可。
     * 只有「发了一个红包 100元」这种连主语都没有的写法会落到 [RULES] 兜底，方向同样是支出。
     *
     * 规则名这里**不带**「聊天·」前缀：前缀是通道属性，由 [parse] 统一加。
     * 两处都加会得到「聊天·聊天·转账收入」（真的踩过）。
     */
    private val CHAT_RULES: List<Rule> = listOf(
        // 收入：钱进来。必须排在支出规则之前，否则「转给你」会被下面的裸「转账」抢走。
        Rule(
            name = "转账收入",
            pattern = Regex(
                """(?:(?:向|给)你转账|转账给你|转给你|你收到(?:了)?(?:一笔)?转账""" +
                    """|收到[^\d]{0,12}的转账)[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.INCOME,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
        // 红包收入：好友发给你。必须在 [RULES] 的「发红包」分支之前命中，否则方向会反过来。
        Rule(
            name = "红包收入",
            pattern = Regex(
                """(?:(?:给你|我)发(?:了|出)?(?:一个)?红包|发给你(?:一个)?红包""" +
                    """|你(?:领取|拆开|抢|收)了?[^\d]{0,12}红包)[^\d]{0,12}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.INCOME,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
        // 支出：钱出去。
        Rule(
            name = "转账支出",
            pattern = Regex(
                """(?:转账给|转给|已转账|转账成功|转出|\[转账\]|【转账】|转账)""" +
                    """[^\d]{0,20}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.EXPENSE,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
        Rule(
            name = "红包支出",
            pattern = Regex(
                """(?:你(?:已)?发(?:出|了)?(?:一个)?红包|红包已(?:发出|发送)|发出红包)""" +
                    """[^\d]{0,12}[¥￥]?\s*(\d+(?:\.\d{1,2})?)\s*元?""",
            ),
            kind = TxKind.EXPENSE,
            confidence = Confidence.LOW,
            amountGroup = 1,
        ),
    )

    /**
     * 尽力抽取交易对方。
     *
     * 标题通常是「微信支付」这样的应用名，正文里才有人名/商户名。
     * 名字用「不含空格的连续字符」匹配，避免把「微信支付 张三」整串吃进来。
     *
     * 抽不到就回退到标题，但必须先过 [APP_NAMES]——否则「微信红包」「服务通知」
     * 这类来源名会被当成商户名写进账目，还会被 learnKeyword 学成分类规则。
     * 抽到的名字还要过一遍 [isPlausibleName] 的形状检查，挡住「你发了一个红包」这种。
     */
    private fun extractParties(content: String, title: String?): Pair<String?, String?> {
        val titleClean = title?.trim()?.takeIf { it.isNotEmpty() && !APP_NAMES.contains(it) }

        val patterns = listOf(
            Regex("""向([^\s]{1,20}?)(?:付款|转账|支付)"""),
            Regex("""([^\s]{1,20}?)的收款"""),
            Regex("""收款方[：:]\s*([^\s]{1,20})"""),
            Regex("""付款给([^\s]{1,20})"""),
            Regex("""来自([^\s]{1,20}?)的转账"""),
            Regex("""([^\s]{1,20}?)向你转账"""),
            // 红包：必须锚在动词上。「([^\s]+?)的红包」会从左边开始最短匹配，
            // 把「你领取了」一起吞进名字里，得到「你领取了王五」。
            Regex("""(?:领取了|收到|获得|抢到)([^\s]{1,20}?)的红包"""),
            // 人名在句尾的写法：「转账 100.00 元 给张三」。放在最后，别抢上面更具体的规则。
            Regex("""给([^\s]{1,20})"""),
        )
        for (pattern in patterns) {
            val m = pattern.find(content)
            val name = m?.groupValues?.getOrNull(1)?.trim()?.removePrefix("微信支付")
            if (!name.isNullOrEmpty() && !APP_NAMES.contains(name) && isPlausibleName(name)) {
                return name to null
            }
        }

        return titleClean to null
    }

    /**
     * 抽出来的字符串像不像人名/商户名。
     *
     * 最后那条 `给X` 模式非常松：「张三给你发了一个红包」会抓出「你发了一个红包」，
     * 「向你转账」的 `向X转账` 会抓出「你」。这类词一旦当商户名写进账目，
     * 还会被 learnKeyword 学成分类规则，污染会一直留在库里。所以过一遍形状检查，
     * 不合格就换下一个模式（都不合格时回退到标题——聊天场景下标题正好是好友昵称）。
     */
    private fun isPlausibleName(candidate: String): Boolean {
        if (candidate.length > 20) return false
        if (NAME_REJECT_PREFIXES.any { candidate.startsWith(it) }) return false
        return NAME_REJECT_WORDS.none { candidate.contains(it) }
    }

    /** 开头是这些字，说明抓到的是一句话的主语，不是名字。 */
    private val NAME_REJECT_PREFIXES = listOf("你", "我", "他", "她", "它", "这", "那", "对方")

    /** 含这些字眼说明抓到的是动作/名词短语，不是名字。 */
    private val NAME_REJECT_WORDS = listOf(
        "红包", "转账", "收款", "付款", "支付", "余额", "零钱", "微信", "银行卡",
    )

    /**
     * 这些不是交易对方，是应用/通知来源名或可信来源前缀。
     * 只要标题整体等于其中一项，就不能当商户名用。
     */
    private val APP_NAMES = setOf(
        "微信支付", "微信", "WeChat",
        "微信支付通知", "微信支付凭证", "微信收款助手", "收款助手",
        "服务通知", "微信转账", "微信红包",
    )

    /**
     * 计算内容指纹，用于防止同一通知被反复入队。
     * 同一笔交易在短时间内可能被多次推送（通知更新），指纹里含金额与时间窗口。
     */
    fun fingerprint(title: String?, text: String?, postedAt: Long, windowMillis: Long = 60_000): String {
        val content = normalize(title, text)
        val bucket = postedAt / windowMillis
        return "$bucket|$content"
    }

    /**
     * 通知级指纹：稳定标识 + 规范化内容。
     *
     * 微信可能复用同一个系统通知 key 推送后续交易。只按 key 去重会把
     * 新的真实交易当成同一条通知更新丢掉；把内容一起纳入指纹后，
     * 完全相同的重复推送仍会去重，内容变化则视为新交易。
     * 没有稳定标识时回退到时间窗口内容指纹。
     */
    fun fingerprint(
        title: String?,
        text: String?,
        postedAt: Long,
        tag: String?,
        id: Int,
        key: String?,
    ): String {
        val stableId = key?.takeIf { it.isNotBlank() }
            ?: tag?.takeIf { it.isNotBlank() }?.let { "$it:$id" }
        return if (stableId != null) {
            "sbn|$stableId|${normalize(title, text)}"
        } else {
            fingerprint(title, text, postedAt)
        }
    }
}
