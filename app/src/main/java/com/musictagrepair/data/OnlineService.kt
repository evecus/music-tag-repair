package com.musictagrepair.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import android.util.Base64
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * 在线搜索服务（网易云 + 酷狗）。
 *
 * [client] 暴露给其他平台服务（[KuwoService] / [MiguService] / [QQMusicService]）复用，
 * 避免每个平台各自创建 OkHttp 实例（共享连接池更省资源）。
 */
class OnlineService(
    /** 复用的 OkHttp 客户端，对其他平台服务可见。 */
    val httpClient: OkHttpClient = defaultClient(),
) {
    private val client: OkHttpClient get() = httpClient

    companion object {
        private fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"

        /** KRC 加密密钥（16 字节固定），来源于 lx-music-desktop 项目。 */
        private val KRC_ENC_KEY = byteArrayOf(
            0x40, 0x47, 0x61, 0x77, 0x5e, 0x32, 0x74, 0x47,
            0x51, 0x36, 0x31, 0x2d, 0xce.toByte(), 0xd2.toByte(), 0x6e, 0x69,
        )

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * 搜索网易云音乐
     */
    suspend fun searchNetease(keyword: String, page: Int = 1, limit: Int = 10): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "keyword" to keyword,
                "needCorrect" to "1",
                "channel" to "typing",
                "offset" to (limit * (page - 1)).toString(),
                "scene" to "normal",
                "total" to if (page == 1) "true" else "false",
                "limit" to limit.toString(),
            )

            val formData = NeteaseCrypto.eapi("/api/search/song/list/page", data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("http://interface.music.163.com/eapi/batch")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext emptyList()
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext emptyList()

            val resources = root["data"]?.jsonObject?.get("resources")?.jsonArray ?: return@withContext emptyList()

            resources.mapNotNull { r ->
                val baseInfo = r.jsonObject["baseInfo"]?.jsonObject ?: return@mapNotNull null
                val song = baseInfo["simpleSongData"]?.jsonObject ?: return@mapNotNull null
                val album = song["al"]?.jsonObject

                var coverUrl = album?.get("picUrl")?.stringOrNull()
                if (coverUrl != null) coverUrl = "$coverUrl?param=500y500"

                val dt = song["dt"]?.longOrNull() ?: 0L
                val interval = if (dt > 0) formatInterval(dt) else null

                val singers = song["ar"]?.jsonArray?.mapNotNull { ar ->
                    ar.jsonObject["name"]?.stringOrNull()?.takeIf { it.isNotBlank() }
                }?.joinToString("、").orEmpty()

                OnlineMusicInfo(
                    id = song["id"]?.stringOrNull().orEmpty(),
                    name = song["name"]?.stringOrNull().orEmpty(),
                    singer = singers,
                    album = album?.get("name")?.stringOrNull().orEmpty(),
                    interval = interval,
                    coverUrl = coverUrl,
                    sourceId = "wy",
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 搜索酷狗音乐
     *
     * 使用 `songsearch.kugou.com/song_search_v2` 接口，相比老版 `mobilecdn.kugou.com/api/v3` 返回字段更全：
     * 除了基础信息外还能拿到 [Audioid] / [AlbumID] / [FileHash] / [Duration]，这些字段后续用于歌词和封面接口。
     * 关键字段写入 [OnlineMusicInfo.meta]：
     * - `hash`：FileHash，歌词搜索和封面接口都需要
     * - `musicId`：Audioid 字符串
     * - `albumId`：AlbumID
     * - `_interval`：Duration（秒），传给歌词接口的 timelength
     */
    suspend fun searchKuGou(keyword: String, page: Int = 1, limit: Int = 10): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        try {
            val url = "https://songsearch.kugou.com/song_search_v2?keyword=${urlEncode(keyword)}&page=$page&pagesize=$limit&userid=0&clientver=&platform=WebFilter&filter=2&iscorrection=1&privilege_filter=0&area_code=1"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", UA)
                .get()
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext emptyList()
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["status"]?.intOrNull() != 1) return@withContext emptyList()

            val lists = root["data"]?.jsonObject?.get("lists")?.jsonArray ?: return@withContext emptyList()

            lists.mapNotNull { s ->
                val obj = s.jsonObject
                val hash = obj["FileHash"]?.stringOrNull().orEmpty()
                if (hash.isBlank()) return@mapNotNull null

                val musicId = obj["Audioid"]?.intOrNull()?.toString().orEmpty()
                val albumId = obj["AlbumID"]?.stringOrNull().orEmpty()
                val duration = obj["Duration"]?.longOrNull() ?: 0L
                val interval = if (duration > 0) formatInterval(duration * 1000) else null

                val singers = obj["Singers"]?.jsonArray?.mapNotNull { it.jsonObject["name"]?.stringOrNull()?.takeIf { n -> n.isNotBlank() } }?.joinToString("、").orEmpty()
                val songName = obj["OriSongName"]?.stringOrNull().orEmpty()
                val albumName = obj["AlbumName"]?.stringOrNull().orEmpty()

                OnlineMusicInfo(
                    id = hash,
                    name = songName,
                    singer = singers,
                    album = albumName,
                    interval = interval,
                    sourceId = "kg",
                    meta = mapOf(
                        "hash" to hash,
                        "musicId" to musicId,
                        "albumId" to albumId,
                        "_interval" to duration.toString(),
                    ),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * 获取酷狗歌词。
     *
     * 两步流程：
     * 1. `lyrics.kugou.com/search`：用 `keyword + hash + timelength` 查到候选歌词的 `id/accesskey`，
     *    根据 `krctype==1 && contenttype!=1` 决定 fmt 是 krc 还是 lrc。
     * 2. `lyrics.kugou.com/download`：根据 fmt 拉取歌词内容：
     *    - lrc：base64 解码即为 LRC 文本
     *    - krc：base64 解码 → 跳过前 4 字节 → XOR encKey（16 字节循环）→ zlib inflate 得到 KRC 文本
     *
     * 返回 `(主歌词, 翻译歌词)`，失败时对应字段为 null。
     *
     * @param info 来自 [searchKuGou] 的搜索结果，[OnlineMusicInfo.meta] 必须含 `hash` 和可选的 `_interval`
     */
    suspend fun getKuGouLyrics(info: OnlineMusicInfo): Pair<String?, String?> = withContext(Dispatchers.IO) {
        try {
            val hash = info.meta["hash"].orEmpty()
            if (hash.isBlank()) return@withContext null to null
            val timeLength = info.meta["_interval"]?.toLongOrNull()
                ?: parseIntervalSeconds(info.interval).toLong()
            val keyword = urlEncode("${info.singer} ${info.name}".trim())

            // 1) 搜索候选歌词
            val searchUrl = "http://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=$keyword&hash=$hash&timelength=$timeLength&lrctxt=1"
            val searchReq = Request.Builder()
                .url(searchUrl)
                .header("KG-RC", "1")
                .header("KG-THash", "expand_search_manager.cpp:852736169:451")
                .header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
                .get()
                .build()

            val searchResp = client.newCall(searchReq).execute()
            val searchBody = searchResp.body?.string() ?: return@withContext null to null
            val searchRoot = json.parseToJsonElement(searchBody).jsonObject
            val candidates = searchRoot["candidates"]?.jsonArray ?: return@withContext null to null
            if (candidates.isEmpty()) return@withContext null to null

            val candidate = candidates[0].jsonObject
            val lyricId = candidate["id"]?.stringOrNull().orEmpty()
            val accessKey = candidate["accesskey"]?.stringOrNull().orEmpty()
            if (lyricId.isBlank() || accessKey.isBlank()) return@withContext null to null

            val krctype = candidate["krctype"]?.intOrNull() ?: 0
            val contenttype = candidate["contenttype"]?.intOrNull() ?: 0
            val fmt = if (krctype == 1 && contenttype != 1) "krc" else "lrc"

            // 2) 下载歌词
            val downloadUrl = "http://lyrics.kugou.com/download?ver=1&client=pc&id=$lyricId&accesskey=$accessKey&fmt=$fmt&charset=utf8"
            val downloadReq = Request.Builder()
                .url(downloadUrl)
                .header("KG-RC", "1")
                .header("KG-THash", "expand_search_manager.cpp:852736169:451")
                .header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
                .get()
                .build()

            val downloadResp = client.newCall(downloadReq).execute()
            val downloadBody = downloadResp.body?.string() ?: return@withContext null to null
            val downloadRoot = json.parseToJsonElement(downloadBody).jsonObject
            val content = downloadRoot["content"]?.stringOrNull() ?: return@withContext null to null
            val actualFmt = downloadRoot["fmt"]?.stringOrNull() ?: fmt

            val rawText = when (actualFmt) {
                "krc" -> decodeKrc(content) ?: return@withContext null to null
                "lrc" -> String(Base64.decode(content, Base64.NO_WRAP), Charsets.UTF_8)
                else -> return@withContext null to null
            }

            // 解析 KRC：尝试提取翻译（[language:...] 块）和主歌词
            parseKrc(rawText) to null
        } catch (_: Throwable) {
            null to null
        }
    }

    /**
     * 获取酷狗封面 URL。
     *
     * POST `media.store.kugou.com/v1/get_res_privilege`，传入 hash / album_audio_id / album_id 拿到图片地址。
     * 返回的 image 模板含 `{size}` 占位符，会被 [imgsize] 数组的第一个值替换。
     *
     * @param info 来自 [searchKuGou] 的搜索结果，[OnlineMusicInfo.meta] 必须含 `hash`，可选 `musicId`/`albumId`
     */
    suspend fun getKuGouCover(info: OnlineMusicInfo): String? = withContext(Dispatchers.IO) {
        try {
            val hash = info.meta["hash"].orEmpty()
            if (hash.isBlank()) return@withContext null
            val albumAudioId = info.meta["musicId"].orEmpty().ifBlank { "0" }
            val albumId = info.meta["albumId"].orEmpty().ifBlank { "0" }

            val jsonBody = buildJsonObject {
                put("appid", 1001)
                put("area_code", "1")
                put("behavior", "play")
                put("clientver", "9020")
                put("need_hash_offset", 1)
                put("relate", 1)
                put("resource", buildJsonArray {
                    add(buildJsonObject {
                        put("album_audio_id", albumAudioId)
                        put("album_id", albumId)
                        put("hash", hash)
                        put("id", 0)
                        put("name", "${info.singer} - ${info.name}.mp3")
                        put("type", "audio")
                    })
                })
                put("token", "")
                put("userid", 2626431536)
                put("vip", 1)
            }.toString()
            val body = jsonBody.toRequestBody(JSON_MEDIA_TYPE)
            val request = Request.Builder()
                .url("http://media.store.kugou.com/v1/get_res_privilege")
                .header("KG-RC", "1")
                .header("KG-THash", "expand_search_manager.cpp:852736169:451")
                .header("User-Agent", "KuGou2012-9020-ExpandSearchManager")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(respBody).jsonObject
            val data = root["data"]?.jsonArray ?: return@withContext null
            if (data.isEmpty()) return@withContext null

            val infoObj = data[0].jsonObject["info"]?.jsonObject ?: return@withContext null
            val image = infoObj["image"]?.stringOrNull() ?: return@withContext null
            val imgSizes = infoObj["imgsize"]?.jsonArray
            if (imgSizes != null && imgSizes.isNotEmpty()) {
                val size = imgSizes[0].stringOrNull()
                if (size != null && image.contains("{size}")) {
                    return@withContext image.replace("{size}", size)
                }
            }
            image
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 解密 KRC 内容。
     * 流程：base64 解码 → 跳过前 4 字节 → 每字节 XOR [KRC_ENC_KEY] → zlib inflate → UTF-8 文本
     */
    private fun decodeKrc(base64Content: String): String? = runCatching {
        val raw = Base64.decode(base64Content, Base64.NO_WRAP)
        if (raw.size <= 4) return@runCatching null
        val buf = raw.copyOfRange(4, raw.size)
        for (i in buf.indices) {
            buf[i] = (buf[i].toInt() xor KRC_ENC_KEY[i % KRC_ENC_KEY.size].toInt()).toByte()
        }
        inflateZlib(buf)
    }.getOrNull()

    /** zlib inflate 到 UTF-8 文本。 */
    private fun inflateZlib(data: ByteArray): String? = runCatching {
        val inflater = java.util.zip.Inflater(false)
        inflater.setInput(data)
        val output = ByteArray(8192)
        val out = java.io.ByteArrayOutputStream()
        while (!inflater.finished()) {
            val n = inflater.inflate(output)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(output, 0, n)
            }
        }
        inflater.end()
        out.toString("UTF-8")
    }.getOrNull()

    /**
     * 解析 KRC 文本：剥离头部 `[id:$xxx]` 和 `[language:...]` 块，主歌词返回为字符串。
     * 翻译/罗马音解析从 KRC 复杂结构里抽取，简化处理只取主歌词部分。
     */
    private fun parseKrc(krcText: String): String {
        var text = krcText.replace("\r", "")
        // 剥离头部 [id:$xxx]
        val headExp = Regex("""^.*\[id:\$\w+]\n""", RegexOption.DOT_MATCHES_ALL)
        text = headExp.replace(text, "")
        // 剥离 [language:...] 块（翻译/罗马音元数据，简化处理直接删掉）
        text = Regex("""\[language:[\w=\\/+]+]\n?""").replace(text, "")
        // 去掉逐字标签 <ms,ms>，保留时间标签 [mm:ss.xxx]
        text = Regex("""<\d+,\d+,\d+>""").replace(text, "")
        // 旧式逐字标签 [mm:ss,ms]
        text = Regex("""\[(\d+),(\d+)]""").replace(text) { mr ->
            val total = mr.groupValues[1].toLong()
            val ms = (total % 1000).toString().padStart(3, '0')
            val sec = total / 1000
            val m = (sec / 60).toString().padStart(2, '0')
            val s = (sec % 60).toString().padStart(2, '0')
            "[$m:$s.$ms]"
        }
        return text.trim()
    }

    /** 把 "mm:ss" 或 "hh:mm:ss" 的时长字符串解析为总秒数。 */
    private fun parseIntervalSeconds(interval: String?): Int {
        if (interval.isNullOrBlank()) return 0
        val parts = interval.split(":")
        var result = 0
        var unit = 1
        for (i in parts.indices.reversed()) {
            result += (parts[i].toIntOrNull() ?: 0) * unit
            unit *= 60
        }
        return result
    }

    /**
     * 获取网易云歌词
     */
    suspend fun getNeteaseLyrics(musicId: String): Map<String, String?>? = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "id" to musicId,
                "cp" to false,
                "tv" to 0, "lv" to 0, "rv" to 0, "kv" to 0, "yv" to 0, "ytv" to 0, "yrv" to 0,
            )

            val formData = NeteaseCrypto.eapi("/api/song/lyric/v1", data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("http://interface.music.163.com/eapi/batch")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext null

            mapOf(
                "lyric" to root["lrc"]?.jsonObject?.get("lyric")?.stringOrNull(),
                "tlyric" to root["tlyric"]?.jsonObject?.get("lyric")?.stringOrNull(),
                "rlyric" to root["romalrc"]?.jsonObject?.get("lyric")?.stringOrNull(),
            )
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 获取网易云封面
     */
    suspend fun getNeteaseCover(musicId: String): String? = withContext(Dispatchers.IO) {
        try {
            val data = mapOf(
                "c" to "[{\"id\":$musicId}]",
                "ids" to "[$musicId]",
            )

            val formData = NeteaseCrypto.weapi(data)

            val body = FormBody.Builder().apply {
                formData.forEach { (k, v) -> add(k, v) }
            }.build()

            val request = Request.Builder()
                .url("https://music.163.com/weapi/v3/song/detail")
                .header("User-Agent", UA)
                .header("Referer", "https://music.163.com/")
                .header("Origin", "https://music.163.com")
                .post(body)
                .build()

            val resp = client.newCall(request).execute()
            val respBody = resp.body?.string() ?: return@withContext null
            val root = json.parseToJsonElement(respBody).jsonObject
            if (root["code"]?.intOrNull() != 200) return@withContext null

            val url = root["songs"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("al")?.jsonObject?.get("picUrl")?.stringOrNull()
            url?.let { "$it?param=500y500" }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 下载封面图片
     */
    suspend fun downloadCover(url: String): ByteArray? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(request).execute()
            if (resp.isSuccessful) resp.body?.bytes() else null
        } catch (_: Throwable) {
            null
        }
    }

    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }

    private fun formatInterval(ms: Long): String {
        val secs = ms / 1000
        return "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
    }

    private fun urlEncode(s: String): String = java.net.URLEncoder.encode(s, "UTF-8")
}

/**
 * JsonElement 工具扩展：安全获取字符串/数字
 */
private fun JsonElement?.stringOrNull(): String? {
    if (this == null) return null
    if (this !is JsonPrimitive) return null
    if (this.isString) return this.content
    return this.content.takeIf { it != "null" }
}

private fun JsonElement?.intOrNull(): Int? = this?.let {
    (it as? JsonPrimitive)?.content?.toIntOrNull()
}

private fun JsonElement?.longOrNull(): Long? = this?.let {
    (it as? JsonPrimitive)?.content?.toLongOrNull()
}
