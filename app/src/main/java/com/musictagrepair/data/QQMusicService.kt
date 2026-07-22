package com.musictagrepair.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * QQ 音乐在线服务。
 *
 * - 搜索：POST `https://u.y.qq.com/cgi-bin/musics.fcg?sign=zzcSign(jsonBody)`
 *   签名算法 zzcSign：SHA1(json) → 字符索引抽取 + XOR 混淆 → base64 去掉 \\/+= → 全小写
 * - 封面：直接拼 URL `https://y.gtimg.cn/music/photo_new/T002R500x500M000{albumMid}.jpg`，不需要单独请求
 * - 歌词：POST `https://u.y.qq.com/cgi-bin/musicu.fcg`，body 含 songID（数字 id）
 *   响应的 lyric 字段是 hex 字符串，需用非标准 3DES 解密 + zlib inflate（见 [TxQrcDecoder]）
 *
 * 算法来源：/tmp/any-listen/.../tx/{musicSearch.ts, lyric.ts, musicInfo.ts, pic.ts, utils/{crypto.ts, index.ts}, qrcDecode.ts}
 */
class QQMusicService(
    private val client: OkHttpClient,
) {
    companion object {
        private const val UA = "QQMusic 14090508(android 12)"
        private const val UA_LYRIC = "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/86.0.4240.198 Safari/537.36"
        private const val UA_INFO = "Mozilla/5.0 (compatible; MSIE 9.0; Windows NT 6.1; WOW64; Trident/5.0)"

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        // zzcSign 算法的固定参数
        private val PART_1_INDEXES = intArrayOf(23, 14, 6, 36, 16, 40, 7, 19)
        private val PART_2_INDEXES = intArrayOf(16, 1, 32, 12, 19, 27, 8, 5)
        private val SCRAMBLE_VALUES = intArrayOf(
            89, 39, 179, 150, 218, 82, 58, 252,
            177, 52, 186, 123, 120, 64, 242, 133,
            143, 161, 121, 179,
        )

        private val random = SecureRandom()
    }

    /** 搜索 QQ 音乐。 */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 20): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val searchId = generateSearchId()
            val body = buildJsonObject {
                put("comm", buildJsonObject {
                    put("_channelid", "0")
                    put("_os_version", "6.2.9200-2")
                    put("ct", "19")
                    put("cv", "2151")
                    put("guid", "1F70E520B2EAA7D25E11760783C53CA9")
                    put("patch", "118")
                    put("psrf_access_token_expiresAt", 0)
                    put("psrf_qqaccess_token", "")
                    put("psrf_qqopenid", "")
                    put("psrf_qqunionid", "")
                    put("tmeAppID", "qqmusic")
                    put("tmeLoginType", 0)
                    put("uin", "0")
                    put("wid", "7223299733393904640")
                })
                put("req", buildJsonObject {
                    put("module", "music.search.SearchCgiService")
                    put("method", "DoSearchForQQMusicDesktop")
                    put("param", buildJsonObject {
                        put("grp", 1)
                        put("num_per_page", limit)
                        put("page_num", page)
                        put("query", keyword)
                        put("remoteplace", "txt.newclient.top")
                        put("search_type", 0)
                        put("searchid", searchId)
                    })
                })
            }.toString()

            val sign = zzcSign(body)
            val url = "https://u.y.qq.com/cgi-bin/musics.fcg?sign=$sign"
            val req = Request.Builder().url(url)
                .header("User-Agent", UA)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext emptyList()
            val root = runCatching { json.parseToJsonElement(respBody).jsonObject }.getOrNull()
                ?: return@withContext emptyList()
            val code = root["code"]?.asIntOrNull() ?: -1
            val reqCode = root["req"]?.jsonObject?.get("code")?.asIntOrNull() ?: -1
            if (code != 0 || reqCode != 0) return@withContext emptyList()

            val songs = root["req"]?.jsonObject
                ?.get("data")?.jsonObject
                ?.get("body")?.jsonObject
                ?.get("song")?.jsonObject
                ?.get("list")?.jsonArray
                ?: return@withContext emptyList()

            songs.mapNotNull { e ->
                val song = e.jsonObject
                val mid = song["mid"]?.asStringOrNull() ?: return@mapNotNull null
                val songId = song["id"]?.asLongOrNull()?.toString() ?: return@mapNotNull null
                val title = song["title"]?.asStringOrNull() ?: return@mapNotNull null
                val singers = song["singer"]?.jsonArray
                val singer = singers?.joinToString("、") { it.jsonObject["name"]?.asStringOrNull().orEmpty() }?.ifBlank { null }
                val album = song["album"]?.jsonObject?.get("name")?.asStringOrNull().orEmpty()
                val albumMid = song["album"]?.jsonObject?.get("mid")?.asStringOrNull().orEmpty()
                val interval = song["interval"]?.asLongOrNull()?.let {
                    if (it > 0) "${it / 60}:${(it % 60).toString().padStart(2, '0')}" else null
                }

                val coverUrl = if (albumMid.isNotBlank()) {
                    "https://y.gtimg.cn/music/photo_new/T002R500x500M000$albumMid.jpg"
                } else null

                OnlineMusicInfo(
                    id = mid,
                    name = title,
                    singer = singer.orEmpty(),
                    album = album,
                    interval = interval,
                    coverUrl = coverUrl,
                    sourceId = MusicSource.QQ,
                    meta = mapOf(
                        "songId" to songId,
                        "albumMid" to albumMid,
                    ),
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 获取 QQ 音乐歌词。返回 (lyric, tlyric)。 */
    suspend fun getLyrics(info: OnlineMusicInfo): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val songIdStr = info.meta["songId"] ?: return@withContext null to null
            val songId = songIdStr.toLongOrNull() ?: return@withContext null to null

            val body = buildJsonObject {
                put("comm", buildJsonObject {
                    put("ct", "19")
                    put("cv", "1859")
                    put("uin", "0")
                })
                put("req", buildJsonObject {
                    put("method", "GetPlayLyricInfo")
                    put("module", "music.musichallSong.PlayLyricInfo")
                    put("param", buildJsonObject {
                        put("format", "json")
                        put("crypt", 1)
                        put("ct", 19)
                        put("cv", 1873)
                        put("interval", 0)
                        put("lrc_t", 0)
                        put("qrc", 1)
                        put("qrc_t", 0)
                        put("roma", 1)
                        put("roma_t", 0)
                        put("songID", songId)
                        put("trans", 1)
                        put("trans_t", 0)
                        put("type", -1)
                    })
                })
            }.toString()

            val req = Request.Builder().url("https://u.y.qq.com/cgi-bin/musicu.fcg")
                .header("referer", "https://y.qq.com")
                .header("user-agent", UA_LYRIC)
                .post(body.toRequestBody(JSON_MEDIA_TYPE))
                .build()
            val resp = client.newCall(req).execute()
            val respBody = resp.body?.string() ?: return@withContext null to null
            val root = runCatching { json.parseToJsonElement(respBody).jsonObject }.getOrNull()
                ?: return@withContext null to null
            val data = root["req"]?.jsonObject?.get("data")?.jsonObject ?: return@withContext null to null
            val lyricHex = data["lyric"]?.asStringOrNull() ?: return@withContext null to null
            val transHex = data["trans"]?.asStringOrNull()

            val lyric = TxQrcDecoder.decodeQrc(lyricHex).ifBlank { null }
            val tlyric = transHex?.let { TxQrcDecoder.decodeQrc(it).ifBlank { null } }
            lyric to tlyric
        }.getOrDefault(null to null)
    }

    // ---------- zzcSign 签名 ----------

    /**
     * QQ 音乐搜索签名算法：
     * 1. SHA1(text) → 40 字符 hex
     * 2. 用 PART_1_INDEXES 抽取 8 个字符 → part1
     * 3. 用 PART_2_INDEXES 抽取 8 个字符 → part2
     * 4. 前 20 字节每字节 XOR SCRAMBLE_VALUES[i]，得 20 字节 → base64 编码并去掉 \\/+=
     * 5. 返回 "zzc" + part1 + b64part + part2，全小写
     */
    private fun zzcSign(text: String): String {
        val hash = sha1Hex(text)
        if (hash.length < 40) return ""
        val part1 = buildString {
            for (idx in PART_1_INDEXES) {
                if (idx < hash.length) append(hash[idx])
            }
        }
        val part2 = buildString {
            for (idx in PART_2_INDEXES) {
                if (idx < hash.length) append(hash[idx])
            }
        }
        val scramble = ByteArray(SCRAMBLE_VALUES.size)
        for (i in SCRAMBLE_VALUES.indices) {
            val hexByte = if (i * 2 + 1 < hash.length) hash.substring(i * 2, i * 2 + 2) else "00"
            val b = hexByte.toInt(16) and 0xFF
            scramble[i] = (b xor SCRAMBLE_VALUES[i]).toByte()
        }
        val b64Part = base64Encode(scramble)
        return ("zzc" + part1 + b64Part + part2).lowercase()
    }

    private fun sha1Hex(s: String): String {
        val md = MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun base64Encode(data: ByteArray): String {
        val raw = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        // 去掉 \ / + = 四个字符（与原 TS 实现一致）
        return raw.replace("\\", "").replace("/", "").replace("+", "").replace("=", "")
    }

    private fun generateSearchId(): String {
        // 32 位随机 hex 大写 + 5 位随机数字
        val sb = StringBuilder(37)
        val hexChars = "0123456789ABCDEF"
        for (i in 0 until 32) {
            sb.append(hexChars[random.nextInt(16)])
        }
        val n = random.nextInt(100000)
        sb.append(n.toString().padStart(5, '0'))
        return sb.toString()
    }
}
