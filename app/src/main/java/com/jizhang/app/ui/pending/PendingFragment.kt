package com.jizhang.app.ui.pending

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.Confidence
import com.jizhang.app.data.db.entity.PendingItem
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.repo.ConfirmResult
import com.jizhang.app.data.repo.PendingConfirmation
import com.jizhang.app.databinding.FragmentPendingBinding
import com.jizhang.app.notify.NotifAccess
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.applyEmptyStateStyle
import com.jizhang.app.ui.common.snack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 待确认队列。识别出的微信付款/收款在这里等用户审核后入账。
 */
class PendingFragment : Fragment() {

    private var _binding: FragmentPendingBinding? = null
    private val binding get() = _binding!!

    private lateinit var adapter: PendingAdapter

    private var categories: List<Category> = emptyList()
    private var accounts: List<Account> = emptyList()
    private var items: List<PendingItem> = emptyList()
    private val busyIds = mutableSetOf<Long>()
    private var bulkConfirming = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        _binding = FragmentPendingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.applyEmptyStateStyle()

        adapter = PendingAdapter(
            onConfirm = { item -> confirm(item) },
            onIgnore = { item -> ignore(item) },
            onPickCategory = { item -> pickCategory(item) },
            onPickAccount = { item -> pickAccount(item) },
            onPickToAccount = { item -> pickToAccount(item) },
        )
        binding.list.layoutManager = LinearLayoutManager(requireContext())
        binding.list.adapter = adapter

        binding.enableNotifButton.setOnClickListener { NotifAccess.openSettings(requireContext()) }
        binding.confirmAllButton.setOnClickListener { confirmAllHighConfidence() }

