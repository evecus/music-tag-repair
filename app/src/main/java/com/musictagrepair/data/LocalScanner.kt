package com.musictagrepair.data

import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * 本地音频文件快速发现器。
 *
 * 设计参考 coolplayer：主路径走 [MediaStore.Audio.Media] 查询（由系统 MediaProvider 维护，
 * 自动覆盖内部存储 / SD 卡 / U 盘等所有卷，查询走系统索引，速度远快于 File.walkTopDown()）。
 *
 * 同时支持 [scanDirectory]（基于 java.io.File 递归）作为回退：当用户通过 SAF 选择了
 * 一个具体目录、或需要扫描 MediaStore 尚未索引到的目录时使用。
 *
 * 进度回调 [onProgress] 在每次发现一个音频文件、或每处理一个文件读取标签时触发，
 * 用于驱动 UI 进度条更新。
 */
object LocalScanner {

    /** 与 [TagService.SUPPORTED_EXT] 保持一致的扩展名集合，带前导 '.' 用于 MediaStore 比较。 */
    private val ALLOWED_EXT: Set<String> = setOf(
        ".mp3", ".flac", ".m4a", ".mp4", ".ogg", ".opus", ".wav", ".ape", ".wma", ".aac",
    )

    data class FoundAudio(
        val path: String,
        val name: String,
        val size: Long,
        val durationMs: Long,
    )

    /**
     * 通过 [MediaStore] 一次性查询设备上所有音频文件。
     *
     * 优点：仅查询文件基本信息（路径 / 名称 / 大小 / 时长），不解析文件标签，
     * 由系统索引提供，速度极快，几千首音乐通常在数百毫秒内完成。
     *
     * @param context 任意 Context
     * @param pathPrefix 可选路径前缀过滤，仅保留以此开头的文件；为 null 表示不限制
     * @param onProgress 每发现一个文件回调一次（在 IO 线程）
     */
    fun queryAllAudio(
        context: Context,
        pathPrefix: String? = null,
        onProgress: (totalScanned: Int, currentName: String) -> Unit = { _, _ -> },
    ): List<FoundAudio> {
        val collection: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DURATION,
        )

        val out = mutableListOf<FoundAudio>()
        val prefix = pathPrefix?.trimEnd('/')

        runCatching {
            context.contentResolver.query(collection, projection, null, null, null)?.use { c ->
                val dataIdx = c.getColumnIndex(MediaStore.Audio.Media.DATA)
                val nameIdx = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val durIdx = c.getColumnIndex(MediaStore.Audio.Media.DURATION)

                while (c.moveToNext()) {
                    if (dataIdx < 0) continue
                    val path = c.getString(dataIdx) ?: continue
                    val name = if (nameIdx >= 0) c.getString(nameIdx) ?: File(path).name
                    else File(path).name

                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext.isEmpty() || ".$ext" !in ALLOWED_EXT) continue

                    if (prefix != null && !path.startsWith(prefix)) continue

                    val size = if (sizeIdx >= 0) c.getLong(sizeIdx) else 0L
                    val duration = if (durIdx >= 0) c.getLong(durIdx) else 0L

                    out.add(FoundAudio(path, name, size, duration))
                    onProgress(out.size, name)
                }
            }
        }
        return out
    }

    /**
     * 回退路径：用 [File] 递归遍历扫描指定目录。
     *
     * 仅在 MediaStore 查询失败、或用户明确指定了一个 MediaStore 未覆盖的目录时使用。
     * 对几千个文件的存储卷做深度递归 [File.listFiles] 是非常昂贵的 I/O 操作，
     * 默认不要走这条路。
     */
    fun scanDirectory(
        dirPath: String,
        maxDepth: Int = 8,
        onProgress: (totalScanned: Int, currentName: String) -> Unit = { _, _ -> },
    ): List<FoundAudio> {
        val out = mutableListOf<FoundAudio>()
        val root = File(dirPath)
        if (!root.exists() || !root.isDirectory) return emptyList()
        walk(root, maxDepth, out, onProgress)
        return out
    }

    private fun walk(
        dir: File,
        depth: Int,
        out: MutableList<FoundAudio>,
        onProgress: (Int, String) -> Unit,
    ) {
        if (depth < 0) return
        val entities = runCatching { dir.listFiles() }.getOrNull() ?: return
        for (entity in entities) {
            if (entity.isDirectory) {
                if (entity.name.startsWith('.') || entity.name == "Android") continue
                runCatching { walk(entity, depth - 1, out, onProgress) }
            } else if (entity.isFile) {
                val ext = entity.extension.let { if (it.isNotEmpty()) ".$it" else "" }.lowercase()
                if (ext !in ALLOWED_EXT) continue
                out.add(FoundAudio(entity.absolutePath, entity.name, entity.length(), 0L))
                onProgress(out.size, entity.name)
            }
        }
    }
}
