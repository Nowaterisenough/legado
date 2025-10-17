package io.legado.app.help

import android.content.Context
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.help.storage.Backup
import io.legado.app.utils.putPrefLong
import splitties.init.appCtx

/**
 * 数据变化监听器
 * 当书籍、书源、订阅、分组等关键数据变化时，更新本地时间戳并触发自动备份
 */
object DataChangeMonitor {

    /**
     * 通知数据已变化
     * 应在以下情况调用：
     * - 书籍增删改
     * - 书源增删改
     * - 订阅源增删改
     * - 书籍分组增删改
     */
    fun notifyDataChanged(context: Context = appCtx) {
        try {
            val currentTime = System.currentTimeMillis()
            // 更新本地数据变化时间戳
            context.putPrefLong(PreferKey.lastDataChangeTime, currentTime)
            AppLog.put("数据变化，更新本地时间戳: $currentTime")

            // 触发自动备份
            Backup.onDataChange(context)
        } catch (e: Exception) {
            AppLog.put("数据变化通知失败\n${e.localizedMessage}", e)
        }
    }

    /**
     * 通知书籍数据变化
     */
    fun notifyBookChanged(context: Context = appCtx) {
        notifyDataChanged(context)
    }

    /**
     * 通知书源数据变化
     */
    fun notifyBookSourceChanged(context: Context = appCtx) {
        notifyDataChanged(context)
    }

    /**
     * 通知订阅源数据变化
     */
    fun notifyRssSourceChanged(context: Context = appCtx) {
        notifyDataChanged(context)
    }

    /**
     * 通知书籍分组数据变化
     */
    fun notifyBookGroupChanged(context: Context = appCtx) {
        notifyDataChanged(context)
    }
}
