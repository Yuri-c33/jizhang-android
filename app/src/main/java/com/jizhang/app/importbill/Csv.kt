package com.jizhang.app.importbill

/**
 * 轻量 CSV 解析器。
 *
 * 微信账单的商品名里常含逗号、引号，必须按 RFC4180 处理：
 * 字段可被双引号包裹，包裹内的逗号不分割，连续两个双引号表示一个双引号。
 */
object Csv {

    /**
     * 解析一行 CSV。返回字段列表。
     */
    fun parseLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < line.length) {
            val ch = line[i]
            when {
                inQuotes -> {
                    if (ch == '"') {
                        // 连续两个引号 → 转义成一个引号
                        if (i + 1 < line.length && line[i + 1] == '"') {
                            current.append('"')
                            i++
                        } else {
                            inQuotes = false
                        }
                    } else {
                        current.append(ch)
                    }
                }

                ch == '"' -> inQuotes = true
                ch == ',' -> {
                    fields += current.toString()
                    current.clear()
                }

                else -> current.append(ch)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    /**
     * 解析整个文本为行列表，自动跳过空行。
     */
    fun parse(text: String): List<List<String>> =
        text.lineSequence()
            .map { it.trimEnd('\r', '\n') }
            .filter { it.isNotBlank() }
            .map { parseLine(it) }
            .toList()

    /** 输出时转义字段。 */
    fun escapeField(value: String): String {
        val needsQuote = value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuote) "\"" + value.replace("\"", "\"\"") + "\"" else value
    }
}
