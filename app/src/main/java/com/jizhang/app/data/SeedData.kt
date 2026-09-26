package com.jizhang.app.data

import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.AccountType
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.MerchantRule
import com.jizhang.app.data.db.entity.TxKind

/**
 * 首次启动时写入的默认分类、账户与「关键词 → 分类」规则。
 */
object SeedData {

    val expenseCategories: List<Pair<String, String>> = listOf(
        "餐饮" to "🍜",
        "零食饮料" to "🧋",
        "交通" to "🚌",
        "购物" to "🛍️",
        "生活缴费" to "💡",
        "话费网费" to "📱",
        "住房房租" to "🏠",
        "医疗健康" to "💊",
        "娱乐" to "🎬",
        "学习" to "📚",
        "人情往来" to "🎁",
        "服饰美容" to "👕",
        "运动健身" to "🏃",
        "旅行" to "✈️",
        "数码电器" to "💻",
        "宠物" to "🐾",
        "育儿" to "🍼",
        "烟酒" to "🍷",
        "投资理财" to "📈",
        "其他支出" to "📦",
    )

    val incomeCategories: List<Pair<String, String>> = listOf(
        "工资" to "💰",
        "奖金" to "🏆",
        "兼职" to "🧰",
        "投资收益" to "📈",
        "报销" to "🧾",
        "退款" to "↩️",
        "红包" to "🧧",
        "其他收入" to "💵",
    )

    fun categories(): List<Category> {
        val list = mutableListOf<Category>()
        expenseCategories.forEachIndexed { index, (name, emoji) ->
            list += Category(name = name, emoji = emoji, kind = TxKind.EXPENSE, sortOrder = index, builtin = true)
        }
        incomeCategories.forEachIndexed { index, (name, emoji) ->
            list += Category(name = name, emoji = emoji, kind = TxKind.INCOME, sortOrder = index, builtin = true)
        }
        return list
    }

    fun accounts(): List<Account> = listOf(
        Account(name = "微信零钱", type = AccountType.WECHAT, sortOrder = 0, builtin = true),
        Account(name = "零钱通", type = AccountType.WECHAT_LICAITONG, sortOrder = 1, builtin = true),
        Account(name = "银行卡", type = AccountType.BANK_CARD, sortOrder = 2, builtin = true),
        Account(name = "支付宝", type = AccountType.ALIPAY, sortOrder = 3, builtin = true),
        Account(name = "现金", type = AccountType.CASH, sortOrder = 4, builtin = true),
    )

