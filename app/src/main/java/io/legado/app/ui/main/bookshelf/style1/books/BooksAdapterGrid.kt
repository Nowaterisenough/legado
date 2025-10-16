package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import io.legado.app.R
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
import io.legado.app.utils.getCompatColor
import io.legado.app.utils.invisible
import io.legado.app.utils.visible
import splitties.views.onLongClick

class BooksAdapterGrid(context: Context, private val callBack: CallBack) :
    BaseBooksAdapter<ItemBookshelfGridBinding>(context) {

    override fun getViewBinding(parent: ViewGroup): ItemBookshelfGridBinding {
        return ItemBookshelfGridBinding.inflate(inflater, parent, false)
    }

    override fun convert(
        holder: ItemViewHolder,
        binding: ItemBookshelfGridBinding,
        item: Book,
        payloads: MutableList<Any>
    ) = binding.run {
        if (payloads.isEmpty()) {
            tvName.text = item.name
            ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin)
            upRefresh(binding, item)
        } else {
            for (i in payloads.indices) {
                val bundle = payloads[i] as Bundle
                bundle.keySet().forEach {
                    when (it) {
                        "name" -> tvName.text = item.name
                        "cover" -> ivCover.load(item.getDisplayCover(), item.name, item.author, false, item.origin)
                        "refresh" -> upRefresh(binding, item)
                    }
                }
            }
        }
    }

    private fun getChapterString(item: Book): String {
        val durChapterIndex = item.durChapterIndex
        val totalChapterNum = item.totalChapterNum
        return when {
            totalChapterNum == 0 -> ""
            else -> "${durChapterIndex + 1}/$totalChapterNum"
        }
    }

    private fun upRefresh(binding: ItemBookshelfGridBinding, item: Book) {
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.tvUpdate.invisible()
            binding.tvProgress.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.inVisible()
            // 显示章节进度
            val chapterString = getChapterString(item)
            if (chapterString.isNotEmpty()) {
                binding.tvProgress.text = chapterString
                if (item.lastCheckCount > 0) {
                    binding.tvProgress.setTextColor(context.getCompatColor(R.color.md_white_1000))
                    binding.tvProgress.setBackgroundResource(R.drawable.bg_progress_badge)
                } else {
                    binding.tvProgress.setTextColor(context.getCompatColor(R.color.secondaryText))
                    binding.tvProgress.background = null
                }
                binding.tvProgress.visible()
            } else {
                binding.tvProgress.invisible()
            }
            // 显示更新标识
            if (item.lastCheckCount > 0) {
                binding.tvUpdate.visible()
            } else {
                binding.tvUpdate.invisible()
            }
        }
    }

    override fun registerListener(holder: ItemViewHolder, binding: ItemBookshelfGridBinding) {
        holder.itemView.apply {
            setOnClickListener {
                getItem(holder.layoutPosition)?.let {
                    callBack.open(it)
                }
            }

            onLongClick {
                getItem(holder.layoutPosition)?.let {
                    callBack.openBookInfo(it)
                }
            }
        }
    }
}