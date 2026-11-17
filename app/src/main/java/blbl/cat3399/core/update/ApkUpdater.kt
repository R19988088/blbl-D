package blbl.cat3399.core.update

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import blbl.cat3399.BuildConfig
import blbl.cat3399.core.net.BiliClient
import blbl.cat3399.core.net.await
import blbl.cat3399.core.net.ipv4OnlyDns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object ApkUpdater {
    private const val GITHUB_REPO = "R19988088/blbl-D"
    private const val RELEASES_LIST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases?per_page=60"
    private const val RELEASES_LATEST_URL = "https://api.github.com/repos/$GITHUB_REPO/releases/latest"
    private const val RELEASE_APK_URL = "https://github.com/$GITHUB_REPO/releases/latest/download/Blbl-latest.apk"
    private const val CHANGELOG_URL = RELEASES_LIST_URL
    val TEST_APK_URL: String
        get() = RELEASE_APK_URL
    val TEST_CHANGELOG_URL: String
        get() = CHANGELOG_URL

    private const val COOLDOWN_MS = 5_000L

    @Volatile
    private var lastStartedAtMs: Long = 0L

    private val okHttpLazy: Lazy<OkHttpClient> =
        lazy {
            OkHttpClient.Builder()
                .dns(ipv4OnlyDns { BiliClient.prefs.ipv4OnlyEnabled })
                .connectTimeout(12, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()
        }

    private val okHttp: OkHttpClient
        get() = okHttpLazy.value

    fun evictConnections() {
        if (okHttpLazy.isInitialized()) okHttp.connectionPool.evictAll()
    }

    sealed class Progress {
        data object Connecting : Progress()

        data class Downloading(
            val downloadedBytes: Long,
            val totalBytes: Long?,
            val bytesPerSecond: Long,
        ) : Progress() {
            val percent: Int? =
                totalBytes?.takeIf { it > 0 }?.let { total ->
                    ((downloadedBytes.toDouble() / total.toDouble()) * 100.0).roundToInt().coerceIn(0, 100)
                }

            val hint: String =
                buildString {
                    if (totalBytes != null && totalBytes > 0) {
                        append("${formatBytes(downloadedBytes)} / ${formatBytes(totalBytes)}")
                    } else {
                        append(formatBytes(downloadedBytes))
                    }
                    if (bytesPerSecond > 0) append("（${formatBytes(bytesPerSecond)}/s）")
                }
        }
    }

    data class RemoteUpdate(
        val versionName: String,
        val changelog: String,
        val versions: List<RemoteUpdate> = emptyList(),
        /** Direct download url of the APK asset published in this GitHub release. */
        val apkUrl: String? = null,
    ) {
        val displayChangelog: String
            get() = changelog.ifBlank { "暂无更新日志" }
    }

    fun markStarted(nowMs: Long = System.currentTimeMillis()) {
        lastStartedAtMs = nowMs
    }

    fun cooldownLeftMs(nowMs: Long = System.currentTimeMillis()): Long {
        val last = lastStartedAtMs
        val left = (last + COOLDOWN_MS) - nowMs
        return left.coerceAtLeast(0)
    }

    suspend fun fetchLatestUpdate(
        url: String = TEST_CHANGELOG_URL,
    ): RemoteUpdate {
        return withContext(Dispatchers.IO) {
            var lastError: Throwable? = null
            val maxAttempts = 3
            for (attempt in 1..maxAttempts) {
                ensureActive()
                try {
                    return@withContext fetchLatestUpdateOnce(url)
                } catch (t: Throwable) {
                    if (t is CancellationException) throw t
                    lastError = t
                    val shouldRetry =
                        attempt < maxAttempts &&
                            (t is IOException || t.message?.startsWith("HTTP ") == true)
                    if (!shouldRetry) throw t
                    delay(400L * attempt)
                }
            }
            throw lastError ?: IllegalStateException("fetch latest version failed")
        }
    }

    private fun fetchLatestUpdateOnce(url: String): RemoteUpdate {
        val req =
            Request.Builder()
                .url(url)
                .header("Cache-Control", "no-cache")
                .get()
                .build()
        val call = okHttp.newCall(req)
        val res = call.execute()
        res.use { r ->
            if (r.code == 403) error("GitHub 接口访问受限（HTTP 403），请稍后再试")
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val raw = body.string()
            return when {
                url.contains("api.github.com/repos/") && url.endsWith("/releases/latest") ->
                    parseGitHubReleaseUpdate(raw)
                url.contains("api.github.com/repos/") -> parseGitHubStableReleases(raw)
                else -> parseChangelog(raw)
            }
        }
    }

    private fun parseGitHubReleaseUpdate(raw: String): RemoteUpdate {
        val obj = org.json.JSONObject(raw)
        val versionName = obj.optString("tag_name").trim().removePrefix("v")
            .ifBlank { obj.optString("name").trim().removePrefix("v") }
        check(versionName.isNotBlank()) { "missing GitHub release version" }
        val changelog = obj.optString("body").trim().ifBlank { "No changelog." }
        return RemoteUpdate(versionName = versionName, changelog = changelog)
    }

    /**
     * GitHub release list -> the newest *stable* (正式版) release of our own repository.
     *
     * A release only qualifies when it is neither a draft nor a pre-release and its tag is a plain
     * dotted version such as `v0.1.135`; tags like `v0.1.135-beta1` are skipped. Releases without an
     * installable APK asset are skipped as well, because the download must target the exact asset
     * of that release instead of a mutable "latest" URL.
     */
    internal fun parseGitHubStableReleases(raw: String): RemoteUpdate {
        val arr = org.json.JSONArray(raw)
        val releases = ArrayList<GitHubRelease>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val assets = obj.optJSONArray("assets")
            val assetList = ArrayList<GitHubReleaseAsset>(assets?.length() ?: 0)
            if (assets != null) {
                for (j in 0 until assets.length()) {
                    val asset = assets.optJSONObject(j) ?: continue
                    assetList.add(
                        GitHubReleaseAsset(
                            name = asset.optString("name").trim(),
                            url = asset.optString("browser_download_url").trim(),
                        ),
                    )
                }
            }
            releases.add(
                GitHubRelease(
                    tagName = obj.optString("tag_name"),
                    draft = obj.optBoolean("draft"),
                    prerelease = obj.optBoolean("prerelease"),
                    body = obj.optString("body"),
                    assets = assetList,
                    publishedAt = obj.optString("published_at"),
                ),
            )
        }
        return selectLatestStableRelease(releases)
    }

    internal data class GitHubReleaseAsset(val name: String, val url: String)

    internal data class GitHubRelease(
        val tagName: String,
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val body: String = "",
        val assets: List<GitHubReleaseAsset> = emptyList(),
        val publishedAt: String = "",
    )

    /**
     * Picks the newest *stable* (正式版) release of our own repository.
     *
     * A release only qualifies when it is neither a draft nor a pre-release, its tag is a plain dotted
     * version such as `v0.1.135` (so `v0.1.135-beta1` is skipped) and it ships an installable APK,
     * because the download targets that exact release asset instead of a mutable "latest" URL.
     */
    internal fun selectLatestStableRelease(releases: List<GitHubRelease>): RemoteUpdate {
        data class Candidate(val release: GitHubRelease, val versionName: String, val apkUrl: String)

        val stable = ArrayList<Candidate>()
        for (release in releases) {
            if (release.draft || release.prerelease) continue
            val versionName = release.tagName.trim().removePrefix("v").removePrefix("V").trim()
            if (!isStableVersionName(versionName)) continue
            val apkUrl = pickApkAsset(release.assets) ?: continue
            stable.add(Candidate(release = release, versionName = versionName, apkUrl = apkUrl))
        }
        check(stable.isNotEmpty()) { "未找到正式版" }

        // Newest *published* stable release wins: tag numbering was reset over time (legacy v0.5.x
        // tags sort above today's 0.1.x numerically), so publication time is the reliable order.
        val sorted =
            stable.sortedWith { a, b ->
                val byTime = b.release.publishedAt.compareTo(a.release.publishedAt)
                if (byTime != 0) {
                    byTime
                } else {
                    val av = parseVersion(a.versionName) ?: emptyList()
                    val bv = parseVersion(b.versionName) ?: emptyList()
                    -compareVersion(av, bv)
                }
            }
        val mapped =
            sorted.map { candidate ->
                RemoteUpdate(
                    versionName = candidate.versionName,
                    changelog = candidate.release.body.trim().ifBlank { "暂无更新日志" },
                    apkUrl = candidate.apkUrl,
                )
            }
        return mapped.first().copy(versions = mapped)
    }

    private fun isStableVersionName(versionName: String): Boolean =
        Regex("""^[0-9]+(\.[0-9]+)*$""").matches(versionName)

    /** Prefers the stable "Blbl-latest.apk" alias, then any non API19/KITKAT release APK asset. */
    private fun pickApkAsset(assets: List<GitHubReleaseAsset>): String? {
        val apks =
            assets.filter {
                it.name.isNotBlank() && it.url.isNotBlank() && it.name.endsWith(".apk", ignoreCase = true)
            }
        apks.firstOrNull { it.name.equals("Blbl-latest.apk", ignoreCase = true) }?.let { return it.url }
        apks.firstOrNull {
            val lower = it.name.lowercase()
            !lower.contains("api19") && !lower.contains("kitkat")
        }?.let { return it.url }
        return null
    }

    internal fun parseChangelog(raw: String): RemoteUpdate {
        val versions = parseChangelogVersions(raw)
        return versions.first().copy(versions = versions)
    }

    internal fun parseChangelogVersions(raw: String): List<RemoteUpdate> {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trim()
        check(normalized.isNotBlank()) { "更新日志为空" }

        val lines = normalized.lines()
        val allHeadings =
            lines.withIndex()
                .mapNotNull { (index, line) ->
                    parseVersionHeading(line)?.let { heading -> index to heading }
                }
        check(allHeadings.isNotEmpty()) { "未找到版本标题" }

        val versionLevel = allHeadings.first().second.level
        val headings = allHeadings.filter { (_, heading) -> heading.level == versionLevel }
        return headings.mapIndexed { index, (headingIndex, heading) ->
            val nextHeadingIndex = headings.getOrNull(index + 1)?.first ?: lines.size
            val sectionLines =
                lines.subList(headingIndex + 1, nextHeadingIndex)
                    .dropLastWhile { it.isBlank() }
            val changelog =
                sectionLines
                    .joinToString("\n")
                    .trim()

            RemoteUpdate(
                versionName = heading.versionName,
                changelog = changelog,
            )
        }
    }

    fun apkUrlFor(versionName: String): String = RELEASE_APK_URL

    private data class VersionHeading(
        val level: Int,
        val versionName: String,
    )

    private fun parseVersionHeading(line: String): VersionHeading? {
        val trimmed = line.trim()
        val match = Regex("""^(#{1,6})\s+\[?v?([0-9]+(?:\.[0-9]+)*(?:[-+][A-Za-z0-9_.-]+)?)\]?(?:\s+.*)?$""").matchEntire(trimmed)
            ?: return null
        val level = match.groupValues[1].length
        val versionName = match.groupValues[2].trim()
        if (parseVersion(versionName) == null) return null
        return VersionHeading(level = level, versionName = versionName)
    }

    fun isRemoteNewer(remoteVersionName: String, currentVersionName: String = BuildConfig.VERSION_NAME): Boolean {
        val remote = parseVersion(remoteVersionName) ?: return false
        val current = parseVersion(currentVersionName) ?: return remoteVersionName.trim() != currentVersionName.trim()
        return compareVersion(remote, current) > 0
    }

    suspend fun downloadApkToCache(
        context: Context,
        url: String = TEST_APK_URL,
        onProgress: (Progress) -> Unit,
    ): File {
        onProgress(Progress.Connecting)

        val dir = File(context.cacheDir, "test_update").apply { mkdirs() }
        val part = File(dir, "update.apk.part")
        val target = File(dir, "update.apk")
        runCatching { part.delete() }
        runCatching { target.delete() }

        val req = Request.Builder().url(url).get().build()
        val call = okHttp.newCall(req)
        val res = call.await()
        res.use { r ->
            check(r.isSuccessful) { "HTTP ${r.code} ${r.message}" }
            val body = r.body ?: error("empty body")
            val total = body.contentLength().takeIf { it > 0 }
            withContext(Dispatchers.IO) {
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var downloaded = 0L

                        var lastEmitAtMs = 0L
                        var speedAtMs = System.currentTimeMillis()
                        var speedBytes = 0L
                        var bytesPerSecond = 0L

                        while (true) {
                            ensureActive()
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read

                            // Speed estimate (1s window)
                            speedBytes += read
                            val nowMs = System.currentTimeMillis()
                            val speedElapsedMs = nowMs - speedAtMs
                            if (speedElapsedMs >= 1_000) {
                                bytesPerSecond = (speedBytes * 1_000L / speedElapsedMs.coerceAtLeast(1)).coerceAtLeast(0)
                                speedBytes = 0L
                                speedAtMs = nowMs
                            }

                            // UI progress: at most 5 updates per second.
                            if (nowMs - lastEmitAtMs >= 200) {
                                lastEmitAtMs = nowMs
                                onProgress(Progress.Downloading(downloadedBytes = downloaded, totalBytes = total, bytesPerSecond = bytesPerSecond))
                            }
                        }
                        output.fd.sync()
                    }
                }
            }
        }

        check(part.exists() && part.length() > 0) { "downloaded file is empty" }
        check(part.renameTo(target)) { "rename failed" }
        return target
    }

    fun installApk(context: Context, apkFile: File) {
        val uri = installUriFor(context, apkFile)
        val intent =
            Intent(Intent.ACTION_VIEW).apply {
                addCategory(Intent.CATEGORY_DEFAULT)
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        grantInstallerReadPermissions(context, intent, uri)
        context.startActivity(intent)
    }

    @SuppressLint("SetWorldReadable")
    private fun installUriFor(context: Context, apkFile: File): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val authority = "${context.packageName}.fileprovider"
            FileProvider.getUriForFile(context, authority, apkFile)
        } else {
            apkFile.setReadable(true, false)
            Uri.fromFile(apkFile)
        }
    }

    @Suppress("DEPRECATION")
    private fun grantInstallerReadPermissions(
        context: Context,
        intent: Intent,
        uri: Uri,
    ) {
        if (uri.scheme != "content") return

        val installers =
            context.packageManager.queryIntentActivities(
                intent,
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        for (installer in installers) {
            val packageName = installer.activityInfo?.packageName ?: continue
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    private fun formatBytes(bytes: Long): String {
        val b = bytes.coerceAtLeast(0)
        if (b < 1024) return "${b}B"
        val kb = b / 1024.0
        if (kb < 1024) return String.format(Locale.US, "%.1fKB", kb)
        val mb = kb / 1024.0
        if (mb < 1024) return String.format(Locale.US, "%.1fMB", mb)
        val gb = mb / 1024.0
        return String.format(Locale.US, "%.2fGB", gb)
    }

    private fun parseVersion(raw: String): List<Int>? {
        val cleaned = raw.trim().removePrefix("v")
        val digitsOnly =
            cleaned.takeWhile { ch ->
                ch.isDigit() || ch == '.'
            }
        if (digitsOnly.isBlank()) return null
        val parts = digitsOnly.split('.').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun compareVersion(a: List<Int>, b: List<Int>): Int {
        val max = maxOf(a.size, b.size)
        for (i in 0 until max) {
            val ai = a.getOrElse(i) { 0 }
            val bi = b.getOrElse(i) { 0 }
            if (ai != bi) return ai.compareTo(bi)
        }
        return 0
    }
}
