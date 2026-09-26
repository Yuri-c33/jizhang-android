package com.jizhang.app.ui.common

import androidx.annotation.DrawableRes
import com.jizhang.app.R

/**
 * 分类的显示图标。
 *
 * 数据库里的 emoji 字段保留不删（老数据与自定义分类仍然依赖它），
 * 但 MD3 界面不应该把彩色 emoji 直接当作功能图标使用：
 * 这里按分类名称/emoji 映射成统一的 Material Symbols 轮廓图标。
 */
object CategoryIcons {

    @DrawableRes
    fun of(name: String?, emoji: String?): Int {
        val key = (name.orEmpty() + emoji.orEmpty()).lowercase()
        return when {
            contains(key, "餐饮", "零食", "饮料", "food", "🍜", "🧋", "🍔", "🍕") ->
                R.drawable.ic_category_food
            contains(key, "交通", "出行", "打车", "公交", "地铁", "🚌", "🚕", "✈") ->
                R.drawable.ic_category_transport
            contains(key, "购物", "服饰", "美容", "🛍", "👕", "👗") ->
                R.drawable.ic_category_shopping
            contains(key, "缴费", "话费", "网费", "水电", "房租", "住房", "💡", "📱", "🏠") ->
                R.drawable.ic_category_home
            contains(key, "医疗", "健康", "药", "💊", "🏥") ->
                R.drawable.ic_category_health
            contains(key, "娱乐", "游戏", "电影", "旅行", "🎬", "🎮", "🎁", "✈") ->
                R.drawable.ic_category_entertainment
            contains(key, "学习", "教育", "书", "📚", "✏") ->
                R.drawable.ic_category_education
            contains(key, "工资", "奖金", "收入", "兼职", "报销", "退款", "红包", "💰", "🏆", "🧧", "💵") ->
                R.drawable.ic_category_income
            contains(key, "投资", "理财", "📈") ->
                R.drawable.ic_category_investment
            contains(key, "运动", "健身", "🏃") ->
                R.drawable.ic_category_fitness
            contains(key, "宠物", "🐾") ->
                R.drawable.ic_category_pet
            contains(key, "数码", "电器", "电脑", "💻") ->
                R.drawable.ic_category_devices
            contains(key, "烟酒", "🍷") ->
                R.drawable.ic_category_drink
            contains(key, "转账", "🔄") ->
                R.drawable.ic_category_transfer
            else -> R.drawable.ic_category_default
        }
    }

    private fun contains(haystack: String, vararg needles: String): Boolean =
        needles.any { haystack.contains(it) }
}
