package blbl.cat3399.core.update

import blbl.cat3399.core.update.ApkUpdater.GitHubRelease
import blbl.cat3399.core.update.ApkUpdater.GitHubReleaseAsset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApkUpdaterStableReleaseTest {
    private fun apk(name: String = "Blbl-latest.apk"): GitHubReleaseAsset =
        GitHubReleaseAsset(name = name, url = "https://example.com/$name")

    private fun release(
        tag: String,
        prerelease: Boolean = false,
        draft: Boolean = false,
        assets: List<GitHubReleaseAsset> = listOf(apk()),
        body: String = "",
        publishedAt: String = "",
    ): GitHubRelease =
        GitHubRelease(
            tagName = tag,
            draft = draft,
            prerelease = prerelease,
            body = body,
            assets = assets,
            publishedAt = publishedAt,
        )

    @Test
    fun picks_highest_stable_release() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release("v0.1.10", body = "旧版"),
                    release("v0.1.12", body = "新版"),
                ),
            )

        assertEquals("0.1.12", update.versionName)
        assertEquals("新版", update.changelog)
        assertEquals(listOf("0.1.12", "0.1.10"), update.versions.map { it.versionName })
    }

    @Test
    fun skips_prerelease_and_draft() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release("v0.2.0", prerelease = true),
                    release("v0.1.99", draft = true),
                    release("v0.1.5"),
                ),
            )

        assertEquals("0.1.5", update.versionName)
    }

    @Test
    fun skips_non_numeric_tags() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release("v0.2.0-beta1"),
                    release("nightly"),
                    release("v0.1.7"),
                ),
            )

        assertEquals("0.1.7", update.versionName)
    }

    @Test
    fun skips_releases_without_apk_asset() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release("v0.3.0", assets = listOf(GitHubReleaseAsset("notes.txt", "https://example.com/notes.txt"))),
                    release("v0.1.8"),
                ),
            )

        assertEquals("0.1.8", update.versionName)
    }

    @Test
    fun prefers_latest_alias_over_versioned_asset() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release(
                        "v0.1.9",
                        assets =
                            listOf(
                                GitHubReleaseAsset("Blbl-0.1.9.apk", "https://example.com/Blbl-0.1.9.apk"),
                                GitHubReleaseAsset("Blbl-latest.apk", "https://example.com/Blbl-latest.apk"),
                            ),
                    ),
                ),
            )

        assertEquals("https://example.com/Blbl-latest.apk", update.apkUrl)
    }

    @Test
    fun ignores_api19_asset_when_alias_missing() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release(
                        "v0.1.9",
                        assets =
                            listOf(
                                GitHubReleaseAsset("blbl-D-0.1.9-api19-release.apk", "https://example.com/api19.apk"),
                                GitHubReleaseAsset("Blbl-0.1.9.apk", "https://example.com/arm64.apk"),
                            ),
                    ),
                ),
            )

        assertEquals("https://example.com/arm64.apk", update.apkUrl)
    }

    @Test
    fun newest_published_wins_over_legacy_high_tag() {
        val update =
            ApkUpdater.selectLatestStableRelease(
                listOf(
                    release("v0.5.98", publishedAt = "2026-01-02T00:00:00Z"),
                    release("v0.1.134", publishedAt = "2026-11-16T08:07:00Z"),
                ),
            )

        assertEquals("0.1.134", update.versionName)
        assertEquals(listOf("0.1.134", "0.5.98"), update.versions.map { it.versionName })
    }

    @Test
    fun empty_list_is_rejected() {
        val error = runCatching { ApkUpdater.selectLatestStableRelease(emptyList()) }.exceptionOrNull()
        assertTrue(error is IllegalStateException)
    }

    @Test
    fun newer_comparison_uses_numeric_version() {
        assertTrue(ApkUpdater.isRemoteNewer("0.1.12", "0.1.11"))
        assertFalse(ApkUpdater.isRemoteNewer("0.1.12", "0.1.12"))
    }
}
