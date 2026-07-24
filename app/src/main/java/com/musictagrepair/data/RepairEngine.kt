package com.musictagrepair.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 修复引擎：扫描目录、在线搜索、获取完整元数据、写入标签。
 *
 * 在线搜索聚合 [OnlineService]（网易云 + 酷狗）、[KuwoService]（酷我）、[MiguService]（咪咕）、
 * [QQMusicService]（QQ）共 5 个平台，按各平台返回顺序合并后按相关度排序返回。
 */
class RepairEngine(
    private val tagService: TagService = TagService,
    private val onlineService: OnlineService = OnlineService(),
    private val kuwoService: KuwoService = KuwoService(onlineService.httpClient),
    private val miguService: MiguService = MiguService(onlineService.httpClient),
    private val qqMusicService: QQMusicService = QQMusicService(onlineService.httpClient),
) {
    companion object { private const val TAG = "RepairEngine" }

    /**
     * 扫描目录
     */
    suspend fun scanDirectory(dirPath: String): List<FileStatus> = withContext(Dispatchers.IO) {
        val result = mutableListOf<FileStatus>()
        val root = File(dirPath)
        if (!root.exists() || !root.isDirectory) return@withContext emptyList()

        root.walkTopDown().forEach { file ->
            if (file.isFile && TagService.isSupportedFile(file.absolutePath)) {
                val tags = tagService.readTags(file.absolutePath)
                if (tags != null) {
                    val report = CompletenessReport.check(tags)
                    result.add(
                        FileStatus(
                            path = file.absolutePath,
                            filename = file.name,
                            report = report,
                        ),
                    )
                }
            }
        }
        result
    }

    /**
     * 通过 [LocalScanner]（MediaStore 优先）发现音频文件，然后逐个读取标签构建完整性报告。
     *
     * 扫描分两个阶段，分别触发回调：
     * 1. **发现阶段**：MediaStore 查询本身很快，但文件数多时仍需滚动展示进度；
     *    调用 [onDiscovered] 上报 `(已发现数, 当前文件名)`。
     * 2. **读标签阶段**：jaudiotagger 逐文件解析，是最耗时的部分；
     *    调用 [onTagsRead] 上报 `(已读标签数, 总数, 当前文件名)`，UI 进度条基于此驱动。
     *
     * @param pathPrefix 仅保留此路径前缀下的文件；null 表示不限制
     */
    suspend fun scanViaMediaStore(
        context: Context,
        pathPrefix: String? = null,
        onDiscovered: (Int, String) -> Unit = { _, _ -> },
        onTagsRead: (Int, Int, String) -> Unit = { _, _, _ -> },
    ): List<FileStatus> = withContext(Dispatchers.IO) {
        // 1) 一次性通过 MediaStore 拉取全部音频文件清单
        val audioFiles = LocalScanner.queryAllAudio(context, pathPrefix, onProgress = onDiscovered)
        if (audioFiles.isEmpty()) return@withContext emptyList()

        val total = audioFiles.size
        val result = mutableListOf<FileStatus>()

        // 2) 逐个读取标签
        for ((index, audio) in audioFiles.withIndex()) {
            val tags = tagService.readTags(audio.path)
            if (tags != null) {
                val report = CompletenessReport.check(tags)
                result.add(
                    FileStatus(
                        path = audio.path,
                        filename = audio.name,
                        report = report,
                    ),
                )
            }
            onTagsRead(index + 1, total, audio.name)
        }
        result
    }

    /**
     * 在线搜索匹配：并发请求所有 5 个平台，合并结果并按相关度排序。
     *
     * 搜索关键词策略：
     * - 标题和歌手都有 → "歌手 标题"
     * - 仅有标题 → 标题
     * - 都没有 → 文件名（去掉括号注释和扩展名）
     *
     * 排序策略：见 [relevanceScore]（匹配精确度优先，其次信息完整度）。
     *
     * 过滤策略：标题、歌手必须**都**匹配（模糊或精确均可）才保留，见 [titleMatchScore] / [artistMatchScore]；
     * 只要有一项完全不沾边，就从列表中剔除，避免"匹配在线信息"里出现风马牛不相及的歌曲。
     */
    suspend fun searchOnline(fileStatus: FileStatus): List<OnlineMusicInfo> = coroutineScope {
        val tags = fileStatus.report.currentTags
        val keyword = when {
            !tags.title.isNullOrBlank() && !tags.artist.isNullOrBlank() -> "${tags.artist} ${tags.title}"
            !tags.title.isNullOrBlank() -> tags.title!!
            else -> fileStatus.filename
                .substringBeforeLast('.')
                .replace(Regex("[\\(\\[（].*?[\\)\\]）]"), "")
                .trim()
        }

        // 并发请求 5 个平台
        val deferreds = listOf(
            async { runCatching { onlineService.searchNetease(keyword) }.getOrDefault(emptyList()) },
            async { runCatching { onlineService.searchKuGou(keyword) }.getOrDefault(emptyList()) },
            async { runCatching { kuwoService.search(keyword) }.getOrDefault(emptyList()) },
            async { runCatching { miguService.search(keyword) }.getOrDefault(emptyList()) },
            async { runCatching { qqMusicService.search(keyword) }.getOrDefault(emptyList()) },
        )

        val rawResults = deferreds.awaitAll().flatten()
        val hasLocalTitle = !tags.title.isNullOrBlank()
        val hasLocalArtist = !tags.artist.isNullOrBlank()

        // 过滤：标题、歌手必须都匹配（本地缺失的那一项不参与过滤）
        val filtered = if (!hasLocalTitle && !hasLocalArtist) {
            // 本地没有可用标题/歌手作为参照（用文件名兜底搜索），无法判断相关性，不过滤
            rawResults
        } else {
            rawResults.filter { info ->
                val titleOk = !hasLocalTitle || titleMatchScore(info.name, tags.title!!) > 0.0
                val artistOk = !hasLocalArtist || artistMatchScore(info.singer, tags.artist!!) > 0.0
                titleOk && artistOk
            }
        }

        // 排序：匹配精确度优先，其次信息完整度（专辑/封面/时长等字段是否齐全）
        filtered.sortedByDescending { relevanceScore(it, tags.title, tags.artist) }
    }

    /**
     * 综合评分，用于排序：匹配精确度权重远高于信息完整度，
     * 即"越精确匹配的越靠前"，同等匹配精确度下"信息越全的越靠前"。
     */
    private fun relevanceScore(info: OnlineMusicInfo, localTitle: String?, localArtist: String?): Double {
        val titleScore = if (localTitle.isNullOrBlank()) 0.5 else titleMatchScore(info.name, localTitle)
        val artistScore = if (localArtist.isNullOrBlank()) 0.5 else artistMatchScore(info.singer, localArtist)
        // 匹配精确度（0~1，二者各占一半），放大到 0~1000 作为主排序依据
        val matchScore = (titleScore + artistScore) / 2.0 * 1000.0

        // 信息完整度：专辑、封面、时长、歌手信息是否存在，每项加一点分，作为同精确度下的次级排序依据
        var completeness = 0.0
        if (info.album.isNotBlank()) completeness += 1.0
        if (!info.coverUrl.isNullOrBlank()) completeness += 1.0
        if (!info.interval.isNullOrBlank()) completeness += 1.0
        if (info.singer.isNotBlank()) completeness += 1.0
        if (!info.lyrics.isNullOrBlank()) completeness += 1.0

        return matchScore + completeness
    }

    /**
     * 标题匹配度：0（完全不相关）~ 1（完全一致）。
     * - 完全一致（归一化后）：1.0
     * - 互相包含（如本地"Faded"命中线上"Faded (Restrung)"）：0.7
     * - 完全不沾边：0.0
     */
    private fun titleMatchScore(remoteTitle: String, localTitle: String): Double {
        val remote = normalizeForMatch(remoteTitle)
        val local = normalizeForMatch(localTitle)
        if (local.isBlank() || remote.isBlank()) return 0.0
        if (remote == local) return 1.0
        if (remote.contains(local) || local.contains(remote)) return 0.7
        return 0.0
    }

    /**
     * 歌手匹配度：0（完全不相关）~ 1（完全一致）。
     * 线上歌手字段可能是"歌手A、歌手B"多人形式，逐个拆分后取最高分。
     */
    private fun artistMatchScore(remoteSinger: String, localArtist: String): Double {
        val local = normalizeForMatch(localArtist)
        if (local.isBlank()) return 0.0
        val remoteArtists = remoteSinger
            .split("、", "/", ",", "，", "&")
            .map { normalizeForMatch(it) }
            .filter { it.isNotBlank() }
        if (remoteArtists.isEmpty()) return 0.0
        return remoteArtists.maxOf { remote ->
            when {
                remote == local -> 1.0
                remote.contains(local) || local.contains(remote) -> 0.7
                else -> 0.0
            }
        }
    }

    /** 归一化：去除括号注释、空白、常见标点，转小写，便于模糊比较。 */
    private fun normalizeForMatch(s: String): String = s
        .lowercase()
        .replace(Regex("[\\(\\[（【].*?[\\)\\]）】]"), "")
        .replace(Regex("[\\s\\-_·.,，。！?？!]"), "")
        .trim()

    /**
     * 获取完整元数据（在线歌曲 → 歌词 + 封面）。
     *
     * 根据 [OnlineMusicInfo.sourceId] 分发到对应平台：
     * - wy：网易云歌词 + 封面
     * - kg：酷狗歌词（KRC 解密，候选歌词查询 + 下载）+ 封面（POST get_res_privilege）
     * - kw：酷我歌词 + 封面（需要单独请求 URL）
     * - mg：咪咕歌词（从 meta.mrcUrl/lrcUrl 下载并解密）+ 封面（来自搜索结果 coverUrl）
     * - tx：QQ 歌词（3DES 解密 QRC）+ 封面（来自搜索结果 coverUrl）
     */
    suspend fun fetchFullMetadata(info: OnlineMusicInfo): MusicTags {
        val tags = MusicTags(
            title = info.name,
            artist = info.singer,
            album = info.album,
        )

        // 歌词
        runCatching {
            when (info.sourceId) {
                MusicSource.NETEASE -> {
                    val lyrics = onlineService.getNeteaseLyrics(info.id)
                    val lyric = lyrics?.get("lyric")
                    if (!lyric.isNullOrBlank()) tags.lyrics = lyric
                }
                MusicSource.KUGOU -> {
                    val (lyric, _) = onlineService.getKuGouLyrics(info)
                    if (!lyric.isNullOrBlank()) tags.lyrics = lyric
                }
                MusicSource.KUWO -> {
                    val (lyric, _) = kuwoService.getLyrics(info.id)
                    if (!lyric.isNullOrBlank()) tags.lyrics = lyric
                }
                MusicSource.MIGU -> {
                    val (lyric, tlyric) = miguService.getLyrics(info)
                    if (!lyric.isNullOrBlank()) tags.lyrics = lyric
                    if (!tlyric.isNullOrBlank()) {
                        // 简单合并翻译：把翻译接到主歌词末尾
                        tags.lyrics = (tags.lyrics ?: "") + "\n\n[翻译]\n$tlyric"
                    }
                }
                MusicSource.QQ -> {
                    val (lyric, tlyric) = qqMusicService.getLyrics(info)
                    if (!lyric.isNullOrBlank()) tags.lyrics = lyric
                    if (!tlyric.isNullOrBlank()) {
                        tags.lyrics = (tags.lyrics ?: "") + "\n\n[翻译]\n$tlyric"
                    }
                }
            }
        }.onFailure { Log.w(TAG, "Get lyrics failed (${info.sourceId}): ${it.message}") }

        // 封面
        runCatching {
            val coverUrl = when (info.sourceId) {
                MusicSource.NETEASE -> info.coverUrl ?: onlineService.getNeteaseCover(info.id)
                MusicSource.KUGOU -> onlineService.getKuGouCover(info)
                MusicSource.KUWO -> kuwoService.getCoverUrl(info.id)
                MusicSource.MIGU -> info.coverUrl ?: info.meta["picUrl"]
                MusicSource.QQ -> info.coverUrl
                else -> info.coverUrl
            }
            if (coverUrl == null) {
                Log.w(TAG, "Cover URL is null (${info.sourceId}), name=${info.name}")
            } else {
                val coverData = onlineService.downloadCover(coverUrl)
                if (coverData != null) {
                    tags.coverData = coverData
                    tags.coverMime = "image/jpeg"
                    tags.hasCover = true
                } else {
                    Log.w(TAG, "Cover download failed (${info.sourceId}), url=$coverUrl")
                }
            }
        }.onFailure { Log.w(TAG, "Get cover failed (${info.sourceId}): ${it.message}") }

        return tags
    }

    /**
     * 写入标签
     */
    suspend fun writeTagsToFile(filePath: String, tags: MusicTags): Boolean = withContext(Dispatchers.IO) {
        tagService.writeTags(filePath, tags)
    }

    fun dispose() {
        onlineService.dispose()
    }
}