        observe()
    }

    private fun observe() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    appContainer.categoryRepo.observeAll().collect { list ->
                        categories = list
                        adapter.submit(renderRows())
                    }
                }
                launch {
                    appContainer.accountRepo.observeActive().collect { list ->
                        accounts = list
                        adapter.submit(renderRows())
                    }
                }
                launch {
                    appContainer.pendingRepo.observePending().collect { list ->
                        items = list
                        adapter.submit(renderRows())
                        val empty = list.isEmpty()
                        binding.emptyView.visibility = if (empty) View.VISIBLE else View.GONE
                        binding.list.visibility = if (empty) View.GONE else View.VISIBLE
                        binding.confirmAllButton.visibility = if (empty) View.GONE else View.VISIBLE
                        binding.confirmAllButton.isEnabled = !empty && !bulkConfirming
                    }
                }
            }
        }
    }

    private fun renderRows(): List<PendingRow> = items.map { item ->
        PendingRow(
            item = item,
            categoryName = item.suggestedCategoryId
                ?.let { id -> categories.firstOrNull { it.id == id } }
                ?.name,
            accountName = item.suggestedAccountId
                ?.let { id -> accounts.firstOrNull { it.id == id } }
                ?.name
                ?: accounts.firstOrNull()?.name,
            toAccountName = item.suggestedToAccountId
                ?.let { id -> accounts.firstOrNull { it.id == id } }
                ?.name,
        )
    }

    /** 待确认条目 -> 账目的统一校验，单项与批量确认共用。 */
    private fun confirmation(item: PendingItem): PendingConfirmation.Result {
        val kind = item.kind ?: TxKind.EXPENSE
        return PendingConfirmation.build(
            item = item,
            fallbackCategoryId = categories.firstOrNull { it.kind == kind }?.id,
            fallbackAccountId = accounts.firstOrNull()?.id,
        )
    }

    private fun messageFor(result: PendingConfirmation.Result): String = getString(
        when (result) {
            PendingConfirmation.Result.MissingAmount -> R.string.pending_need_amount
            PendingConfirmation.Result.MissingAccount -> R.string.add_need_account
            PendingConfirmation.Result.MissingCategory -> R.string.add_need_category
            PendingConfirmation.Result.MissingToAccount -> R.string.add_need_to_account
            PendingConfirmation.Result.SameAccount -> R.string.add_same_account
            is PendingConfirmation.Result.Valid -> R.string.pending_confirm_invalid
        },
    )

    private fun confirm(item: PendingItem) {
        if (item.id in busyIds || bulkConfirming) return
        val confirmation = confirmation(item)
        if (confirmation !is PendingConfirmation.Result.Valid) {
            binding.root.snack(messageFor(confirmation))
            return
        }
        val tx = confirmation.transaction
        busyIds += item.id
        adapter.setBusyIds(busyIds)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                when (appContainer.pendingRepo.confirm(item, tx)) {
                    is ConfirmResult.Confirmed -> {
                        learnFrom(item, tx.categoryId)
                        binding.root.snack(getString(R.string.pending_confirmed))
                    }

                    ConfirmResult.AlreadyResolved -> {
                        binding.root.snack(getString(R.string.pending_already_resolved))
                    }

                    ConfirmResult.Duplicate -> {
                        binding.root.snack(getString(R.string.pending_duplicate))
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                binding.root.snack(getString(R.string.pending_action_failed))
            } finally {
                busyIds -= item.id
                adapter.setBusyIds(busyIds)
            }
        }
    }

    /** 记住「交易对方 → 分类」，下次自动推荐。 */
    private suspend fun learnFrom(item: PendingItem, categoryId: Long?) {
        val name = item.counterparty ?: return
        val cid = categoryId ?: return
        appContainer.categoryRepo.learnKeyword(name, cid)
    }

    private fun ignore(item: PendingItem) {
        if (item.id in busyIds || bulkConfirming) return
        busyIds += item.id
        adapter.setBusyIds(busyIds)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val ignored = appContainer.pendingRepo.ignore(item.id)
                binding.root.snack(
                    getString(if (ignored) R.string.pending_ignored else R.string.pending_already_resolved),
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                binding.root.snack(getString(R.string.pending_action_failed))
            } finally {
                busyIds -= item.id
                adapter.setBusyIds(busyIds)
            }
        }
    }

    /** 批量确认高置信度且金额有效的条目。 */
    private fun confirmAllHighConfidence() {
        if (bulkConfirming) return
        val targets = items.filter {
            it.confidence == Confidence.HIGH && it.cents != null && it.cents > 0
        }
        if (targets.isEmpty()) {
            binding.root.snack(getString(R.string.pending_need_amount))
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setMessage(getString(R.string.pending_confirm_all_question, targets.size))
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    bulkConfirming = true
                    binding.confirmAllButton.isEnabled = false
                    adapter.setBulkBusy(true)
                    var done = 0
                    var failed = 0
                    try {
                        targets.forEach { item ->
                            val confirmation = confirmation(item)
                            if (confirmation !is PendingConfirmation.Result.Valid) {
                                failed++
                                return@forEach
                            }
                            val tx = confirmation.transaction
                            busyIds += item.id
                            adapter.setBusyIds(busyIds)
                            try {
                                when (appContainer.pendingRepo.confirm(item, tx)) {
                                    is ConfirmResult.Confirmed -> {
                                        learnFrom(item, tx.categoryId)
                                        done++
                                    }

                                    // 已处理过、或同来源账目已存在：都算本次未入账
                                    ConfirmResult.AlreadyResolved, ConfirmResult.Duplicate -> failed++
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                failed++
                            } finally {
                                busyIds -= item.id
                            }
                        }
                    } finally {
                        bulkConfirming = false
                        adapter.setBulkBusy(false)
                        adapter.setBusyIds(busyIds)
                        binding.confirmAllButton.isEnabled = items.isNotEmpty()
                    }
                    binding.root.snack(getString(R.string.pending_bulk_result, done, failed))
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickCategory(item: PendingItem) {
        val kind = item.kind ?: TxKind.EXPENSE
        val options = categories.filter { it.kind == kind || kind == TxKind.TRANSFER }
        if (options.isEmpty()) return
        val labels = options.map { it.name }.toTypedArray()
        val current = options.indexOfFirst { it.id == item.suggestedCategoryId }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_label_category)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = options[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    val updated = runCatching {
                        appContainer.pendingRepo.setSuggestedCategory(item.id, picked.id)
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        binding.root.snack(getString(R.string.pending_action_failed))
                        return@launch
                    }
                    if (!updated) {
                        binding.root.snack(getString(R.string.pending_already_resolved))
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickAccount(item: PendingItem) {
        if (accounts.isEmpty()) return
        val labels = accounts.map { it.name }.toTypedArray()
        val current = accounts.indexOfFirst { it.id == item.suggestedAccountId }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_label_account)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = accounts[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    val updated = runCatching {
                        appContainer.pendingRepo.setSuggestedAccount(item.id, picked.id)
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        binding.root.snack(getString(R.string.pending_action_failed))
                        return@launch
                    }
                    if (!updated) {
                        binding.root.snack(getString(R.string.pending_already_resolved))
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickToAccount(item: PendingItem) {
        if (accounts.isEmpty()) return
        val fromId = item.suggestedAccountId ?: accounts.firstOrNull()?.id
        val options = accounts.filter { it.id != fromId }
        if (options.isEmpty()) {
            binding.root.snack(getString(R.string.pending_need_second_account))
            return
        }
        val labels = options.map { it.name }.toTypedArray()
        val current = options.indexOfFirst { it.id == item.suggestedToAccountId }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.add_label_to_account)
            .setSingleChoiceItems(labels, current) { dialog, which ->
                val picked = options[which]
                viewLifecycleOwner.lifecycleScope.launch {
                    val updated = runCatching {
                        appContainer.pendingRepo.setSuggestedToAccount(item.id, picked.id)
                    }.getOrElse {
                        if (it is CancellationException) throw it
                        binding.root.snack(getString(R.string.pending_action_failed))
                        return@launch
                    }
                    if (!updated) {
                        binding.root.snack(getString(R.string.pending_already_resolved))
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        binding.list.adapter = null
        _binding = null
    }
}

/** 待确认行渲染所需的数据。 */
data class PendingRow(
    val item: PendingItem,
    val categoryName: String?,
    val accountName: String?,
    val toAccountName: String?,
)
