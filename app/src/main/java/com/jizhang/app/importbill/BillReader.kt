package com.jizhang.app.importbill

import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.exception.ZipException
import java.io.ByteArrayOutputStream
import java.io.InputStream

/** 解压需要密码时抛出。 */
class PasswordRequiredException : Exception("需要解压密码")

/** 密码错误。 */
class WrongPasswordException : Exception("解压密码错误")

/**
 * 读取账单文件内容。
 *
 * 微信导出的账单是加密 ZIP（解压码在邮件里），也可能是用户自己解压出的 CSV。
 * 编码上：带 BOM 的按 UTF-8，否则微信默认 GBK，再回退 UTF-8。
 */
object BillReader {

    private const val MAX_SIZE = 32 * 1024 * 1024 // 32MB，防异常文件

    /**
     * 从字节流读取账单文本。
     *
     * @param fileName 文件名，用于判断是 zip 还是纯文本
     * @param password ZIP 解压码，非 ZIP 文件时可忽略
     */
    fun read(bytes: ByteArray, fileName: String, password: String? = null): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".zip") -> readZip(bytes, password)
            lower.endsWith(".csv") || lower.endsWith(".txt") -> decode(bytes)
            else -> {
                // 未知扩展名：先看 ZIP 魔数，否则当文本
                if (isZip(bytes)) readZip(bytes, password) else decode(bytes)
            }
        }
    }

    fun read(input: InputStream, fileName: String, password: String? = null): String {
        val bytes = input.use { readAll(it) }
        return read(bytes, fileName, password)
    }

    /** ZIP 魔数：PK\x03\x04。 */
    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            (bytes[2] == 0x03.toByte() || bytes[2] == 0x05.toByte() || bytes[2] == 0x07.toByte())

    private fun readZip(bytes: ByteArray, password: String?): String {
        val temp = java.io.File.createTempFile("bill", ".zip")
        try {
            temp.writeBytes(bytes)
            val zip = ZipFile(temp, password?.toCharArray())
            if (zip.isEncrypted && password.isNullOrEmpty()) {
                throw PasswordRequiredException()
            }
            val entry = zip.fileHeaders
                .firstOrNull { header ->
                    val name = header.fileName.lowercase()
                    !header.isDirectory && (name.endsWith(".csv") || name.endsWith(".txt"))
                }
                ?: throw IllegalStateException("压缩包内没有 CSV 文件")

            val out = ByteArrayOutputStream()
            try {
                zip.getInputStream(entry).use { input ->
                    copyLimited(input, out)
                }
            } catch (e: ZipException) {
                val message = e.message.orEmpty()
                if (message.contains("password", ignoreCase = true) ||
                    message.contains("Wrong Password", ignoreCase = true)
                ) {
                    throw WrongPasswordException()
                }
                throw e
            }
            return decode(out.toByteArray())
        } finally {
            temp.delete()
        }
    }

    private fun copyLimited(input: InputStream, out: ByteArrayOutputStream) {
        val buffer = ByteArray(64 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            total += read
            if (total > MAX_SIZE) throw IllegalStateException("文件过大")
            out.write(buffer, 0, read)
        }
    }

    private fun readAll(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        copyLimited(input, out)
        return out.toByteArray()
    }

    /**
     * 解码文本：BOM → UTF-8；否则先试 GBK（微信默认），
     * 若解码出大量替换字符再回退 UTF-8。
     */
    fun decode(bytes: ByteArray): String {
        // UTF-8 BOM
        if (bytes.size >= 3 &&
            bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()
        ) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }

        val gbkText = runCatching { String(bytes, charset("GBK")) }.getOrNull()
        if (gbkText != null && gbkText.count { it == '\uFFFD' } == 0) {
            // GBK 解码干净，且内容里应当能看出账单特征
            return gbkText
        }

        return String(bytes, Charsets.UTF_8)
    }
}
