package com.musictagrepair.data

import android.util.Log
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.audio.exceptions.CannotReadException
import org.jaudiotagger.audio.exceptions.CannotWriteException
import org.jaudiotagger.tag.FieldKey
import org.jaudiotagger.tag.images.Artwork
import org.jaudiotagger.tag.images.ArtworkFactory
import java.io.File

/**
 * 本地音乐标签读写服务（基于 jaudiotagger）
 */
object TagService {

    private const val TAG = "TagService"

    private val SUPPORTED_EXT = setOf("mp3", "flac", "m4a", "mp4", "ogg", "opus", "wav", "ape", "wma", "aac")

    fun isSupportedFile(filePath: String): Boolean {
        val ext = filePath.substringAfterLast('.', "").lowercase()
        return ext in SUPPORTED_EXT
    }

    /**
     * 读取文件标签。
     *
     * **注意**：封面字节 [MusicTags.coverData] 默认不读取（避免大数组污染内存），
     * 只设置 [MusicTags.hasCover] = true。需要拿封面字节时调 [readCover]。
     */
    fun readTags(filePath: String): MusicTags? {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canRead()) return null

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault
            val audioHeader = audioFile.audioHeader

            val artwork: Artwork? = tag.firstArtwork

            // 歌词字段（ID3 的 LYRICS/UNSYNCEDLYRICS 等）
            val lyrics = try { tag.getFirst(FieldKey.LYRICS) } catch (_: Throwable) { "" }

            MusicTags(
                title = tag.getFirst(FieldKey.TITLE).takeIf { it.isNotBlank() },
                artist = tag.getFirst(FieldKey.ARTIST).takeIf { it.isNotBlank() },
                album = tag.getFirst(FieldKey.ALBUM).takeIf { it.isNotBlank() },
                albumArtist = tag.getFirst(FieldKey.ALBUM_ARTIST).takeIf { it.isNotBlank() },
                year = tag.getFirst(FieldKey.YEAR).toIntOrNull(),
                trackNumber = tag.getFirst(FieldKey.TRACK).toIntOrNull(),
                trackTotal = tag.getFirst(FieldKey.TRACK_TOTAL).toIntOrNull(),
                discNumber = tag.getFirst(FieldKey.DISC_NO).toIntOrNull(),
                discTotal = tag.getFirst(FieldKey.DISC_TOTAL).toIntOrNull(),
                genre = tag.getFirst(FieldKey.GENRE).takeIf { it.isNotBlank() },
                coverMime = artwork?.mimeType,
                hasCover = artwork?.binaryData?.isNotEmpty() == true,
                coverData = null, // 不在常规读取中加载大字节数组
                lyrics = lyrics.takeIf { it.isNotBlank() && it.length > 10 },
                durationMs = audioHeader.trackLength * 1000L,
                bitrate = try { audioHeader.bitRateAsNumber.toInt() } catch (_: Throwable) { 0 },
                sampleRate = try {
                    audioHeader.sampleRateAsNumber.toInt()
                } catch (_: Throwable) { 0 },
                channels = 0,
            )
        } catch (e: CannotReadException) {
            Log.w(TAG, "Cannot read: $filePath - ${e.message}")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "Read tags error: $filePath - ${e.message}")
            null
        }
    }

    /**
     * 写入标签到文件
     */
    fun writeTags(filePath: String, tags: MusicTags): Boolean {
        return try {
            val file = File(filePath)
            if (!file.exists() || !file.canWrite()) return false

            val audioFile = AudioFileIO.read(file)
            val tag = audioFile.tagOrCreateAndSetDefault

            tags.title?.let { tag.setField(FieldKey.TITLE, it) }
            tags.artist?.let { tag.setField(FieldKey.ARTIST, it) }
            tags.album?.let { tag.setField(FieldKey.ALBUM, it) }
            tags.albumArtist?.let { tag.setField(FieldKey.ALBUM_ARTIST, it) }
            tags.year?.let { tag.setField(FieldKey.YEAR, it.toString()) }
            tags.trackNumber?.let { tag.setField(FieldKey.TRACK, it.toString()) }
            tags.trackTotal?.let { tag.setField(FieldKey.TRACK_TOTAL, it.toString()) }
            tags.discNumber?.let { tag.setField(FieldKey.DISC_NO, it.toString()) }
            tags.discTotal?.let { tag.setField(FieldKey.DISC_TOTAL, it.toString()) }
            tags.genre?.let { tag.setField(FieldKey.GENRE, it) }
            tags.lyrics?.let { tag.setField(FieldKey.LYRICS, it) }

            // 写封面
            val cover = tags.coverData
            if (cover != null && cover.isNotEmpty()) {
                tag.deleteArtworkField()
                val coverFile = File.createTempFile("cover", ".jpg").apply {
                    writeBytes(cover)
                    deleteOnExit()
                }
                val artwork = ArtworkFactory.createArtworkFromFile(coverFile)
                tag.setField(artwork)
            }

            audioFile.commit()
            true
        } catch (e: CannotWriteException) {
            Log.e(TAG, "Cannot write: $filePath - ${e.message}")
            false
        } catch (e: Throwable) {
            Log.e(TAG, "Write tags error: $filePath - ${e.message}")
            false
        }
    }
}
