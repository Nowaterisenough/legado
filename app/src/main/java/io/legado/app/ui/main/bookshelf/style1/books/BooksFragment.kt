package io.legado.app.ui.main.bookshelf.style1.books

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.View
import android.view.ViewConfiguration
import androidx.core.view.isGone
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.RecyclerView.Adapter.StateRestorationPolicy
import io.legado.app.R
import io.legado.app.base.BaseFragment
import io.legado.app.constant.AppLog
import io.legado.app.constant.EventBus
import io.legado.app.data.AppDatabase
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.databinding.FragmentBooksBinding
import io.legado.app.help.AppWebDav
import io.legado.app.help.storage.Backup
import io.legado.app.constant.PreferKey
import io.legado.app.help.config.AppConfig
import io.legado.app.help.config.LocalConfig
import io.legado.app.lib.theme.accentColor
import io.legado.app.lib.theme.primaryColor
import io.legado.app.ui.book.info.BookInfoActivity
import io.legado.app.ui.main.MainViewModel
import io.legado.app.utils.cnCompare
import io.legado.app.utils.flowWithLifecycleAndDatabaseChangeFirst
import io.legado.app.utils.getPrefLong
import io.legado.app.utils.observeEvent
import io.legado.app.utils.putPrefLong
import io.legado.app.utils.setEdgeEffectColor
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * 书架界面
 */
