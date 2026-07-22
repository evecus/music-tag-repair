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
     * 排序策略：标题精确匹配 > 歌手匹配 > 专辑匹配 > 其他
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

        val results = deferreds.awaitAll().flatten()

        // 排序：标题完全匹配 > 歌手包含 > 专辑包含 > 其他
        val titleLower = tags.title?.lowercase().orEmpty()
        val artistLower = tags.artist?.lowercase().orEmpty()
        results.sortedWith(
            compareByDescending<OnlineMusicInfo> { it.name.equals(tags.title ?: it.name, ignoreCase = true) }
                .thenByDescending { artistLower.isNotBlank() && it.singer.contains(artistLower, ignoreCase = true) }
                .thenByDescending { it.singer.isNotBlank() && artistLower.contains(it.singer.substringBefore("、").lowercase(), ignoreCase = true) }
                .thenBy { it.sourceId },
        )
    }

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
            if (coverUrl != null) {
                val coverData = onlineService.downloadCover(coverUrl)
                if (coverData != null) {
                    tags.coverData = coverData
                    tags.coverMime = "image/jpeg"
                    tags.hasCover = true
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
