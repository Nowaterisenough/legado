package io.legado.app.help.storage

import android.content.Context
import androidx.room.InvalidationTracker
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import kotlinx.coroutines.delay
import splitties.init.appCtx
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 自动备份观察器
 * 监听书源、书架和阅读记录的变化,当配置开启时自动触发WebDAV备份
 */
object AutoBackupObserver {

    private val isBackingUp = AtomicBoolean(false)
    private val lastBackupTime = AtomicLong(0)
    private val lastChangeTime = AtomicLong(0)
    private const val BACKUP_DELAY = 30 * 1000L // 30秒延迟,避免频繁备份
    private const val MIN_BACKUP_INTERVAL = 5 * 60 * 1000L // 最小备份间隔5分钟

    private val observer = object : InvalidationTracker.Observer("books", "book_sources", "readRecord") {
        override fun onInvalidated(tables: Set<String>) {
            notifyDataChanged()
        }
    }

    /**
     * 通知数据变化
     */
    fun notifyDataChanged() {
        if (!AppConfig.autoBackupOnChange) {
            return
        }

        lastChangeTime.set(System.currentTimeMillis())

        // 延迟备份,避免短时间内多次变化导致多次备份
        Coroutine.async {
            delay(BACKUP_DELAY)

            val now = System.currentTimeMillis()
            val timeSinceLastChange = now - lastChangeTime.get()
            val timeSinceLastBackup = now - lastBackupTime.get()

            // 如果距离最后一次变化已经过了延迟时间,且距离上次备份超过最小间隔,则执行备份
            if (timeSinceLastChange >= BACKUP_DELAY && timeSinceLastBackup >= MIN_BACKUP_INTERVAL) {
                triggerAutoBackup(appCtx)
            }
        }
    }

    /**
     * 触发自动备份
     */
    private suspend fun triggerAutoBackup(context: Context) {
        if (!AppConfig.autoBackupOnChange) {
            return
        }

        if (isBackingUp.compareAndSet(false, true)) {
            try {
                AppLog.put("开始自动备份")
                Backup.backupLocked(context, AppConfig.backupPath)
                lastBackupTime.set(System.currentTimeMillis())
                AppLog.put("自动备份完成")
            } catch (e: Exception) {
                AppLog.put("自动备份失败\n${e.localizedMessage}", e)
            } finally {
                isBackingUp.set(false)
            }
        }
    }

    /**
     * 初始化数据库观察器
     */
    fun init() {
        appDb.invalidationTracker.addObserver(observer)
    }
}
