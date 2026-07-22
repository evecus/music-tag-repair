package com.musictagrepair.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.math.BigInteger
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * 咪咕音乐在线服务。
 *
 * - 搜索：HTTP GET，URL 携带 query 参数；headers 携带 timestamp / deviceId / sign
 *   签名公式：`MD5(keyword + "6cdc72a439cef99a3418d2a78aa28c73" + "yyapp2d16148780a1dcc7408e06336b98cfd50" + deviceId + timestamp)`
 * - 封面：直接从搜索结果 `img3/img2/img1` 拿，不需要单独请求
 * - 歌词：从搜索结果的 `mrcUrl`（加密逐字）/ `lrcUrl`（明文）/ `trcUrl`（翻译）下载文本；
 *   mrc 用 TEA 解密（固定 9 个 bigint 密钥，utf16le 输出）
 *
 * 算法来源：/tmp/any-listen/.../mg/{musicSearch.ts, lyric.ts, pic.ts, musicDetail.ts, utils/index.ts, utils/mrc.ts}
 */
class MiguService(
    private val client: OkHttpClient,
) {
    companion object {
        private const val UA = "Mozilla/5.0 (Linux; U; Android 11.0.0; zh-cn; MI 11 Build/OPR1.170623.032) " +
            "AppleWebKit/534.30 (KHTML, like Gecko) Version/4.0 Mobile Safari/534.30"
        private const val UA_LYRIC = "Mozilla/5.0 (Linux; Android 5.1.1; Nexus 6 Build/LYZ28E) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/59.0.3071.115 Mobile Safari/537.36"
        private const val DEVICE_ID = "963B7AA0D21511ED807EE5846EC87D20"
        private const val SIGNATURE_MD5 = "6cdc72a439cef99a3418d2a78aa28c73"
        private const val APP_ID = "yyapp2d16148780a1dcc7408e06336b98cfd50"
        private const val CHANNEL = "0146921"
        private const val UI_VERSION = "A_music_3.6.1"
        private const val SEARCH_SWITCH = """{"song":1,"album":0,"singer":0,"tagSong":1,"mvSong":0,"bestShow":1,"songlist":0,"lyricSong":0}"""

        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        // TEA 解密常量
        private val DELTA = 2654435769L
        private val TEA_KEYS = longArrayOf(
            27303562373562475L,
            18014862372307051L,
            22799692160172081L,
            34058940340699235L,
            30962724186095721L,
            27303523720101991L,
            27303523720101998L,
            31244139033526382L,
            28992395054481524L,
        )
        private val TWO_POW_64 = BigInteger.valueOf(2).pow(64)
    }

    /** 搜索咪咕音乐。 */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 20): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val time = System.currentTimeMillis().toString()
            val sign = md5("$keyword$SIGNATURE_MD5$APP_ID$DEVICE_ID$time")
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val switch = URLEncoder.encode(SEARCH_SWITCH, "UTF-8")
            val url = "https://jadeite.migu.cn/music_search/v3/search/searchAll" +
                "?isCorrect=0&isCopyright=1&searchSwitch=$switch" +
                "&pageSize=$limit&text=$encoded&pageNo=$page&sort=0&sid=USS"
            val req = Request.Builder().url(url).get()
                .header("uiVersion", UI_VERSION)
                .header("deviceId", DEVICE_ID)
                .header("timestamp", time)
                .header("sign", sign)
                .header("channel", CHANNEL)
                .header("User-Agent", UA)
                .build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: return@withContext emptyList()
            if (root["code"]?.asStringOrNull() != "000000") return@withContext emptyList()

            val songData = root["songResultData"]?.jsonObject ?: return@withContext emptyList()
            // resultList 是二维数组 [[song1], [song2], ...]，扁平化
            val resultList = songData["resultList"]?.jsonArray ?: return@withContext emptyList()
            resultList.flatMap { outer ->
                outer.jsonArray.mapNotNull { e ->
                    val item = e.jsonObject
                    val songId = item["songId"]?.asStringOrNull() ?: return@mapNotNull null
                    val songName = item["songName"]?.asStringOrNull() ?: return@mapNotNull null
                    val singers = item["singerList"]?.jsonArray
                    val singer = singers?.joinToString("、") { it.jsonObject["name"]?.asStringOrNull().orEmpty() }?.ifBlank { null }
                    val album = item["album"]?.asStringOrNull().orEmpty()
                    val duration = item["duration"]?.asLongOrNull() ?: 0L
                    val interval = if (duration > 0) formatInterval(duration * 1000) else null

                    val picUrl = normalizeMiguImg(
                        item["img3"]?.asStringOrNull()
                            ?: item["img2"]?.asStringOrNull()
                            ?: item["img1"]?.asStringOrNull()
                    )
                    val lrcUrl = item["lrcUrl"]?.asStringOrNull()
                    val mrcUrl = item["mrcUrl"]?.asStringOrNull()
                    val trcUrl = item["trcUrl"]?.asStringOrNull()
                    val contentId = item["contentId"]?.asStringOrNull()
                    val copyrightId = item["copyrightId"]?.asStringOrNull()

                    OnlineMusicInfo(
                        id = songId,
                        name = songName,
                        singer = singer.orEmpty(),
                        album = album,
                        interval = interval,
                        coverUrl = picUrl,
                        sourceId = MusicSource.MIGU,
                        meta = buildMap {
                            put("contentId", contentId.orEmpty())
                            put("copyrightId", copyrightId.orEmpty())
                            lrcUrl?.let { put("lrcUrl", it) }
                            mrcUrl?.let { put("mrcUrl", it) }
                            trcUrl?.let { put("trcUrl", it) }
                            picUrl?.let { put("picUrl", it) }
                        },
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 获取咪咕歌词。返回 (lyric, tlyric)。 */
    suspend fun getLyrics(info: OnlineMusicInfo): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            val mrcUrl = info.meta["mrcUrl"]
            val lrcUrl = info.meta["lrcUrl"]
            val trcUrl = info.meta["trcUrl"]

            val lyric: String?
            if (mrcUrl != null) {
                // 加密逐字歌词：先 TEA 解密再 parseLyric
                val raw = downloadText(mrcUrl) ?: return@withContext null to null
                val decrypted = decryptMrc(raw) ?: raw
                lyric = parseLyric(decrypted)
            } else if (lrcUrl != null) {
                lyric = downloadText(lrcUrl)
            } else {
                return@withContext null to null
            }

            val tlyric = trcUrl?.let { downloadText(it) }
            lyric to tlyric
        }.getOrDefault(null to null)
    }

    // ---------- 内部工具 ----------

    private fun downloadText(url: String): String? = runCatching {
        val req = Request.Builder().url(url).get()
            .header("Referer", "https://app.c.nf.migu.cn/")
            .header("User-Agent", UA_LYRIC)
            .header("channel", CHANNEL)
            .build()
        val resp = client.newCall(req).execute()
        resp.body?.string()
    }.getOrNull()

    private fun md5(s: String): String {
        val md = MessageDigest.getInstance("MD5")
        val bytes = md.digest(s.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /** 咪咕图片 URL 规范化：缺前缀补 http://d.musicapp.migu.cn */
    private fun normalizeMiguImg(img: String?): String? {
        if (img.isNullOrBlank()) return null
        return if (img.startsWith("http://") || img.startsWith("https://")) img
        else "http://d.musicapp.migu.cn$img"
    }

    /**
     * TEA 解密 mrc 逐字歌词。
     *
     * 算法：把 base64 字符串按 16 字符一组转 bigint（0x + 16位hex），对每块做 TEA 解密，
     * 解密后的 bigint 转 8 字节小端，整体以 UTF-16LE 解码。
     */
    private fun decryptMrc(data: String): String? = runCatching {
        if (data.length < 32) return null
        // 每 16 个字符一组转 bigint（解析为 0x + 16位hex）
        val blocks = mutableListOf<BigInteger>()
        var i = 0
        while (i + 16 <= data.length) {
            val hex = data.substring(i, i + 16)
            // 原始 TS 是 parseInt('0x' + hex)，但 16 位 hex 可能超过 long 范围
            val bi = BigInteger("0$hex", 16) // 注意原 TS 用 parseInt 实际是按 long 解析
            // 实际上 TS parseInt 是 32 位有符号，所以这里要按 long 模拟
            val longVal = bi.toLong()
            blocks.add(BigInteger.valueOf(longVal))
            i += 16
        }
        if (blocks.isEmpty()) return null

        // TEA 解密：每块用 4 个 key 子项循环
        val decrypted = teaDecrypt(blocks)
        // 每个 bigint 转 8 字节小端
        val bytes = mutableListOf<Byte>()
        for (bi in decrypted) {
            val v = bi.toLong()
            for (b in 0 until 8) {
                bytes.add(((v shr (b * 8)) and 0xFF).toByte())
            }
        }
        String(bytes.toByteArray(), Charsets.UTF_16LE)
    }.getOrNull()

    private fun teaDecrypt(data: List<BigInteger>): List<BigInteger> {
        val n = data.size
        if (n < 2) return data
        val q = 6 + 52 / n
        var sum = BigInteger.valueOf((DELTA * q).toLong())
        val mod = BigInteger.valueOf(2).pow(32)
        val out = data.toMutableList()
        while (sum != BigInteger.ZERO) {
            val e = (sum.and(BigInteger.valueOf(0x3))).toInt()
            for (i in (n - 1) downTo 1) {
                val v = out[i].toLong()
                val vPrev = out[i - 1].toLong()
                val k = TEA_KEYS[(e xor (v shr 5).toInt()) and 3]
                val mx = ((vPrev shl 2) xor (v shr 5)) + ((v shl 4) xor (vPrev shr 5)) xor (sum.toLong() xor v) xor k
                out[i] = BigInteger.valueOf((v - mx).toLong() and 0xFFFFFFFFL)
            }
            val v0 = out[0].toLong()
            val vLast = out[n - 1].toLong()
            val k = TEA_KEYS[(e xor (v0 shr 5).toInt()) and 3]
            val mx = ((vLast shl 2) xor (v0 shr 5)) + ((v0 shl 4) xor (vLast shr 5)) xor (sum.toLong() xor v0) xor k
            out[0] = BigInteger.valueOf((v0 - mx).toLong() and 0xFFFFFFFFL)
            sum = sum.subtract(BigInteger.valueOf(DELTA))
        }
        return out
    }

    /**
     * 解析咪咕逐字歌词格式：`[startTime,duration]word(t1,t2)word(t3,t4)...`
     * 提取普通歌词（去掉字时间标签）。
     */
    private fun parseLyric(content: String): String {
        val sb = StringBuilder()
        for (rawLine in content.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val timeMatch = Regex("""^\s*\[(\d+),\d+\]""").find(line)
            if (timeMatch != null) {
                val t = timeMatch.groupValues[1].toLongOrNull() ?: 0L
                val mm = t / 60000
                val ss = (t % 60000) / 1000
                val ms = t % 1000
                val rest = line.substring(timeMatch.range.last + 1)
                    .replace(Regex("""\(\d+,\d+\)"""), "") // 去掉字时间标签
                sb.append("[%02d:%02d.%03d]%s\n".format(mm, ss, ms, rest))
            } else if (line.startsWith("[")) {
                // 标准 [mm:ss] 格式直接保留
                sb.append(line).append("\n")
            }
        }
        return sb.toString()
    }

    private fun formatInterval(ms: Long): String {
        val secs = ms / 1000
        return "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
    }
}
