package com.jizhang.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Account
import com.jizhang.app.data.db.entity.AccountType
import com.jizhang.app.data.repo.AccountBalance
import com.jizhang.app.data.repo.DeleteResult
import com.jizhang.app.databinding.ActivityAccountManageBinding
import com.jizhang.app.databinding.ItemAccountManageBinding
import com.jizhang.app.money.Money
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import kotlinx.coroutines.launch

/** 账户管理：显示各账户余额与净资产。 */
class AccountManageActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityAccountManageBinding
    private lateinit var adapter: AccountAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAccountManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.addButton.setOnClickListener { showEditor(null) }

        adapter = AccountAdapter(onEdit = { showEditor(it) }, onDelete = { confirmDelete(it) })
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        observe()
    }

    private fun observe() {
        lifecycleScope.launch {
            appContainer.accountRepo.observeActive().collect { list ->
                val balances = list.map { AccountBalance(it, appContainer.accountRepo.balanceOf(it)) }
                adapter.submitList(balances)

                val net = balances.sumOf { it.cents }
                binding.netWorthView.text = Money.formatCentsWithSymbol(net)
                binding.netWorthView.setTextColor(
                    getColor(if (net < 0) R.color.expense else R.color.on_surface),
                )
            }
        }
    }

    private fun showEditor(existing: Account?) {
        val types = AccountType.entries
        val typeHolder = arrayOf(existing?.type ?: AccountType.WECHAT)

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 0)
        }

        val nameLayout = TextInputLayout(this).apply { hint = getString(R.string.account_name) }
        val nameInput = TextInputEditText(this).apply {
            setText(existing?.name.orEmpty())
            maxLines = 1
        }
        nameLayout.addView(nameInput)
        form.addView(nameLayout)

        val initialLayout = TextInputLayout(this).apply {
            hint = getString(R.string.account_initial)
            helperText = getString(R.string.account_initial_desc)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            lp.topMargin = (resources.displayMetrics.density * 12).toInt()
            layoutParams = lp
        }
        val initialInput = TextInputEditText(this).apply {
            setText(Money.formatCents(existing?.initialCents ?: 0L))
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            maxLines = 1
        }
        initialLayout.addView(initialInput)
        form.addView(initialLayout)

        var saving = false
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.account_add else R.string.action_edit)
            .setView(form)
            .setPositiveButton(R.string.action_save, null)
            .setNeutralButton(R.string.account_type, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    val labels = types.map { it.label }.toTypedArray()
                    MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.account_type)
                        .setSingleChoiceItems(labels, types.indexOf(typeHolder[0])) { picker, which ->
                            typeHolder[0] = types[which]
                            picker.dismiss()
                        }
                        .setNegativeButton(R.string.action_cancel, null)
                        .show()
                }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    if (saving) return@setOnClickListener
                    val name = nameInput.text?.toString().orEmpty().trim()
                    if (name.isEmpty()) {
                        binding.root.snack(getString(R.string.account_need_name))
                        return@setOnClickListener
                    }
                    val initial = Money.parseYuanToCents(initialInput.text?.toString()) ?: 0L
                    saving = true
                    lifecycleScope.launch {
                        try {
                            if (existing == null) {
                                val count = appContainer.accountRepo.listAll().size
                                appContainer.accountRepo.add(
                                    Account(
                                        name = name,
                                        type = typeHolder[0],
                                        initialCents = initial,
                                        sortOrder = count,
                                    ),
                                )
                            } else {
                                appContainer.accountRepo.update(
                                    existing.copy(
                                        name = name,
                                        type = typeHolder[0],
                                        initialCents = initial,
                                    ),
                                )
                            }
                            binding.root.snack(getString(R.string.account_saved))
                            dialog.dismiss()
                        } finally {
                            saving = false
                        }
                    }
                }
        }
        dialog.show()
    }

    private fun confirmDelete(account: Account) {
        if (account.builtin) {
            binding.root.snack(getString(R.string.account_builtin_locked))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.account_delete_confirm, account.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    when (val result = appContainer.accountRepo.delete(account)) {
                        DeleteResult.Deleted -> {
                            binding.root.snack(getString(R.string.account_deleted))
                        }

                        DeleteResult.Builtin -> {
                            binding.root.snack(getString(R.string.account_builtin_locked))
                        }

                        DeleteResult.NotFound -> {
                            binding.root.snack(getString(R.string.delete_not_found))
                        }

                        is DeleteResult.Referenced -> {
                            binding.root.snack(
                                getString(R.string.account_delete_referenced, result.count),
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class AccountAdapter(
        private val onEdit: (Account) -> Unit,
        private val onDelete: (Account) -> Unit,
    ) : ListAdapter<AccountBalance, AccountHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AccountHolder =
            AccountHolder(
                ItemAccountManageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: AccountHolder, position: Int) {
            holder.bind(getItem(position), onEdit, onDelete)
        }
    }

    private class AccountHolder(private val binding: ItemAccountManageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(item: AccountBalance, onEdit: (Account) -> Unit, onDelete: (Account) -> Unit) {
            val account = item.account
            binding.initialView.text = account.name.take(1)
            binding.nameView.text = account.name
            binding.typeView.text = account.type.label
            binding.balanceView.text = Money.formatCentsWithSymbol(item.cents)
            binding.balanceView.setTextColor(
                binding.root.context.getColor(
                    if (item.cents < 0) R.color.expense else R.color.on_surface,
                ),
            )
            binding.editButton.setOnClickListener { onEdit(account) }
            binding.deleteButton.setOnClickListener { onDelete(account) }
            binding.deleteButton.alpha = if (account.builtin) 0.35f else 1f
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<AccountBalance>() {
            override fun areItemsTheSame(oldItem: AccountBalance, newItem: AccountBalance) =
                oldItem.account.id == newItem.account.id

            override fun areContentsTheSame(oldItem: AccountBalance, newItem: AccountBalance) =
                oldItem == newItem
        }
    }
}
