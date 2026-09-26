package com.jizhang.app.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jizhang.app.AppContainer
import com.jizhang.app.JizhangApp
import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.NotificationLog
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.PendingStatus
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 监听微信通知，识别付款/收款并生成待确认条目。
 *
 * 只在系统已授权时才会被调用。识别结果默认进「待确认」队列，
 * 用户可在设置里开启高置信度自动入账。
 */
class WeChatNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val appContainer: AppContainer
        get() = (application as JizhangApp).container

    override fun onListenerConnected() {
        super.onListenerConnected()
        Log.i(TAG, "通知监听已连接")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        Log.i(TAG, "通知监听已断开")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val posted = sbn ?: return
        if (posted.packageName != WECHAT_PACKAGE) return

        val notification: Notification = posted.notification ?: return
        val extras = notification.extras ?: return

        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val text = buildText(extras)
        if (title.isNullOrBlank() && text.isNullOrBlank()) return

        scope.launch {
            runCatching {
                handle(
                    title = title,
                    text = text,
                    postedAt = posted.postTime,
                    tag = posted.tag,
                    id = posted.id,
                    key = posted.key,
                )
            }
                .onFailure { Log.w(TAG, "处理通知失败", it) }
        }
    }

    /** 把通知里的各个文本字段拼成全文。 */
    private fun buildText(extras: android.os.Bundle): String {
        val parts = mutableListOf<String>()

        extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()?.let { parts += it }
        extras.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()?.let { parts += it }
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.forEach { line ->
            line?.toString()?.let { parts += it }
        }

        // 去重后拼接：微信常把同一句话放在多个字段里
        return parts
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" ")
    }

    private suspend fun handle(
        title: String?,
        text: String?,
        postedAt: Long,
        tag: String?,
        id: Int,
        key: String?,
    ) {
        val parsed = WeChatNotificationParser.parse(title, text)

        // 无论识别成功与否都记日志，方便诊断页排查。
        // v1.4 曾在这里加过一道「只记支付类来源」的闸（isDiagnosticCandidate），
        // v1.9 按用户要求撤掉、回到最初行为：所有微信通知都读、都留样本。
        // 代价是私聊原文也会入库（DAO 里有条数上限，超出即淘汰最旧的），
        // 且真正的支付通知可能被聊天刷出上限——诊断页价值高于这点噪音。
        appContainer.pendingRepo.logNotification(
            NotificationLog(
                packageName = WECHAT_PACKAGE,
                title = title,
                text = text,
                capturedAt = postedAt,
                recognized = parsed != null,
                matchedRule = parsed?.ruleName,
            ),
        )

        val result = parsed ?: return

        // 同一通知可能被系统多次推送（通知更新），用指纹去重
        val fingerprint = WeChatNotificationParser.fingerprint(
            title = title,
            text = text,
            postedAt = postedAt,
            tag = tag,
            id = id,
            key = key,
        )

        val settings = appContainer.settings
        val account = resolveAccount()
        val categories = appContainer.categoryRepo.listAll()

        val targetKind = if (result.kind == TxKind.INCOME) TxKind.INCOME else TxKind.EXPENSE
        val textForCategory = listOfNotNull(result.counterparty, result.item, title, text)
            .joinToString(" ")
        val suggestedCategory = appContainer.categoryRepo.suggestCategoryId(textForCategory, targetKind)
            ?: categories.firstOrNull {
                it.kind == targetKind && (it.name == "其他支出" || it.name == "其他收入")
            }?.id

        val minConfidence = when (settings.autoConfirmMinConfidence) {
            "LOW" -> Confidence.LOW
            "MEDIUM" -> Confidence.MEDIUM
            else -> Confidence.HIGH
        }

        // 自动入账的四个前提：账户与分类都已确定（否则账目会缺归属，和手工确认
        // 时的 MissingAccount/MissingCategory 保持一致）、转账类不自动入账
        // ——转账涉及两个账户，必须由用户确认对方账户，且必须来自**支付来源**通知。
        // 聊天里的一句话（「我明天转给你 200」）哪怕命中规则也只能进待确认队列：
        // 一旦自动入账，用户的账本就会被聊天内容污染，而且是静默污染。
        val canAutoConfirm = account != null &&
            suggestedCategory != null &&
            settings.autoConfirmNotifications &&
            result.scope == NotifyScope.PAYMENT &&
            result.kind != TxKind.TRANSFER &&
            rank(result.confidence) >= rank(minConfidence)

        val pendingItem = PendingItem(
            rawTitle = title,
            rawText = text,
            postedAt = postedAt,
            fingerprint = fingerprint,
            kind = result.kind,
            cents = result.cents,
            counterparty = result.counterparty,
            item = result.item,
            matchedRule = result.ruleName,
            confidence = result.confidence,
            suggestedCategoryId = suggestedCategory,
            suggestedAccountId = account?.id,
        )

        val autoConfirmed = canAutoConfirm && appContainer.pendingRepo.autoConfirm(
            item = pendingItem.copy(status = PendingStatus.CONFIRMED),
            tx = Transaction(
                kind = result.kind,
                cents = result.cents,
                categoryId = suggestedCategory,
                accountId = account.id,
                occurredAt = postedAt,
                counterparty = result.counterparty,
                item = result.item,
                source = TxSource.NOTIFICATION,
                sourceRef = "notify:$fingerprint",
                confirmed = true,
            ),
        ) != null

        // 没自动入账就落队列；重复推送与同来源账目已存在都会被去重逻辑再挡一次
        if (!autoConfirmed) {
            appContainer.pendingRepo.enqueueNotification(pendingItem)
        }
    }

    private suspend fun resolveAccount() = run {
        val preferred = appContainer.settings.defaultAccountId
        if (preferred != 0L) {
            appContainer.accountRepo.findById(preferred)?.let { return@run it }
        }
        appContainer.accountRepo.defaultAccount()
    }

    private fun rank(confidence: Confidence): Int = when (confidence) {
        Confidence.LOW -> 0
        Confidence.MEDIUM -> 1
        Confidence.HIGH -> 2
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
        private const val TAG = "WeChatNotifListener"
    }
}