class BooksFragment() : BaseFragment(R.layout.fragment_books),
    BaseBooksAdapter.CallBack {

    constructor(position: Int, group: BookGroup) : this() {
        val bundle = Bundle()
        bundle.putInt("position", position)
        bundle.putLong("groupId", group.groupId)
        bundle.putInt("bookSort", group.getRealBookSort())
        bundle.putBoolean("enableRefresh", group.enableRefresh)
        arguments = bundle
    }

    private val binding by viewBinding(FragmentBooksBinding::bind)
    private val activityViewModel by activityViewModels<MainViewModel>()
    private val bookshelfLayout by lazy { AppConfig.bookshelfLayout }
    private val booksAdapter: BaseBooksAdapter<*> by lazy {
        if (bookshelfLayout == 0) {
            BooksAdapterList(requireContext(), this, this, viewLifecycleOwner.lifecycle)
        } else {
            BooksAdapterGrid(requireContext(), this)
        }
    }
    private var booksFlowJob: Job? = null
    var position = 0
        private set
    var groupId = -1L
        private set
    var bookSort = 0
        private set
    private var upLastUpdateTimeJob: Job? = null
    private var enableRefresh = true

    override fun onFragmentCreated(view: View, savedInstanceState: Bundle?) {
        arguments?.let {
            position = it.getInt("position", 0)
            groupId = it.getLong("groupId", -1)
            bookSort = it.getInt("bookSort", 0)
            enableRefresh = it.getBoolean("enableRefresh", true)
            binding.refreshLayout.isEnabled = enableRefresh
        }
        initRecyclerView()
        upRecyclerData()
    }

    private fun initRecyclerView() {
        binding.rvBookshelf.setEdgeEffectColor(primaryColor)
        upFastScrollerBar()
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
        if (bookshelfLayout == 0) {
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksListRecycledViewPool)
        } else {
            binding.rvBookshelf.setRecycledViewPool(activityViewModel.booksGridRecycledViewPool)
        }
        booksAdapter.stateRestorationPolicy = StateRestorationPolicy.PREVENT_WHEN_EMPTY
        binding.rvBookshelf.adapter = booksAdapter
        booksAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (positionStart == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }

            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) {
                val layoutManager = binding.rvBookshelf.layoutManager
                if (toPosition == 0 && itemCount == 1 && layoutManager is LinearLayoutManager) {
                    val scrollTo = layoutManager.findFirstVisibleItemPosition() - itemCount
                    binding.rvBookshelf.scrollToPosition(max(0, scrollTo))
                }
            }
        })
        startLastUpdateTimeJob()
    }

    private fun upFastScrollerBar() {
        val showBookshelfFastScroller = AppConfig.showBookshelfFastScroller
        binding.rvBookshelf.setFastScrollEnabled(showBookshelfFastScroller)
        if (showBookshelfFastScroller) {
            binding.rvBookshelf.scrollBarSize = 0
        } else {
            binding.rvBookshelf.scrollBarSize =
                ViewConfiguration.get(requireContext()).scaledScrollBarSize
        }
    }

    fun upBookSort(sort: Int) {
        binding.root.post {
            arguments?.putInt("bookSort", sort)
            bookSort = sort
            upRecyclerData()
        }
    }

    fun setEnableRefresh(enable: Boolean) {
        enableRefresh = enable
        binding.refreshLayout.isEnabled = enable
    }

    /**
     * 更新书籍列表信息
     */
    private fun upRecyclerData() {
        booksFlowJob?.cancel()
        booksFlowJob = viewLifecycleOwner.lifecycleScope.launch {
            appDb.bookDao.flowByGroup(groupId).map { list ->
                //排序
                when (bookSort) {
                    1 -> list.sortedByDescending { it.latestChapterTime }
                    2 -> list.sortedWith { o1, o2 ->
                        o1.name.cnCompare(o2.name)
                    }

                    3 -> list.sortedBy { it.order }

                    // 综合排序 issue #3192
                    4 -> list.sortedByDescending {
                        max(it.latestChapterTime, it.durChapterTime)
                    }
                    // 按作者排序
                    5 -> list.sortedWith { o1, o2 ->
                        o1.author.cnCompare(o2.author)
                    }

                    else -> list.sortedByDescending { it.durChapterTime }
                }
            }.flowWithLifecycleAndDatabaseChangeFirst(
                viewLifecycleOwner.lifecycle,
                Lifecycle.State.RESUMED,
                AppDatabase.BOOK_TABLE_NAME
            ).catch {
                AppLog.put("书架更新出错", it)
            }.conflate().flowOn(Dispatchers.Default).collect { list ->
                binding.tvEmptyMsg.isGone = list.isNotEmpty()
                binding.refreshLayout.isEnabled = enableRefresh && list.isNotEmpty()
                booksAdapter.setItems(list)
                delay(100)
            }
        }
    }

    private fun startLastUpdateTimeJob() {
        upLastUpdateTimeJob?.cancel()
        if (!AppConfig.showLastUpdateTime || bookshelfLayout != 0) {
            return
        }
        upLastUpdateTimeJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (isActive) {
                    booksAdapter.upLastUpdateTime()
                    delay(30 * 1000)
                }
            }
        }
    }

    fun getBooks(): List<Book> {
        return booksAdapter.getItems()
    }

    fun gotoTop() {
        if (AppConfig.isEInkMode) {
            binding.rvBookshelf.scrollToPosition(0)
        } else {
            binding.rvBookshelf.smoothScrollToPosition(0)
        }
    }

    fun getBooksCount(): Int {
        return booksAdapter.itemCount
    }

    /**
     * 智能双向同步WebDAV备份
     * 策略：比较远端备份的lastModify和本地的LocalConfig.lastBackup
     * - 远端更新：先恢复备份再更新书籍
     * - 本地更新：先更新书籍再备份
     */
    private fun checkAndRestoreBackupThenUpdate() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // 检查是否配置了WebDAV
                if (!AppWebDav.isOk) {
                    AppLog.put("WebDAV未配置，直接更新书籍")
                    activityViewModel.upToc(booksAdapter.getItems())
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
                    activityViewModel.upToc(booksAdapter.getItems())
                    return@launch
                }

                val remoteBackupFile = lastBackupResult.getOrNull()
                val remoteLastModify = remoteBackupFile?.lastModify ?: 0L
                val localLastBackup = LocalConfig.lastBackup

                AppLog.put(
                    "远端备份: ${remoteBackupFile?.displayName ?: "无"} (时间戳: $remoteLastModify), " +
                            "本地最后备份: $localLastBackup"
                )

                when {
                    remoteBackupFile == null -> {
                        // 无远端备份，先更新再备份
                        AppLog.put("未发现远端备份，更新后将首次备份")
                        context?.toastOnUi("未发现远端备份，更新后将备份")
                        activityViewModel.upToc(booksAdapter.getItems())
                        delay(2000)
                        context?.let { ctx ->
                            Backup.backupLocked(ctx, AppConfig.backupPath)
                            AppLog.put("首次备份完成，时间戳: ${LocalConfig.lastBackup}")
                            toastOnUi("备份到WebDAV完成")
                        }
                    }

                    remoteLastModify == 0L -> {
                        // 时间戳解析失败，保守起见先恢复
                        AppLog.put("警告：远端时间戳解析失败，将恢复远端备份")
                        context?.toastOnUi("远端时间戳异常，正在恢复...")
                        AppWebDav.restoreWebDav(remoteBackupFile.displayName)
                        AppLog.put("恢复完成")
                        context?.toastOnUi("恢复完成，开始更新书籍")
                        delay(1000)
                        activityViewModel.upToc(booksAdapter.getItems())
                    }

                    remoteLastModify > localLastBackup -> {
                        // 远端更新，先恢复再更新
                        AppLog.put(
                            "远端备份更新 (远端:$remoteLastModify > 本地:$localLastBackup)，" +
                                    "先恢复备份"
                        )
                        context?.toastOnUi("发现新的远端备份，正在恢复...")
                        AppWebDav.restoreWebDav(remoteBackupFile.displayName)
                        AppLog.put("恢复完成，开始更新书籍")
                        context?.toastOnUi("恢复完成，开始更新书籍")
                        delay(1000)
                        activityViewModel.upToc(booksAdapter.getItems())
                    }

                    else -> {
                        // 本地更新或相等，先更新再备份
                        AppLog.put(
                            "本地备份更新 (本地:$localLastBackup >= 远端:$remoteLastModify)，" +
                                    "先更新再备份"
                        )
                        activityViewModel.upToc(booksAdapter.getItems())
                        delay(2000)
                        context?.let { ctx ->
                            Backup.backupLocked(ctx, AppConfig.backupPath)
                            AppLog.put("备份完成，时间戳: ${LocalConfig.lastBackup}")
                        }
                    }
                }

            } catch (e: Exception) {
                AppLog.put("WebDAV同步失败: ${e.localizedMessage}", e)
                context?.toastOnUi("WebDAV同步失败: ${e.localizedMessage}")
                // 出错时仍然执行更新
                activityViewModel.upToc(booksAdapter.getItems())
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        /**
         * 将 RecyclerView 中的视图全部回收到 RecycledViewPool 中
         */
        binding.rvBookshelf.setItemViewCacheSize(0)
        binding.rvBookshelf.adapter = null
    }

    override fun open(book: Book) {
        startActivityForBook(book)
    }

    override fun openBookInfo(book: Book) {
        startActivity<BookInfoActivity> {
            putExtra("name", book.name)
            putExtra("author", book.author)
        }
    }

    override fun isUpdate(bookUrl: String): Boolean {
        return activityViewModel.isUpdate(bookUrl)
    }

    @SuppressLint("NotifyDataSetChanged")
    override fun observeLiveBus() {
        super.observeLiveBus()
        observeEvent<String>(EventBus.UP_BOOKSHELF) {
            booksAdapter.notification(it)
        }
        observeEvent<String>(EventBus.BOOKSHELF_REFRESH) {
            booksAdapter.notifyDataSetChanged()
            startLastUpdateTimeJob()
            upFastScrollerBar()
        }
    }
}
