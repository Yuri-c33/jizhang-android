package com.jizhang.app.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.jizhang.app.R
import com.jizhang.app.data.db.entity.Category
import com.jizhang.app.data.db.entity.TxKind
import com.jizhang.app.data.repo.DeleteResult
import com.jizhang.app.databinding.ActivityCategoryManageBinding
import com.jizhang.app.databinding.ItemCategoryManageBinding
import com.jizhang.app.ui.common.EmojiPicker
import com.jizhang.app.ui.common.CategoryIcons
import com.jizhang.app.ui.common.appContainer
import com.jizhang.app.ui.common.snack
import kotlinx.coroutines.launch

/** 分类管理：支出/收入分组显示，可增删改。 */
class CategoryManageActivity : com.jizhang.app.ui.common.ThemedActivity() {

    private lateinit var binding: ActivityCategoryManageBinding
    private lateinit var adapter: CategoryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCategoryManageBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.toolbar.setNavigationOnClickListener { finish() }
        binding.addExpenseButton.setOnClickListener { showEditor(null, TxKind.EXPENSE) }
        binding.addIncomeButton.setOnClickListener { showEditor(null, TxKind.INCOME) }

        adapter = CategoryAdapter(
            onEdit = { showEditor(it, it.kind) },
            onDelete = { confirmDelete(it) },
        )
        binding.list.layoutManager = LinearLayoutManager(this)
        binding.list.adapter = adapter

        lifecycleScope.launch {
            appContainer.categoryRepo.observeAll().collect { list -> adapter.submitList(list) }
        }
    }

    private fun showEditor(existing: Category?, kind: TxKind) {
        // 用数组持有，便于在对话框回调里更新
        val emojiHolder = arrayOf(existing?.emoji ?: "📦")
        var saving = false

        val layout = TextInputLayout(this).apply {
            hint = getString(R.string.category_name)
            setPadding(48, 24, 48, 0)
        }
        val input = TextInputEditText(this).apply {
            setText(existing?.name.orEmpty())
            maxLines = 1
        }
        layout.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing == null) R.string.category_add else R.string.action_edit)
            .setView(layout)
            .setPositiveButton(R.string.action_save, null)
            .setNeutralButton(R.string.category_emoji, null)
            .setNegativeButton(R.string.action_cancel, null)
            .create()

        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
                .setOnClickListener {
                    // 用 setOnShowListener 接管，避免点击后主对话框被默认行为关闭。
                    EmojiPicker.show(this, emojiHolder[0]) { picked ->
                        emojiHolder[0] = picked
                    }
                }
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener {
                    if (saving) return@setOnClickListener
                    val name = input.text?.toString().orEmpty().trim()
                    if (name.isEmpty()) {
                        binding.root.snack(getString(R.string.category_need_name))
                        return@setOnClickListener
                    }
                    saving = true
                    lifecycleScope.launch {
                        try {
                            val duplicate = appContainer.categoryRepo.findByName(name, kind)
                            if (duplicate != null && duplicate.id != existing?.id) {
                                binding.root.snack(getString(R.string.category_name_exists))
                                return@launch
                            }
                            if (existing == null) {
                                val count = appContainer.categoryRepo.listByKind(kind).size
                                appContainer.categoryRepo.add(
                                    Category(
                                        name = name,
                                        emoji = emojiHolder[0],
                                        kind = kind,
                                        sortOrder = count,
                                    ),
                                )
                            } else {
                                appContainer.categoryRepo.update(
                                    existing.copy(name = name, emoji = emojiHolder[0]),
                                )
                            }
                            binding.root.snack(getString(R.string.category_saved))
                            dialog.dismiss()
                        } finally {
                            saving = false
                        }
                    }
                }
        }
        dialog.show()
    }

    private fun confirmDelete(category: Category) {
        if (category.builtin) {
            binding.root.snack(getString(R.string.category_builtin_locked))
            return
        }
        MaterialAlertDialogBuilder(this)
            .setMessage(getString(R.string.category_delete_confirm, category.name))
            .setPositiveButton(R.string.action_delete) { _, _ ->
                lifecycleScope.launch {
                    when (val result = appContainer.categoryRepo.delete(category)) {
                        DeleteResult.Deleted -> {
                            binding.root.snack(getString(R.string.category_deleted))
                        }

                        DeleteResult.Builtin -> {
                            binding.root.snack(getString(R.string.category_builtin_locked))
                        }

                        DeleteResult.NotFound -> {
                            binding.root.snack(getString(R.string.delete_not_found))
                        }

                        is DeleteResult.Referenced -> {
                            binding.root.snack(
                                getString(R.string.category_delete_referenced, result.count),
                            )
                        }
                    }
                }
            }
            .setNegativeButton(R.string.action_cancel, null)
            .show()
    }

    private inner class CategoryAdapter(
        private val onEdit: (Category) -> Unit,
        private val onDelete: (Category) -> Unit,
    ) : ListAdapter<Category, CategoryHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CategoryHolder =
            CategoryHolder(
                ItemCategoryManageBinding.inflate(LayoutInflater.from(parent.context), parent, false),
            )

        override fun onBindViewHolder(holder: CategoryHolder, position: Int) {
            holder.bind(getItem(position), onEdit, onDelete)
        }
    }

    private class CategoryHolder(private val binding: ItemCategoryManageBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(category: Category, onEdit: (Category) -> Unit, onDelete: (Category) -> Unit) {
            binding.emojiView.setImageResource(CategoryIcons.of(category.name, category.emoji))
            binding.nameView.text = category.name
            binding.editButton.setOnClickListener { onEdit(category) }
            binding.deleteButton.setOnClickListener { onDelete(category) }
            binding.deleteButton.alpha = if (category.builtin) 0.35f else 1f
        }
    }

    private companion object {
        val DIFF = object : DiffUtil.ItemCallback<Category>() {
            override fun areItemsTheSame(oldItem: Category, newItem: Category) = oldItem.id == newItem.id

            override fun areContentsTheSame(oldItem: Category, newItem: Category) = oldItem == newItem
        }
    }
}
