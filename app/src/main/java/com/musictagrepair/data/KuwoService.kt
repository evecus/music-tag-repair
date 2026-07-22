package com.musictagrepair.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.zip.Inflater

/**
 * 酷我音乐在线服务。
 *
 * - 搜索：HTTP GET，无加密，无签名
 * - 歌词：响应前 10 字节是 `tp=content`，CRLFCRLF 分隔头部与正文，正文 zlib inflate；
 *   若是逐字歌词（lrcx=1），inflate 后是 base64 文本，解码后用 key `"yeelion"` 循环 XOR 解密
 * - 封面：HTTP GET，纯文本响应，直接是图片 URL
 *
 * 算法来源：/tmp/any-listen/.../kw/{musicSearch.ts, lyric.ts, pic.ts, decodeLyric.ts, util.ts}
 */
class KuwoService(
    private val client: OkHttpClient,
) {
    companion object {
        private const val UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36"
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
        private val LYRIC_XOR_KEY = byteArrayOf(121, 101, 101, 108, 105, 111, 110) // "yeelion"
        private val CRLF_CRLF = byteArrayOf(13, 10, 13, 10)
    }

    /** 搜索酷我音乐。 */
    suspend fun search(keyword: String, page: Int = 1, limit: Int = 20): List<OnlineMusicInfo> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(keyword, "UTF-8")
            val url = "http://search.kuwo.cn/r.s?" +
                "client=kt&all=$encoded&pn=${page - 1}&rn=$limit" +
                "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1" +
                "&show_copyright_off=1&newver=1&ft=music&cluster=0&strategy=2012" +
                "&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(req).execute()
            val body = resp.body?.string() ?: return@withContext emptyList()
            // 酷我返回有时是单引号 JSON，统一替换
            val normalized = body.replace("'", "\"")
            val root = runCatching { json.parseToJsonElement(normalized).jsonObject }.getOrNull()
                ?: return@withContext emptyList()
            val total = root["TOTAL"]?.toString()?.toLongOrNull() ?: 0L
            if (total == 0L) return@withContext emptyList()

            val list = root["abslist"]?.jsonArray ?: return@withContext emptyList()
            list.mapNotNull { e ->
                val o = e.jsonObject
                val musicrid = o["MUSICRID"]?.asStringOrNull() ?: return@mapNotNull null
                val id = musicrid.removePrefix("MUSIC_")
                val name = decodeHtmlEntities(o["SONGNAME"]?.asStringOrNull() ?: return@mapNotNull null)
                val artist = decodeHtmlEntities(o["ARTIST"]?.asStringOrNull().orEmpty())
                    .replace("&", "、")
                val album = decodeHtmlEntities(o["ALBUM"]?.asStringOrNull().orEmpty())
                val duration = o["DURATION"]?.asStringOrNull()?.toLongOrNull() ?: 0L
                val interval = if (duration > 0) formatInterval(duration * 1000) else null
                OnlineMusicInfo(
                    id = id,
                    name = name,
                    singer = artist,
                    album = album,
                    interval = interval,
                    sourceId = MusicSource.KUWO,
                )
            }
        }.getOrDefault(emptyList())
    }

    /** 获取酷我歌词。返回 (lyric, tlyric)。 */
    suspend fun getLyrics(rid: String): Pair<String?, String?> = withContext(Dispatchers.IO) {
        runCatching {
            // lrcx=1 走逐字歌词解密分支；失败时退到 lrcx=0 走明文分支
            val r = fetchLyric(rid, lrcx = true)
            if (r != null) r else (fetchLyric(rid, lrcx = false) ?: (null to null))
        }.getOrDefault(null to null)
    }

    private suspend fun fetchLyric(rid: String, lrcx: Boolean): Pair<String?, String?>? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://mlyric.kuwo.cn/mobi.s?f=web&type=lyric&lrcx=${if (lrcx) 1 else 0}&rid=$rid&encode=utf8"
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(req).execute()
            val raw = resp.body?.bytes() ?: return@withContext null
            // 前 10 字节必须是 "tp=content"
            val head = String(raw.copyOfRange(0, minOf(10, raw.size)), Charsets.US_ASCII).lowercase()
            if (!head.startsWith("tp=content")) return@withContext null
            // 找 CRLF CRLF 分隔符
            val sep = indexOfSequence(raw, CRLF_CRLF)
            if (sep < 0) return@withContext null
            val compressed = raw.copyOfRange(sep + 4, raw.size)
            // zlib inflate
            val inflated = inflate(compressed) ?: return@withContext null
            val text = if (lrcx) {
                // 逐字歌词：inflate 后是 base64 文本，先 utf-8 解码为字符串，再 base64 解码为字节，再 XOR 解密
                val base64Text = String(inflated, Charsets.UTF_8)
                val decodedBytes = runCatching { android.util.Base64.decode(base64Text, android.util.Base64.DEFAULT) }
                    .getOrNull() ?: return@withContext null
                val xored = ByteArray(decodedBytes.size)
                for (i in decodedBytes.indices) {
                    xored[i] = (decodedBytes[i].toInt() xor LYRIC_XOR_KEY[i % LYRIC_XOR_KEY.size].toInt()).toByte()
                }
                String(xored, Charsets.UTF_8)
            } else {
                String(inflated, Charsets.UTF_8)
            }
            // 酷我歌词只有一段主歌词，翻译不返回
            text to null
        }.getOrNull()
    }

    /** 获取酷我封面 URL（响应纯文本）。 */
    suspend fun getCoverUrl(rid: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val url = "http://artistpicserver.kuwo.cn/pic.web?corp=kuwo&type=rid_pic&pictype=500&size=500&rid=$rid"
            val req = Request.Builder().url(url).header("User-Agent", UA).get().build()
            val resp = client.newCall(req).execute()
            val text = resp.body?.string()?.trim() ?: return@withContext null
            if (text.startsWith("http")) {
                // 替换 kwcdn.kuwo.cn 为 kuwo.cn，并把 http 改 https
                if (text.contains(".kwcdn.kuwo.cn")) {
                    text.replace(".kwcdn.kuwo.cn", ".kuwo.cn").replaceFirst("http://", "https://")
                } else text
            } else null
        }.getOrNull()
    }

    // ---------- 工具 ----------

    private fun indexOfSequence(buf: ByteArray, seq: ByteArray): Int {
        outer@ for (i in 0..buf.size - seq.size) {
            for (j in seq.indices) {
                if (buf[i + j] != seq[j]) continue@outer
            }
            return i
        }
        return -1
    }

    private fun inflate(data: ByteArray): ByteArray? = runCatching {
        val inflater = Inflater()
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
        out.toByteArray()
    }.getOrNull()

    private fun decodeHtmlEntities(s: String): String {
        // 简单处理常见 HTML 实体（酷我主要返回 &#数字; 和 &amp; 等）
        if (!s.contains("&")) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val end = s.indexOf(';', i)
                if (end in (i + 2)..(i + 8)) {
                    val ent = s.substring(i + 1, end)
                    val replacement = when {
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.toChar()?.toString()
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent == "nbsp" -> " "
                        else -> null
                    }
                    if (replacement != null) {
                        sb.append(replacement)
                        i = end + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    private fun formatInterval(ms: Long): String {
        val secs = ms / 1000
        return "${secs / 60}:${(secs % 60).toString().padStart(2, '0')}"
    }
}

/** JsonObject 安全字符串提取。 */
internal fun kotlinx.serialization.json.JsonElement?.asStringOrNull(): String? {
    val p = (this as? kotlinx.serialization.json.JsonPrimitive) ?: return null
    if (p.isString) return p.content
    return p.content.takeIf { it != "null" }
}

/** JsonObject 安全长整型提取。 */
internal fun kotlinx.serialization.json.JsonElement?.asLongOrNull(): Long? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()

/** JsonObject 安全整型提取。 */
internal fun kotlinx.serialization.json.JsonElement?.asIntOrNull(): Int? =
    (this as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull()
