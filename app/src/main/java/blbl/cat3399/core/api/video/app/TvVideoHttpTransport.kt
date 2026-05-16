package blbl.cat3399.core.api.video.app

import blbl.cat3399.core.api.video.VideoPlayKind
import blbl.cat3399.core.api.video.VideoPlayRequest
import blbl.cat3399.core.net.BiliClient
import org.json.JSONObject

internal interface TvVideoHttpTransport {
    suspend fun playUrl(request: VideoPlayRequest): JSONObject
}

internal object BiliClientTvVideoHttpTransport : TvVideoHttpTransport {
    override suspend fun playUrl(request: VideoPlayRequest): JSONObject {
        val cid = request.cid?.takeIf { it > 0L } ?: error("cid required")
        val objectId =
            when (request.kind) {
                VideoPlayKind.UGC -> request.aid?.takeIf { it > 0L } ?: error("aid required")
                VideoPlayKind.PGC -> request.aid?.takeIf { it > 0L } ?: 0L
            }
        val params =
            buildMap {
                val session = BiliClient.prefs.appAuthSession
                session?.accessKey?.trim()?.takeIf { it.isNotBlank() }?.let { put("access_key", it) }
                put("appkey", TV_APP_KEY)
                put("build", TV_BUILD.toString())
                put("cid", cid.toString())
                put("device", "android")
                request.epId?.takeIf { it > 0L }?.let { put("ep_id", it.toString()) }
                put("expire", "0")
                put("fnval", request.fnval.toString())
                put("fnver", "0")
                put("fourk", "1")
                put("mid", "0")
                put("mobi_app", TV_MOBI_APP)
                put("platform", "android")
                put("playurl_type", "1")
                put("qn", request.qn.toString())
                put("ts", (System.currentTimeMillis() / 1000L).toString())
                put("object_id", objectId.toString())
            }
        val signed = signTvQuery(params)
        val url =
            BiliClient.withQuery(
                when (request.kind) {
                    VideoPlayKind.UGC -> TV_UGC_URL
                    VideoPlayKind.PGC -> TV_PGC_URL
                },
                signed,
            )
        return BiliClient.getJson(url = url, headers = tvHeaders(), noCookies = true)
    }

    private fun signTvQuery(params: Map<String, String>): Map<String, String> {
        val queryWithoutSign =
            params.entries
                .sortedBy { it.key }
                .joinToString("&") { (k, v) -> "${encode(k)}=${encode(v)}" }
        return params + ("sign" to md5Hex(queryWithoutSign + TV_APP_SEC))
    }

    private fun encode(s: String): String =
        java.net.URLEncoder.encode(s, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    private fun md5Hex(s: String): String {
        val digest = java.security.MessageDigest.getInstance("MD5").digest(s.toByteArray())
        return buildString(digest.size * 2) {
            digest.forEach { append(String.format(java.util.Locale.US, "%02x", it)) }
        }
    }

    private fun tvHeaders(): Map<String, String> =
        mapOf(
            "User-Agent" to TV_USER_AGENT,
            "Referer" to "https://www.bilibili.com",
            "X-Blbl-Skip-Origin" to "1",
        )

    private const val TV_UGC_URL = "https://api.snm0516.aisee.tv/x/tv/playurl"
    private const val TV_PGC_URL = "https://api.snm0516.aisee.tv/pgc/player/api/playurltv"
    private const val TV_APP_KEY = "4409e2ce8ffd12b8"
    private const val TV_APP_SEC = "59b43e04ad6965f34319062b478f83dd"
    private const val TV_BUILD = 106500
    private const val TV_MOBI_APP = "android_tv_yst"
    private const val TV_USER_AGENT =
        "Mozilla/5.0 BiliDroid/1.12.0 (bbcallen@gmail.com) os/android model/android_tv_yst mobi_app/android_tv_yst build/106500 channel/master innerVer/106500 osVer/11 network/2"
}
