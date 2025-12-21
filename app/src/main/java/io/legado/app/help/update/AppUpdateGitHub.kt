package io.legado.app.help.update

import androidx.annotation.Keep
import io.legado.app.constant.AppConst
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.help.http.newCallResponse
import io.legado.app.help.http.okHttpClient
import io.legado.app.help.http.text
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonObject
import kotlinx.coroutines.CoroutineScope

@Keep
@Suppress("unused")
object AppUpdateGitHub : AppUpdate.AppUpdateInterface {

    private val checkVariant: AppVariant
        get() = when (AppConfig.updateToVariant) {
            "official_version" -> AppVariant.OFFICIAL
            "beta_release_version" -> AppVariant.BETA_RELEASE
            "beta_releaseA_version" -> AppVariant.BETA_RELEASEA
            // 默认使用正式版，避免 DEBUG 版本或 UNKNOWN 导致检查失败
            else -> AppVariant.OFFICIAL
        }

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        // 始终使用 /releases/latest 获取最新正式版本
        // 因为 fork 仓库通常没有 beta tag，使用 /releases/tags/beta 会返回 404
        val lastReleaseUrl = "https://api.github.com/repos/${io.legado.app.BuildConfig.GITHUB_REPO}/releases/latest"
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body.text()
        if (body.isBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return GSON.fromJsonObject<GithubRelease>(body)
            .getOrElse {
                throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
            }
            .gitReleaseToAppReleaseInfo()
            .sortedByDescending { it.createdAt }
    }

    override fun check(
        scope: CoroutineScope,
    ): Coroutine<AppUpdate.UpdateInfo> {
        return Coroutine.async(scope) {
            getLatestRelease()
                .filter { it.appVariant == checkVariant }
                .firstOrNull { it.versionName > AppConst.appInfo.versionName }
                ?.let {
                    return@async AppUpdate.UpdateInfo(
                        it.versionName,
                        it.note,
                        it.downloadUrl,
                        it.name
                    )
                }
                ?: throw NoStackTraceException("已是最新版本")
        }.timeout(10000)
    }

    /**
     * 获取更新日志
     */
    fun getChangeLog(scope: CoroutineScope): Coroutine<String> {
        return Coroutine.async(scope) {
            val releaseUrl = "https://api.github.com/repos/${io.legado.app.BuildConfig.GITHUB_REPO}/releases/latest"
            val res = okHttpClient.newCallResponse {
                url(releaseUrl)
            }
            if (!res.isSuccessful) {
                throw NoStackTraceException("获取更新日志失败(${res.code})")
            }
            val body = res.body?.text()
            if (body.isNullOrBlank()) {
                throw NoStackTraceException("获取更新日志失败")
            }
            GSON.fromJsonObject<GithubRelease>(body)
                .getOrElse {
                    throw NoStackTraceException("解析更新日志失败: " + it.localizedMessage)
                }
                .body
        }.timeout(10000)
    }
}