    /**
     * 内置的「关键词 → 分类名」规则。
     * 用分类名而非 id，写入时再解析成 id。
     */
    val keywordRules: List<Triple<String, String, TxKind>> = listOf(
        // 餐饮
        Triple("美团", "餐饮", TxKind.EXPENSE),
        Triple("饿了么", "餐饮", TxKind.EXPENSE),
        Triple("肯德基", "餐饮", TxKind.EXPENSE),
        Triple("麦当劳", "餐饮", TxKind.EXPENSE),
        Triple("星巴克", "餐饮", TxKind.EXPENSE),
        Triple("瑞幸", "餐饮", TxKind.EXPENSE),
        Triple("餐厅", "餐饮", TxKind.EXPENSE),
        Triple("饭店", "餐饮", TxKind.EXPENSE),
        Triple("面馆", "餐饮", TxKind.EXPENSE),
        Triple("火锅", "餐饮", TxKind.EXPENSE),
        Triple("咖啡", "餐饮", TxKind.EXPENSE),
        Triple("奶茶", "餐饮", TxKind.EXPENSE),
        // 零食饮料
        Triple("零食", "零食饮料", TxKind.EXPENSE),
        Triple("便利店", "零食饮料", TxKind.EXPENSE),
        Triple("蜜雪冰城", "零食饮料", TxKind.EXPENSE),
        Triple("超市", "购物", TxKind.EXPENSE),
        // 烟酒
        Triple("香烟", "烟酒", TxKind.EXPENSE),
        Triple("白酒", "烟酒", TxKind.EXPENSE),
        Triple("啤酒", "烟酒", TxKind.EXPENSE),
        // 交通
        Triple("滴滴", "交通", TxKind.EXPENSE),
        Triple("高德", "交通", TxKind.EXPENSE),
        Triple("地铁", "交通", TxKind.EXPENSE),
        Triple("公交", "交通", TxKind.EXPENSE),
        Triple("出租车", "交通", TxKind.EXPENSE),
        Triple("加油", "交通", TxKind.EXPENSE),
        Triple("停车", "交通", TxKind.EXPENSE),
        Triple("12306", "交通", TxKind.EXPENSE),
        Triple("航空", "交通", TxKind.EXPENSE),
        // 购物
        Triple("淘宝", "购物", TxKind.EXPENSE),
        Triple("天猫", "购物", TxKind.EXPENSE),
        Triple("京东", "购物", TxKind.EXPENSE),
        Triple("拼多多", "购物", TxKind.EXPENSE),
        Triple("唯品会", "购物", TxKind.EXPENSE),
        Triple("沃尔玛", "购物", TxKind.EXPENSE),
        Triple("永辉", "购物", TxKind.EXPENSE),
        // 生活缴费
        Triple("国家电网", "生活缴费", TxKind.EXPENSE),
        Triple("电费", "生活缴费", TxKind.EXPENSE),
        Triple("水费", "生活缴费", TxKind.EXPENSE),
        Triple("燃气", "生活缴费", TxKind.EXPENSE),
        Triple("物业", "生活缴费", TxKind.EXPENSE),
        // 通讯
        Triple("中国移动", "话费网费", TxKind.EXPENSE),
        Triple("中国联通", "话费网费", TxKind.EXPENSE),
        Triple("中国电信", "话费网费", TxKind.EXPENSE),
        Triple("话费", "话费网费", TxKind.EXPENSE),
        // 住房
        Triple("房租", "住房房租", TxKind.EXPENSE),
        Triple("租金", "住房房租", TxKind.EXPENSE),
        // 医疗
        Triple("医院", "医疗健康", TxKind.EXPENSE),
        Triple("药房", "医疗健康", TxKind.EXPENSE),
        Triple("药店", "医疗健康", TxKind.EXPENSE),
        Triple("诊所", "医疗健康", TxKind.EXPENSE),
        // 娱乐
        Triple("电影", "娱乐", TxKind.EXPENSE),
        Triple("影院", "娱乐", TxKind.EXPENSE),
        Triple("KTV", "娱乐", TxKind.EXPENSE),
        Triple("腾讯视频", "娱乐", TxKind.EXPENSE),
        Triple("爱奇艺", "娱乐", TxKind.EXPENSE),
        Triple("哔哩哔哩", "娱乐", TxKind.EXPENSE),
        Triple("网易云音乐", "娱乐", TxKind.EXPENSE),
        Triple("Steam", "娱乐", TxKind.EXPENSE),
        // 学习
        Triple("书店", "学习", TxKind.EXPENSE),
        Triple("当当", "学习", TxKind.EXPENSE),
        Triple("培训", "学习", TxKind.EXPENSE),
        // 健身
        Triple("健身房", "运动健身", TxKind.EXPENSE),
        Triple("keep", "运动健身", TxKind.EXPENSE),
        // 服饰
        Triple("优衣库", "服饰美容", TxKind.EXPENSE),
        Triple("美的", "数码电器", TxKind.EXPENSE),
        Triple("小米", "数码电器", TxKind.EXPENSE),
        Triple("华为", "数码电器", TxKind.EXPENSE),
        Triple("苹果", "数码电器", TxKind.EXPENSE),
        // 收入
        Triple("工资", "工资", TxKind.INCOME),
        Triple("薪资", "工资", TxKind.INCOME),
        Triple("薪酬", "工资", TxKind.INCOME),
        Triple("奖金", "奖金", TxKind.INCOME),
        Triple("年终奖", "奖金", TxKind.INCOME),
        Triple("报销", "报销", TxKind.INCOME),
        Triple("退款", "退款", TxKind.INCOME),
        Triple("退税", "退款", TxKind.INCOME),
    )

    fun merchantRules(categoryIdOf: (String, TxKind) -> Long?): List<MerchantRule> =
        keywordRules.mapNotNull { (keyword, categoryName, kind) ->
            categoryIdOf(categoryName, kind)?.let { id ->
                MerchantRule(keyword = keyword, categoryId = id, hits = 1, builtin = true)
            }
        }
}
