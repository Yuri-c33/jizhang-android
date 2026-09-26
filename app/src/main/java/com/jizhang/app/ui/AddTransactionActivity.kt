package com.jizhang.app.ui

import android.app.DatePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.Transaction
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.db.entity.TxSource
import com.jizhang.app.databinding.ActivityAddTransactionBinding
import com.jizhang.app.money.Money
import com.jizhang.app.ui.common.PickerAdapter
import com.jizhang.app.ui.common.PickerItem
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import com.jizhang.app.util.Dates
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 记一笔 / 编辑账目。
 */
class AddTransactionActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityAddTransactionBinding

    private var editingId: Long = 0

    private var kind: TxKind = TxKind.EXPENSE
    private var amountInput: String = ""
    private var selectedCategoryId: Long? = null
    private val categorySelections = mutableMapOf<TxKind, Long>()
    private var categoryLoadVersion = 0
    private var accountId: Long? = null
    private var toAccountId: Long? = null
    private var note: String = ""
    private var occurredDate: LocalDate = LocalDate.now()
    private var occurredTime: LocalTime = LocalTime.now().withSecond(0).withNano(0)

    private var accounts: List<Account> = emptyList()
    private lateinit var categoryAdapter: PickerAdapter
    private var saving = false
    private var accountsLoaded = false
    private var pendingAccountRemovedNotice = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAddTransactionBinding.inflate(layoutInflater)
        setContentView(binding.root)

        editingId = intent.getLongExtra(EXTRA_ID, 0L)

        applySystemBarInsets()
        setupTabs()
        setupCategoryGrid()
        setupKeypad()
        setupInfoButtons()
        observeData()
    }

    /**
     * 让顶栏与键盘避开系统栏。
     *
     * 如果窗口已经是边到边绘制，这里会拿到真实的系统栏高度并把内容推上来；
     * 如果系统已经把内容限制在系统栏以内，拿到的就是 0，不会重复留白。
     * 之前键盘最后一行（0 / . / 保存）在某些机型上会压在导航栏下面，
     * 看起来就像「没有 0 这个键」。
     */
    private fun applySystemBarInsets() {
        val baseTopPadding = binding.topBar.paddingTop
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            // 顶栏避开状态栏，键盘避开导航栏；页面底色仍然铺满整屏。
            // 加回 XML 定义的间距，避免 Android 10+ 上 insets 覆盖后顶栏贴住状态栏。
            binding.topBar.updatePadding(top = bars.top + baseTopPadding)
            binding.keypadPanel.updatePadding(
                bottom = bars.bottom +
                    resources.getDimensionPixelSize(R.dimen.keypad_padding_bottom),
            )
            WindowInsetsCompat.CONSUMED
        }
    }

    private fun setupTabs() {
        val kinds = listOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER)
        val kindButtons = listOf(binding.kindExpense, binding.kindIncome, binding.kindTransfer)

        binding.kindTabs.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                val index = kindButtons.indexOfFirst { it.id == checkedId }
                if (index < 0) return@addOnButtonCheckedListener
                val nextKind = kinds[index]
                if (nextKind == kind) return@addOnButtonCheckedListener

                // 每个方向分别记住自己的分类，收入/支出来回切换时不会互相清空。
                rememberCategorySelection()
                kind = nextKind
                selectedCategoryId = categorySelections[kind]
                applyKindUi()
                binding.accountButton.text = accountLabel()
                reloadCategories(resetScroll = true)
                updateSaveState()
            }
        }

        binding.closeButton.setOnClickListener { finish() }
        binding.deleteButton.setOnClickListener { confirmDelete() }
    }

    private fun setupCategoryGrid() {
        categoryAdapter = PickerAdapter { item ->
            if (item.id == MANAGE_ID) {
                startActivity(Intent(this, CategoryManageActivity::class.java))
            } else {
                selectedCategoryId = item.id
                categorySelections[kind] = item.id
                appContainer.settings.lastCategoryId = item.id
                categoryAdapter.selectId(item.id)
                updateSaveState()
            }
        }
        binding.categoryGrid.layoutManager = GridLayoutManager(this, 4)
        binding.categoryGrid.itemAnimator = null
        binding.categoryGrid.adapter = categoryAdapter
    }

    private fun rememberCategorySelection() {
        if (kind == TxKind.TRANSFER) return
        selectedCategoryId?.let { categorySelections[kind] = it }
            ?: categorySelections.remove(kind)
    }

    private fun reloadCategories(resetScroll: Boolean = false) {
        val requestedKind = kind
        val loadVersion = ++categoryLoadVersion

        lifecycleScope.launch {
            val list: List<Category> = when (requestedKind) {
                TxKind.INCOME -> appContainer.categoryRepo.listByKind(TxKind.INCOME)
                TxKind.EXPENSE -> appContainer.categoryRepo.listByKind(TxKind.EXPENSE)
                TxKind.TRANSFER, TxKind.NEUTRAL -> emptyList()
            }

            // 快速切换方向时只接受最后一次请求，避免旧结果覆盖当前页面。
            if (requestedKind != kind || loadVersion != categoryLoadVersion) return@launch

            var selected = selectedCategoryId
            if (selected != null && list.none { it.id == selected }) {
                val invalidId = selected
                val existing = appContainer.categoryRepo.findById(invalidId)
                if (existing == null || existing.kind == requestedKind) {
                    selected = null
                    selectedCategoryId = null
                    categorySelections.remove(requestedKind)
                    if (appContainer.settings.lastCategoryId == invalidId) {
                        appContainer.settings.lastCategoryId = 0L
                    }
                    binding.root.snack(getString(R.string.add_category_removed))
                } else {
                    // 分类仍然存在，只是属于另一个方向；留给切换方向时恢复。
                    categorySelections[existing.kind] = existing.id
                    selected = null
                    selectedCategoryId = null
                }
            } else if (selected != null) {
                categorySelections[requestedKind] = selected
            }

            val items = list.map { PickerItem(it.id, it.emoji, it.name) } +
                PickerItem(MANAGE_ID, "➕", getString(R.string.add_manage_category))

            categoryAdapter.selectId(selected ?: -1L)
            categoryAdapter.submitList(items) {
                if (requestedKind != kind || loadVersion != categoryLoadVersion) return@submitList
                binding.categoryGrid.post {
                    if (requestedKind != kind || loadVersion != categoryLoadVersion) return@post
                    // 管理分类入口的 id 也是负数，不能把它当成当前选中项。
                    val selectedPosition = if (categoryAdapter.selectedId >= 0) {
                        items.indexOfFirst { it.id == categoryAdapter.selectedId }
                    } else {
                        -1
                    }
                    val manager = binding.categoryGrid.layoutManager as? GridLayoutManager
                    if (selectedPosition >= 0) {
                        manager?.scrollToPositionWithOffset(selectedPosition, 0)
                    } else if (resetScroll) {
                        manager?.scrollToPosition(0)
                    }
                }
            }
            binding.categoryEmptyHint.visibility =
                if (requestedKind != TxKind.TRANSFER && list.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    /** 类型决定页面上哪些区块可见：转账没有分类，改为提示选择转入账户。 */
    private fun applyKindUi() {
        val isTransfer = kind == TxKind.TRANSFER
        binding.amountLabel.setText(kind.label)
        binding.toAccountButton.visibility = if (isTransfer) View.VISIBLE else View.GONE
        binding.categoryGrid.visibility = if (isTransfer) View.GONE else View.VISIBLE
        binding.transferHint.visibility = if (isTransfer) View.VISIBLE else View.GONE
        binding.categoryEmptyHint.visibility = View.GONE
    }

    private fun setupKeypad() {
        listOf(
            binding.key1 to "1", binding.key2 to "2", binding.key3 to "3",
            binding.key4 to "4", binding.key5 to "5", binding.key6 to "6",
            binding.key7 to "7", binding.key8 to "8", binding.key9 to "9",
            binding.key0 to "0",
            binding.keyDot to ".",
        ).forEach { (button, key) -> button.setOnClickListener { onKey(key) } }

        binding.keyDel.setOnClickListener { onKey("del") }
        binding.keyClear.setOnClickListener {
            amountInput = ""
            renderAmount()
        }
        binding.keySave.setOnClickListener { save() }
        binding.keyDate.setOnClickListener { pickDate() }
    }

    private fun setupInfoButtons() {
        binding.dateButton.setOnClickListener { pickDate() }
        binding.noteButton.setOnClickListener { editNote() }
        binding.accountButton.setOnClickListener { pickAccount(forTransferTarget = false) }
        binding.toAccountButton.setOnClickListener { pickAccount(forTransferTarget = true) }
    }

    private fun onKey(key: String) {
        amountInput = Money.appendKey(amountInput, key)
        renderAmount()
    }

    private fun renderAmount() {
        binding.amountView.text = if (amountInput.isEmpty()) "0.00" else amountInput
        updateSaveState()
    }

    private fun observeData() {
        lifecycleScope.launch {
            appContainer.accountRepo.observeActive().collectLatest { list ->
                accounts = list
                if (accountsLoaded && accountId != null && list.none { it.id == accountId }) {
                    pendingAccountRemovedNotice = true
                }
                if (accountId == null || list.none { it.id == accountId }) {
                    accountId = resolveDefaultAccount(list)
                }
                if (toAccountId == null || list.none { it.id == toAccountId }) {
                    toAccountId = list.firstOrNull { it.id != accountId }?.id
                }
                binding.accountButton.text = accountLabel()
                renderToAccount()
                if (pendingAccountRemovedNotice) {
                    pendingAccountRemovedNotice = false
                    binding.root.snack(getString(R.string.add_account_removed))
                }
                accountsLoaded = true
            }
        }

        if (editingId != 0L) {
            lifecycleScope.launch {
                appContainer.transactionRepo.findById(editingId)?.let { prefill(it) }
            }
        } else {
            amountInput = ""
            renderAmount()
            binding.dateButton.text = Dates.sectionTitle(occurredDate)
            binding.keyDate.text = Dates.sectionTitle(occurredDate)
            binding.deleteButton.visibility = View.GONE
            binding.noteButton.text = getString(R.string.add_hint_note)

            if (appContainer.settings.rememberLastSelection) {
                val last = appContainer.settings.lastCategoryId
                if (last != 0L) selectedCategoryId = last
            }
            reloadCategories(resetScroll = true)
        }
    }

    private suspend fun resolveDefaultAccount(list: List<Account>): Long? {
        val preferred = appContainer.settings.defaultAccountId
        if (preferred != 0L && list.any { it.id == preferred }) return preferred
        return list.firstOrNull()?.id
    }

    private fun prefill(tx: Transaction) {
        kind = tx.kind
        val kinds = listOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER)
        val kindIndex = kinds.indexOf(kind)
        if (kindIndex >= 0) {
            binding.kindTabs.check(
                listOf(binding.kindExpense, binding.kindIncome, binding.kindTransfer)[kindIndex].id,
            )
        }
        applyKindUi()

        amountInput = Money.formatCents(tx.cents)
        renderAmount()

        selectedCategoryId = tx.categoryId
        tx.categoryId?.let { categorySelections[tx.kind] = it }
        accountId = tx.accountId
        toAccountId = tx.toAccountId
        note = tx.note.orEmpty()
        if (note.isNotBlank()) binding.noteButton.text = note

        val dt = Dates.fromEpochMillis(tx.occurredAt)
        occurredDate = dt.toLocalDate()
        occurredTime = dt.toLocalTime().withSecond(0).withNano(0)
        binding.dateButton.text = Dates.sectionTitle(occurredDate)
        binding.keyDate.text = Dates.sectionTitle(occurredDate)

        binding.accountButton.text = accountLabel()
        renderToAccount()
        binding.deleteButton.visibility = View.VISIBLE
        reloadCategories(resetScroll = true)
    }

    private fun accountLabel(): String {
        val id = accountId ?: return getString(R.string.add_need_account)
        return accounts.firstOrNull { it.id == id }?.name ?: getString(R.string.add_need_account)
    }

    private fun renderToAccount() {
        if (kind != TxKind.TRANSFER) return
        val name = accounts.firstOrNull { it.id == toAccountId }?.name
        binding.toAccountButton.text = if (name == null) {
            getString(R.string.add_label_to_account)
        } else {
            getString(R.string.add_label_to_account) + "：" + name
        }
    }

    private fun updateSaveState() {
        if (saving) return
        val hasAmount = Money.keyboardToCents(amountInput) > 0
        val needsCategory = kind != TxKind.TRANSFER
        binding.keySave.isEnabled = hasAmount && (!needsCategory || selectedCategoryId != null)
        binding.keySave.alpha = if (binding.keySave.isEnabled) 1f else 0.6f
    }

    private fun pickDate() {
        DatePickerDialog(
            this,
            { _, year, month, day ->
                occurredDate = LocalDate.of(year, month + 1, day)
                binding.dateButton.text = Dates.sectionTitle(occurredDate)
                binding.keyDate.text = Dates.sectionTitle(occurredDate)
            },
            occurredDate.year,
            occurredDate.monthValue - 1,
            occurredDate.dayOfMonth,
        ).show()
    }

    private fun editNote() {
        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.add_label_note)
            setPadding(48, 24, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            setText(note)
            maxLines = 2
        }
        layout.addView(input)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.add_label_note)
            .setView(layout)
            .setPositiveButton(R.string.action_confirm) { _, _ ->
                note = input.text?.toString().orEmpty().trim()
                binding.noteButton.text =
                    if (note.isBlank()) getString(R.string.add_hint_note) else note
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun pickAccount(forTransferTarget: Boolean) {
        if (accounts.isEmpty()) {
            startActivity(Intent(this, AccountManageActivity::class.java))
            return
        }
        val names = accounts.map { "${it.name}（${it.type.label}）" }.toTypedArray()
        val current = if (forTransferTarget) toAccountId else accountId
        val checked = accounts.indexOfFirst { it.id == current }

        MaterialAlertDialogBuilder(this)
            .setTitle(if (forTransferTarget) R.string.add_label_to_account else R.string.add_label_account)
            .setSingleChoiceItems(names, checked) { dialog, which ->
                val picked = accounts[which].id
                if (forTransferTarget) {
                    toAccountId = picked
                    renderToAccount()
                } else {
                    accountId = picked
                    binding.accountButton.text = accountLabel()
                }
                dialog.dismiss()
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private fun save() {
        if (saving) return
        val cents = Money.keyboardToCents(amountInput)
        if (cents <= 0) {
            binding.root.snack(getString(R.string.add_need_amount))
            return
        }
        if (kind != TxKind.TRANSFER && selectedCategoryId == null) {
            binding.root.snack(getString(R.string.add_need_category))
            return
        }
        if (accountId == null) {
            binding.root.snack(getString(R.string.add_need_account))
            return
        }
        if (kind == TxKind.TRANSFER) {
            if (toAccountId == null) {
                binding.root.snack(getString(R.string.add_need_to_account))
                return
            }
            if (toAccountId == accountId) {
                binding.root.snack(getString(R.string.add_same_account))
                return
            }
        }

        val occurredAt = LocalDateTime.of(occurredDate, occurredTime)
            .atZone(Dates.ZONE).toInstant().toEpochMilli()

        saving = true
        binding.keySave.isEnabled = false
        lifecycleScope.launch {
            try {
                // 管理页可能刚删除或归档了当前选择，保存前以数据库状态为准。
                val fromAccount = accountId?.let { appContainer.accountRepo.findById(it) }
                if (fromAccount == null || fromAccount.archived) {
                    accountId = null
                    binding.accountButton.text = accountLabel()
                    binding.root.snack(getString(R.string.add_account_removed))
                    return@launch
                }

                if (kind == TxKind.TRANSFER) {
                    val toAccount = toAccountId?.let { appContainer.accountRepo.findById(it) }
                    if (toAccount == null || toAccount.archived) {
                        toAccountId = null
                        renderToAccount()
                        binding.root.snack(getString(R.string.add_need_to_account))
                        return@launch
                    }
                    if (toAccount.id == fromAccount.id) {
                        binding.root.snack(getString(R.string.add_same_account))
                        return@launch
                    }
                } else {
                    val category = selectedCategoryId?.let { appContainer.categoryRepo.findById(it) }
                    if (category == null || category.kind != kind) {
                        val staleId = selectedCategoryId
                        selectedCategoryId = null
                        categorySelections.remove(kind)
                        if (staleId != null && appContainer.settings.lastCategoryId == staleId) {
                            appContainer.settings.lastCategoryId = 0L
                        }
                        reloadCategories()
                        binding.root.snack(getString(R.string.add_category_removed))
                        return@launch
                    }
                }

                if (editingId == 0L) {
                    appContainer.transactionRepo.add(
                        Transaction(
                            kind = kind,
                            cents = cents,
                            categoryId = if (kind == TxKind.TRANSFER) null else selectedCategoryId,
                            accountId = fromAccount.id,
                            toAccountId = if (kind == TxKind.TRANSFER) toAccountId else null,
                            occurredAt = occurredAt,
                            note = note.takeIf { it.isNotBlank() },
                            source = TxSource.MANUAL,
                            confirmed = true,
                        ),
                    )
                } else {
                    val original = appContainer.transactionRepo.findById(editingId)
                    if (original == null) {
                        binding.root.snack(getString(R.string.add_transaction_removed))
                        finish()
                        return@launch
                    }
                    appContainer.transactionRepo.update(
                        original.copy(
                            kind = kind,
                            cents = cents,
                            categoryId = if (kind == TxKind.TRANSFER) null else selectedCategoryId,
                            accountId = fromAccount.id,
                            toAccountId = if (kind == TxKind.TRANSFER) toAccountId else null,
                            occurredAt = occurredAt,
                            note = note.takeIf { it.isNotBlank() },
                        ),
                    )
                }
                appContainer.settings.lastCategoryId = selectedCategoryId ?: 0L
                finish()
            } finally {
                saving = false
                updateSaveState()
            }
        }
    }

    private fun confirmDelete() {
        MaterialAlertDialogBuilder(this)
            .setMessage(R.string.add_delete_confirm)
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    appContainer.transactionRepo.deleteById(editingId)
                    finish()
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    override fun onResume() {
        super.onResume()
        // 分类/账户可能在管理页被改动
        if (::categoryAdapter.isInitialized) reloadCategories()
        if (::binding.isInitialized) {
            if (accountId != null && accounts.none { it.id == accountId }) {
                accountId = null
                binding.root.snack(getString(R.string.add_account_removed))
            }
            if (toAccountId != null && accounts.none { it.id == toAccountId }) {
                toAccountId = null
            }
            binding.accountButton.text = accountLabel()
            renderToAccount()
            updateSaveState()
        }
    }

    companion object {
        private const val EXTRA_ID = "tx_id"
        private const val MANAGE_ID = -1L

        fun editIntent(context: Context, txId: Long): Intent =
            Intent(context, AddTransactionActivity::class.java).putExtra(EXTRA_ID, txId)
    }
}
