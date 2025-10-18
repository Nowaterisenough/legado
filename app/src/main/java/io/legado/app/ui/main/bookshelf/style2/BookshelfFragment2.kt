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
import io.legado.app.help.book.isLocal
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

    companion object {
        // 使用静态变量，避免Fragment重建时状态丢失
        @Volatile
        private var isSyncing = false
        private var syncJob: Job? = null
    }

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
     * 等待书籍更新完成
     * 通过检查ViewModel中的更新队列状态来判断是否完成
     */
    private suspend fun waitForBooksUpdateComplete(books: List<Book>) {
        // 获取可能需要更新的书籍URL集合
        val bookUrls = books.filter { !it.isLocal && it.canUpdate }.map { it.bookUrl }.toSet()
        if (bookUrls.isEmpty()) {
            AppLog.put("没有需要更新的书籍")
            return
        }

        AppLog.put("等待 ${bookUrls.size} 本书籍加入更新队列...")

        // 第一阶段：等待书籍真正开始更新（加入onUpTocBooks）
        // 因为upToc()是异步的，需要等待书籍真正进入更新状态
        var waitTime = 0L
        val maxStartWaitTime = 5_000L // 最多等待5秒让更新开始
        val checkInterval = 200L // 每200ms检查一次
        var hasStarted = false

        while (waitTime < maxStartWaitTime) {
            // 检查是否有我们关心的书籍开始更新了
            val startedCount = bookUrls.count { url ->
                activityViewModel.isUpdate(url)
            }

            if (startedCount > 0) {
                AppLog.put("已有 $startedCount 本书籍开始更新，等待全部完成...")
                hasStarted = true
                break
            }

            delay(checkInterval)
            waitTime += checkInterval
        }

        if (!hasStarted) {
            AppLog.put("等待更新开始超时(${waitTime}ms)，书籍可能已在后台更新或无需更新")
            return
        }

        // 第二阶段：等待所有书籍更新完成
        waitTime = 0L
        val maxUpdateWaitTime = 30_000L // 最多等待30秒完成更新

        while (waitTime < maxUpdateWaitTime) {
            // 检查是否还有我们关心的书籍在更新中
            val updatingCount = bookUrls.count { url ->
                activityViewModel.isUpdate(url)
            }

            if (updatingCount == 0) {
                // 所有书籍都更新完成了
                AppLog.put("所有书籍更新完成，总耗时: ${waitTime}ms")
                return
            }

            // 每隔一段时间打印进度
            if (waitTime % 5000L == 0L) {
                AppLog.put("还有 $updatingCount 本书籍正在更新中...")
            }

            delay(checkInterval)
            waitTime += checkInterval
        }

        // 超时了，记录日志但不抛出异常
        val remainingCount = bookUrls.count { url -> activityViewModel.isUpdate(url) }
        AppLog.put("等待书籍更新超时(${maxUpdateWaitTime}ms)，还有 $remainingCount 本书籍未完成更新")
    }

    /**
     * 检查并同步WebDAV备份，然后更新书籍
     * 使用lastDataChangeTime记录本地数据变化时间
     * 对比本地和远端时间戳：
     * - 如果本地更新，先更新书籍信息再备份到远端
     * - 如果远端更新，先恢复远端备份再更新书籍信息
     */
    private fun checkAndRestoreBackupThenUpdate() {
        // 防止重复触发
        if (isSyncing) {
            AppLog.put("同步操作正在进行中，忽略本次下拉刷新")
            context?.toastOnUi("同步操作正在进行中，请稍候")
            binding.refreshLayout.isRefreshing = false  // 立即隐藏下拉动画
            return
        }

        // 取消之前的同步任务（如果有）
        syncJob?.cancel()

        syncJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                isSyncing = true
                binding.refreshLayout.isRefreshing = true  // 显示同步进度

                // 检查是否配置了WebDAV
                if (!AppWebDav.isOk) {
                    AppLog.put("WebDAV未配置，直接更新书籍")
                    activityViewModel.upToc(books)
                    waitForBooksUpdateComplete(books)
                    return@launch
                }

                // 获取最新的远端备份文件
                AppLog.put("开始检查远端备份...")
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
                    // 备份失败仍然执行更新
                    activityViewModel.upToc(books)
                    waitForBooksUpdateComplete(books)
                    return@launch
                }

                val lastBackupFile = lastBackupResult.getOrNull()

                // 获取本地数据变化时间戳
                val localDataChangeTime = context?.getPrefLong(PreferKey.lastDataChangeTime) ?: 0L

                when {
                    lastBackupFile == null -> {
                        // 没有远端备份，先更新书籍再尝试备份
                        AppLog.put("未发现远端备份，将在更新后尝试备份")
                        activityViewModel.upToc(books)
                        // 等待所有书籍更新完成
                        waitForBooksUpdateComplete(books)
                        AppLog.put("书籍更新完成，开始首次备份...")
                        // 尝试备份到远端(backup方法会自动更新本地时间戳)
                        context?.let { ctx ->
                            try {
                                AppLog.put("开始备份到WebDAV...")
                                Backup.backupLocked(ctx, AppConfig.backupPath)
                                toastOnUi("首次备份到WebDAV完成")
                            } catch (e: Exception) {
                                AppLog.put("首次备份失败: ${e.localizedMessage}", e)
                                toastOnUi("备份失败: ${e.localizedMessage}")
                            }
                        }
                    }
                    else -> {
                        // 解析远端备份的时间戳（从文件名）
                        val remoteTimestamp = AppWebDav.parseTimestampFromBackupName(lastBackupFile.displayName)
                        // 如果文件名没有时间戳，使用lastModify
                        val remoteTime = if (remoteTimestamp > 0) remoteTimestamp else lastBackupFile.lastModify

                        AppLog.put("远端备份: ${lastBackupFile.displayName}, 远端时间: $remoteTime, 本地时间: $localDataChangeTime")

                        if (remoteTime > localDataChangeTime) {
                            // 远端备份更新，先恢复备份再更新书籍
                            AppLog.put("远端备份较新 ($remoteTime > $localDataChangeTime)，开始恢复...")
                            context?.toastOnUi("发现新的远端备份，正在恢复...")

                            // 等待恢复完成
                            AppLog.put("调用 restoreWebDav 开始恢复备份...")
                            AppWebDav.restoreWebDav(lastBackupFile.displayName)
                            AppLog.put("restoreWebDav 调用完成")

                            // 恢复完成后，更新本地时间戳为远端时间
                            context?.putPrefLong(PreferKey.lastDataChangeTime, remoteTime)
                            AppLog.put("备份恢复完成，更新本地时间戳: $remoteTime")

                            // 等待数据库更新生效 - 增加等待时间确保所有数据都已写入
                            AppLog.put("等待数据库更新生效...")
                            delay(2000)

                            // 从数据库重新读取恢复后的书籍列表
                            val restoredBooks = appDb.bookDao.getBooksByGroup(groupId)
                            AppLog.put("从数据库读取到 ${restoredBooks.size} 本书籍")

                            context?.toastOnUi("备份恢复完成，开始更新书籍")

                            // 开始更新书籍
                            AppLog.put("开始调用 upToc 更新书籍...")
                            activityViewModel.upToc(restoredBooks)
                            AppLog.put("upToc 调用完成，开始等待更新完成...")

                            // 等待所有书籍更新完成
                            waitForBooksUpdateComplete(restoredBooks)
                            AppLog.put("书籍更新完成")
                        } else {
                            // 本地更新或时间相同，先更新书籍再备份
                            AppLog.put("本地数据较新或相同 ($localDataChangeTime >= $remoteTime)，先更新再备份")
                            activityViewModel.upToc(books)
                            // 等待所有书籍更新完成
                            waitForBooksUpdateComplete(books)
                            AppLog.put("书籍更新完成，开始备份...")
                            // 备份到远端(backup方法会自动更新本地时间戳)
                            context?.let { ctx ->
                                AppLog.put("开始备份到WebDAV...")
                                Backup.backupLocked(ctx, AppConfig.backupPath)
                                AppLog.put("备份完成")
                            }
                        }
                    }
                }

            } catch (e: Exception) {
                when (e) {
                    is kotlinx.coroutines.CancellationException -> {
                        // 协程被取消，不重新抛出，让finally正常执行
                        AppLog.put("WebDAV同步被取消")
                        // finally块会处理清理工作
                    }
                    else -> {
                        // 其他异常，记录日志并继续更新
                        AppLog.put("WebDAV同步失败: ${e.localizedMessage}", e)
                        context?.toastOnUi("WebDAV同步失败: ${e.localizedMessage}")
                        // 出错时仍然执行更新
                        try {
                            activityViewModel.upToc(books)
                            waitForBooksUpdateComplete(books)
                        } catch (ignored: Exception) {
                            // 更新过程中的异常忽略，确保finally能执行
                            AppLog.put("更新过程中出错: ${ignored.message}")
                        }
                    }
                }
            } finally {
                // 无论成功失败，都要重置同步标志和隐藏动画
                isSyncing = false
                binding.refreshLayout.isRefreshing = false
                AppLog.put("同步操作结束，重置同步标志")
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        // Fragment销毁时，取消同步任务并重置标志
        syncJob?.cancel()
        isSyncing = false
        AppLog.put("Fragment销毁，重置同步标志")
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