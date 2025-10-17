package io.legado.app.ui.main.bookshelf.style2

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.SearchView
import androidx.core.view.isGone
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.constant.PreferKey
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBookshelf2Binding
import io.legado.app.help.AppWebDav
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.group.GroupEditDialog
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.book.search.SearchActivity
import io.legado.app.ui.main.bookshelf.BaseBookshelfFragment
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.observeEvent
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.startActivity
import io.legado.app.utils.startActivityForBook
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BookshelfFragment2() : BaseBookshelfFragment(R.layout.fragment_bookshelf2),
    SearchView.OnQueryTextListener,
    BaseBooksAdapter.CallBack {

    constructor(position: Int) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBookshelf2Binding::bind)
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout == 0) {
            BooksAdapterList(requireContext(), this)
        } else {
            BooksAdapterGrid(requireContext(), this)
        }
    }
    private var bookGroups: List<BookGroup> = emptyList()
    private var booksFlowJob: Job? = null
    override var groupId = BookGroup.IdRoot
    override var books: List<Book> = emptyList()
    private var enableRefresh = true

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        setSupportToolbar(binding.titleBar.toolbar)
        initRecyclerView()
        initBookGroupData()
        initBooksData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        binding.refreshLayout.setColorSchemeColors(accentColor)
        binding.refreshLayout.setOnRefreshListener {
            binding.refreshLayout.isRefreshing = false
            checkAndRestoreBackupThenUpdate()
        }
        if (bookshelfLayout == 0) {
            binding.rvBookshelf.layoutManager = LinearLayoutManager(context)
        } else {
            binding.rvBookshelf.layoutManager = GridLayoutManager(context, bookshelfLayout + 2)
        }
        binding.rvBookshelf.itemAnimator = null
        binding.rvBookshelf.adapter = booksAdapter
        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (positionStart == 0 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (toPosition == 0 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }
        })
    }

    override fun upGroup(data: List<BookGroup>) {
        if (data != bookGroups) {
            bookGroups = data
            booksAdapter.updateItems()
            binding.tvEmptyMsg.isGone = getItemCount() > 0
            binding.refreshLayout.isEnabled = enableRefresh && getItemCount() > 0
        }
    }

    override fun upSort() {
        initBooksData()
    }

    private fun initBooksData() {
        if (groupId == BookGroup.IdRoot) {
            if (isAdded) {
                binding.titleBar.title = getString(R.string.bookshelf)
                binding.refreshLayout.isEnabled = true
                enableRefresh = true
            }
        } else {
            bookGroups.firstOrNull {
                groupId == it.groupId
            }?.let {
                binding.titleBar.title = "${getString(R.string.bookshelf)}(${it.groupName})"
                binding.refreshLayout.isEnabled = it.enableRefresh
                enableRefresh = it.enableRefresh
            }
        }
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (AppConfig.getBookSortByGroupId(groupId)) {
                    1 -> list.sortedByDescending {
                        it.latestChapterTime
                    }

                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy {
                        it.order
                    }

                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }

                    else -> list.sortedByDescending {
                        it.durChapterTime
                    }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                books = list
                booksAdapter.updateItems()
                binding.tvEmptyMsg.isGone = getItemCount() > 0
                binding.refreshLayout.isEnabled = enableRefresh && getItemCount() > 0
                delay(100)
            }
        }
    }

    fun back(): Boolean {
        if (groupId != BookGroup.IdRoot) {
            groupId = BookGroup.IdRoot
            initBooksData()
            return true
        }
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        SearchActivity.start(requireContext(), query)
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        return false
    }

    override fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    override fun onItemClick(item: Any) {
        when (item) {
            is Book -> startActivityForBook(item)

            is BookGroup -> {
                groupId = item.groupId
                initBooksData()
            }
        }
    }

    override fun onItemLongClick(item: Any) {
        when (item) {
            is Book -> startActivity<BookInfoActivity> {
                putExtra("name", item.name)
                putExtra("author", item.author)
            }

            is BookGroup -> showDialogFragment(GroupEditDialog(item))
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    fun getItemCount(): Int {
        return if (groupId == BookGroup.IdRoot) {
            bookGroups.size + books.size
        } else {
            books.size
        }
    }

    /**
     * 智能双向同步WebDAV备份
     * 策略：比较远端备份的lastModify和本地的LocalConfig.lastDataChange
     * - 远端更新：先恢复备份再更新书籍
     * - 本地更新：先更新书籍再备份
     */
    private fun checkAndRestoreBackupThenUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 检查是否配置了WebDAV
                if (!AppWebDav.isOk) {
                    AppLog.put("WebDAV未配置，直接更新书籍")
                    activityViewModel.upToc(books)
                    return@launch
                }

                // 获取远端最新备份
                AppLog.put("开始检查WebDAV备份...")
                val lastBackupResult = AppWebDav.lastBackUp()

                if (lastBackupResult.isFailure) {
                    val exception = lastBackupResult.exceptionOrNull()
                    val errorMsg = when {
                        exception == null -> "未知错误（异常为null）"
                        exception.message != null -> exception.message!!
                        else -> "${exception.javaClass.simpleName}（无错误信息）"
                    }
                    AppLog.put("获取远端备份失败: $errorMsg", exception)
                    context?.toastOnUi("检查WebDAV备份失败: $errorMsg")
                    activityViewModel.upToc(books)
                    return@launch
                }

                val remoteBackupFile = lastBackupResult.getOrNull()
                val remoteLastModify = remoteBackupFile?.lastModify ?: 0L
                val localLastDataChange = LocalConfig.lastDataChange

                AppLog.put(
                    "远端备份: ${remoteBackupFile?.displayName ?: "无"} (时间戳: $remoteLastModify), " +
                            "本地最后数据变化: $localLastDataChange"
                )

                when {
                    remoteBackupFile == null -> {
                        // 无远端备份，先更新再备份
                        AppLog.put("未发现远端备份，更新后将首次备份")
                        context?.toastOnUi("未发现远端备份，更新后将备份")
                        activityViewModel.upToc(books)
                        delay(2000)
                        context?.let { ctx ->
                            Backup.backupLocked(ctx, AppConfig.backupPath)
                            LocalConfig.lastDataChange = LocalConfig.lastBackup
                            AppLog.put("首次备份完成，时间戳: ${LocalConfig.lastBackup}")
                            toastOnUi("备份到WebDAV完成")
                        }
                    }

                    remoteLastModify == 0L -> {
                        // 时间戳解析失败，保守起见先恢复
                        AppLog.put("警告：远端时间戳解析失败，将恢复远端备份")
                        context?.toastOnUi("远端时间戳异常，正在恢复...")
                        AppWebDav.restoreWebDav(remoteBackupFile.displayName)
                        LocalConfig.lastDataChange = System.currentTimeMillis()
                        AppLog.put("恢复完成，更新lastDataChange: ${LocalConfig.lastDataChange}")
                        context?.toastOnUi("恢复完成，开始更新书籍")
                        delay(1000)
                        activityViewModel.upToc(books)
                    }

                    remoteLastModify > localLastDataChange -> {
                        // 远端更新，先恢复再更新
                        AppLog.put(
                            "远端备份更新 (远端:$remoteLastModify > 本地:$localLastDataChange)，" +
                                    "先恢复备份"
                        )
                        context?.toastOnUi("发现新的远端备份，正在恢复...")
                        AppWebDav.restoreWebDav(remoteBackupFile.displayName)
                        LocalConfig.lastDataChange = remoteLastModify
                        AppLog.put("恢复完成，更新lastDataChange: ${LocalConfig.lastDataChange}")
                        context?.toastOnUi("恢复完成，开始更新书籍")
                        delay(1000)
                        activityViewModel.upToc(books)
                    }

                    else -> {
                        // 本地更新或相等，先更新再备份
                        AppLog.put(
                            "本地数据更新 (本地:$localLastDataChange >= 远端:$remoteLastModify)，" +
                                    "先更新再备份"
                        )
                        activityViewModel.upToc(books)
                        delay(2000)
                        context?.let { ctx ->
                            Backup.backupLocked(ctx, AppConfig.backupPath)
                            LocalConfig.lastDataChange = LocalConfig.lastBackup
                            AppLog.put("备份完成，时间戳: ${LocalConfig.lastBackup}")
                        }
                    }
                }

            } catch (e: kotlinx.coroutines.CancellationException) {
                // 协程被取消（通常是因为恢复备份后数据库变化触发了新的同步）
                // 这是正常情况，不需要处理，Flow会自动更新界面
                AppLog.put("WebDAV同步协程被取消（正常）")
                throw e  // 重新抛出以正确传播取消信号
            } catch (e: Exception) {
                AppLog.put("WebDAV同步失败: ${e.localizedMessage}", e)
                context?.toastOnUi("WebDAV同步失败: ${e.localizedMessage}")
                // 出错时仍然执行更新
                activityViewModel.upToc(books)
            }
        }
    }

    override fun getItems(): List<Any> {
        if (groupId != BookGroup.IdRoot) {
            return books
        }
        return bookGroups + books
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
        }
    }
}