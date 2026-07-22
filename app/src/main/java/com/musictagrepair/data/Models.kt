package com.musictagrepair.data

/**
 * 音乐标签数据。
 *
 * **注意**：[coverData] 是大字节数组（数百 KB ~ 数 MB），不应进入 UI State。
 * 列表扫描 / 批量修复时只关心 [hasCover]，写封面时才通过 [TagService.writeCover] 单独写入。
 */
data class MusicTags(
    var title: String? = null,
    var artist: String? = null,
    var album: String? = null,
    var albumArtist: String? = null,
    var year: Int? = null,
    var trackNumber: Int? = null,
    var trackTotal: Int? = null,
    var discNumber: Int? = null,
    var discTotal: Int? = null,
    var genre: String? = null,
    /** 是否含封面。避免在 StateFlow 里持有大字节数组。 */
    var hasCover: Boolean = false,
    var coverMime: String? = null,
    /** 仅在写入文件路径上临时使用，**不要**把此字段放到 UiState 里。 */
    var coverData: ByteArray? = null,
    var lyrics: String? = null,
    var durationMs: Long = 0L,
    var bitrate: Int = 0,
    var sampleRate: Int = 0,
    var channels: Int = 0,
) {
    val hasTag: Boolean get() = !title.isNullOrBlank() || !artist.isNullOrBlank() || !album.isNullOrBlank()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as MusicTags
        return title == other.title && artist == other.artist && album == other.album
    }

    override fun hashCode(): Int {
        var result = title?.hashCode() ?: 0
        result = 31 * result + (artist?.hashCode() ?: 0)
        result = 31 * result + (album?.hashCode() ?: 0)
        return result
    }
}

/**
 * 缺失字段
 */
enum class MissingField(val label: String) {
    TITLE("标题"),
    ARTIST("歌手"),
    ALBUM("专辑"),
    COVER("封面"),
    LYRICS("歌词");
}

/**
 * 完整性报告
 */
data class CompletenessReport(
    val hasTitle: Boolean,
    val hasArtist: Boolean,
    val hasAlbum: Boolean,
    val hasCover: Boolean,
    val hasLyrics: Boolean,
    val missingFields: List<MissingField>,
    val currentTags: MusicTags,
    val score: Int,
) {
    val isComplete: Boolean get() = missingFields.isEmpty()

    companion object {
        fun check(tags: MusicTags): CompletenessReport {
            val missing = mutableListOf<MissingField>()

            val hasTitle = !tags.title.isNullOrBlank()
            if (!hasTitle) missing.add(MissingField.TITLE)

            val hasArtist = !tags.artist.isNullOrBlank()
            if (!hasArtist) missing.add(MissingField.ARTIST)

            val hasAlbum = !tags.album.isNullOrBlank()
            if (!hasAlbum) missing.add(MissingField.ALBUM)

            // 优先使用 hasCover 标记；兼容老路径下还残留 coverData 的情况
            val hasCover = tags.hasCover || (tags.coverData?.isNotEmpty() == true)
            if (!hasCover) missing.add(MissingField.COVER)

            val lyricsText = tags.lyrics
            val hasLyrics = lyricsText != null && lyricsText.length > 10
            if (!hasLyrics) missing.add(MissingField.LYRICS)

            val total = 5
            val complete = total - missing.size
            val score = (complete * 100 / total)

            return CompletenessReport(
                hasTitle = hasTitle,
                hasArtist = hasArtist,
                hasAlbum = hasAlbum,
                hasCover = hasCover,
                hasLyrics = hasLyrics,
                missingFields = missing,
                currentTags = tags,
                score = score,
            )
        }
    }
}

/**
 * 文件状态
 */
data class FileStatus(
    val path: String,
    val filename: String,
    val report: CompletenessReport,
)

/**
 * 在线音乐信息
 *
 * [meta] 用于保存各平台后续获取歌词/封面所需的字段：
 * - wy（网易云）：id 即歌曲 id，直接用于歌词/封面接口
 * - kg（酷狗）：id 即 hash，直接用于歌词/封面接口
 * - kw（酷我）：id 即 MUSICRID 去掉前缀的 rid
 * - mg（咪咕）：id 是 songId；meta 额外存 contentId / copyrightId / lrcUrl / mrcUrl / trcUrl / picUrl
 * - tx（QQ）：id 是 song mid；meta 额外存 songId（数字 id，用于歌词接口）/ albumMid（用于封面拼 URL）
 */
data class OnlineMusicInfo(
    val id: String,
    val name: String,
    val singer: String,
    val album: String,
    val interval: String? = null,
    val coverUrl: String? = null,
    val lyrics: String? = null,
    val tlyrics: String? = null,
    val rlyrics: String? = null,
    val sourceId: String,
    /** 平台额外元数据，键值对形式。详见类注释。 */
    val meta: Map<String, String> = emptyMap(),
)

/** 各平台标识。 */
object MusicSource {
    const val NETEASE = "wy"
    const val KUGOU = "kg"
    const val KUWO = "kw"
    const val MIGU = "mg"
    const val QQ = "tx"

    /** 用户可读的中文标签。 */
    fun label(sourceId: String): String = when (sourceId) {
        NETEASE -> "网易云"
        KUGOU -> "酷狗"
        KUWO -> "酷我"
        MIGU -> "咪咕"
        QQ -> "QQ"
        else -> sourceId
    }

    /** 用于 UI 标签的配色。返回 ARGB 颜色值。 */
    fun color(sourceId: String): Long = when (sourceId) {
        NETEASE -> 0xFFE53935
        KUGOU -> 0xFF1E88E5
        KUWO -> 0xFFFFA726
        MIGU -> 0xFFE91E63
        QQ -> 0xFF26C6DA
        else -> 0xFF9E9E9E
    }
}
