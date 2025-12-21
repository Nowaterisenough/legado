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
import io.legado.app.utils.fromJsonArray
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
            else -> AppConst.appInfo.appVariant
        }

    private suspend fun getLatestRelease(): List<AppReleaseInfo> {
        val lastReleaseUrl = if (checkVariant.isBeta()) {
            // 获取所有 releases 列表，然后筛选 prerelease 版本
            "https://api.github.com/repos/${io.legado.app.BuildConfig.GITHUB_REPO}/releases?per_page=5"
        } else {
            "https://api.github.com/repos/${io.legado.app.BuildConfig.GITHUB_REPO}/releases/latest"
        }
        val res = okHttpClient.newCallResponse {
            url(lastReleaseUrl)
        }
        if (!res.isSuccessful) {
            throw NoStackTraceException("获取新版本出错(${res.code})")
        }
        val body = res.body?.text()
        if (body.isNullOrBlank()) {
            throw NoStackTraceException("获取新版本出错")
        }
        return if (checkVariant.isBeta()) {
            // 解析 releases 列表，筛选最新的 prerelease 版本
            GSON.fromJsonArray<GithubRelease>(body)
                .getOrElse {
                    throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
                }
                .filter { it.isPreRelease }
                .firstOrNull()
                ?.gitReleaseToAppReleaseInfo()
                ?.sortedByDescending { it.createdAt }
                ?: throw NoStackTraceException("未找到测试版本")
        } else {
            GSON.fromJsonObject<GithubRelease>(body)
                .getOrElse {
                    throw NoStackTraceException("获取新版本出错 " + it.localizedMessage)
                }
                .gitReleaseToAppReleaseInfo()
                .sortedByDescending { it.createdAt }
        }
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
