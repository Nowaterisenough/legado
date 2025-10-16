package io.legado.app.ui.main.bookshelf.style1.books

import android.content.Context
import android.os.Bundle
import android.view.ViewGroup
import io.legado.app.base.adapter.ItemViewHolder
import io.legado.app.data.entities.Book
import io.legado.app.databinding.ItemBookshelfGridBinding
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfig
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

    private fun upRefresh(binding: ItemBookshelfGridBinding, item: Book) {
        if (!item.isLocal && callBack.isUpdate(item.bookUrl)) {
            binding.bvUnread.invisible()
            binding.rlLoading.visible()
        } else {
            binding.rlLoading.inVisible()
            if (AppConfig.showUnread) {
                // 显示章节进度: 当前章节/总章节
                val currentChapter = item.durChapterIndex + 1 // 索引从0开始，显示从1开始
                val totalChapter = item.totalChapterNum
                if (totalChapter > 0) {
                    binding.bvUnread.text = "$currentChapter/$totalChapter"
                    binding.bvUnread.visible()
                } else {
                    binding.bvUnread.invisible()
                }
                binding.bvUnread.setHighlight(item.lastCheckCount > 0)
            } else {
                binding.bvUnread.invisible()
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