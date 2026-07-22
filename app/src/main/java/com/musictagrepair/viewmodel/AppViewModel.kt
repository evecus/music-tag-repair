package com.musictagrepair.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.musictagrepair.data.FileStatus
import com.musictagrepair.data.MusicTags
import com.musictagrepair.data.OnlineMusicInfo
import com.musictagrepair.data.RepairEngine
import com.musictagrepair.data.TagService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * UI 状态
 */
data class UiState(
    val scanning: Boolean = false,
    val scanStatus: String = "",
    val scanPhase: ScanPhase = ScanPhase.Idle,
    val discoveredCount: Int = 0,
    val scannedCount: Int = 0,
    val scanTotal: Int = 0,
    val currentFileName: String = "",
    val files: List<FileStatus> = emptyList(),
    val searching: Boolean = false,
    val searchResults: List<OnlineMusicInfo> = emptyList(),
    val repairing: Boolean = false,
    val repairMessage: String = "",
    val currentFile: FileStatus? = null,
    val allFilesAccessGranted: Boolean = true,
    val batchRepairing: Boolean = false,
    val batchTotal: Int = 0,
    val batchDone: Int = 0,
    val batchSucceeded: Int = 0,
    val batchFailed: Int = 0,
    val batchCurrentFileName: String = "",
    val batchSummary: String? = null,
    /** 已从持久化恢复过列表的标志，避免 ScanScreen 启动时立即跳转。 */
    val hydrated: Boolean = false,
) {
    val totalCount: Int get() = files.size
    val completeCount: Int get() = files.count { it.report.isComplete }
    val incompleteCount: Int get() = files.count { !it.report.isComplete }

    val scanProgress: Float
        get() = when (scanPhase) {
            ScanPhase.Discovering -> -1f
            ScanPhase.ReadingTags -> if (scanTotal > 0) scannedCount.toFloat() / scanTotal else 0f
            else -> 0f
        }
}

enum class ScanPhase {
    Idle,
    Discovering,
    ReadingTags,
    Done,
}

/**
 * 全局 ViewModel
 */
class AppViewModel(
    app: Application,
) : AndroidViewModel(app) {

    private val engine = RepairEngine()
    private val rootPathResolver = RootPathResolver(app)
    private val store = FileListStore(app)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        refreshPermissions()
        // 启动时异步从 SharedPreferences 恢复文件列表，避免每次重新扫描
        viewModelScope.launch {
            val saved = store.load()
            _uiState.update {
                it.copy(
                    files = saved,
                    hydrated = true,
                    scanStatus = if (saved.isNotEmpty()) "已恢复 ${saved.size} 个文件" else "",
                    scanPhase = if (saved.isNotEmpty()) ScanPhase.Done else ScanPhase.Idle,
                )
            }
        }
    }

    fun refreshPermissions() {
        _uiState.update {
            it.copy(allFilesAccessGranted = PermissionUtil.hasAllFilesAccess())
        }
    }

    /**
     * 仅解析 SAF tree Uri 为可读路径（用于 UI 展示），不触发扫描。
     */
    fun resolveDir(treeUri: Uri): String? = rootPathResolver.resolve(treeUri)

    fun scanDirectory(treeUri: Uri) {
        viewModelScope.launch {
            val realPath = rootPathResolver.resolve(treeUri)
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = if (realPath != null) "正在通过媒体库发现 $realPath 下的音频..."
                    else "SAF 目录无法解析为路径，将扫描全设备音频...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = realPath)
        }
    }

    fun scanAllMedia() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = "正在通过媒体库发现音频...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = null)
        }
    }

    private suspend fun doScanViaMediaStore(pathPrefix: String?) {
        try {
            val files = engine.scanViaMediaStore(
                context = getApplication(),
                pathPrefix = pathPrefix,
                onDiscovered = { count, name ->
                    if (count <= 5 || count % 20 == 0) {
                        _uiState.update {
                            it.copy(
                                discoveredCount = count,
                                currentFileName = name,
                                scanStatus = "正在发现音频 $count ...",
                            )
                        }
                    }
                },
                onTagsRead = { done, total, name ->
                    if (done <= 5 || done % 5 == 0 || done == total) {
                        _uiState.update {
                            it.copy(
                                scanPhase = ScanPhase.ReadingTags,
                                scannedCount = done,
                                scanTotal = total,
                                currentFileName = name,
                                scanStatus = "读取标签 $done / $total ...",
                            )
                        }
                    }
                },
            )
            _uiState.update {
                it.copy(
                    scanning = false,
                    scanPhase = ScanPhase.Done,
                    scanStatus = "扫描完成，共 ${files.size} 个文件",
                    files = files,
                    currentFileName = "",
                )
            }
            // 持久化文件列表，下次启动时直接恢复
            store.save(files)
        } catch (e: Throwable) {
            _uiState.update {
                it.copy(
                    scanning = false,
                    scanPhase = ScanPhase.Idle,
                    scanStatus = "扫描失败: ${e.message ?: e.javaClass.simpleName}",
                )
            }
        }
    }

    fun scanDirectoryPath(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    scanning = true,
                    scanPhase = ScanPhase.Discovering,
                    scanStatus = "正在通过媒体库扫描 $path ...",
                    discoveredCount = 0,
                    scannedCount = 0,
                    scanTotal = 0,
                    currentFileName = "",
                )
            }
            doScanViaMediaStore(pathPrefix = path)
        }
    }

    fun searchOnline(file: FileStatus) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentFile = file,
                    searching = true,
                    searchResults = emptyList(),
                    repairMessage = "",
                )
            }
            try {
                val results = engine.searchOnline(file)
                _uiState.update {
                    it.copy(searching = false, searchResults = results)
                }
            } catch (e: Throwable) {
                _uiState.update {
                    it.copy(searching = false, searchResults = emptyList())
                }
            }
        }
    }

    /**
     * 单文件修复（合并模式）：仅补充缺失字段，已有字段保留。
     */
    fun repair(info: OnlineMusicInfo, onDone: (Boolean) -> Unit) {
        val current = _uiState.value.currentFile ?: run {
            onDone(false); return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(repairing = true, repairMessage = "正在获取元数据...") }
            // 让 Compose 先渲染"修复中"状态，避免按了没反应的感觉
            kotlinx.coroutines.yield()
            try {
                val tags = engine.fetchFullMetadata(info)
                val existing = current.report.currentTags
                val merged = MusicTags(
                    title = tags.title ?: existing.title,
                    artist = tags.artist ?: existing.artist,
                    album = tags.album ?: existing.album,
                    albumArtist = tags.albumArtist ?: existing.albumArtist,
                    year = tags.year ?: existing.year,
                    trackNumber = tags.trackNumber ?: existing.trackNumber,
                    trackTotal = tags.trackTotal ?: existing.trackTotal,
                    discNumber = tags.discNumber ?: existing.discNumber,
                    discTotal = tags.discTotal ?: existing.discTotal,
                    genre = tags.genre ?: existing.genre,
                    coverMime = tags.coverMime ?: existing.coverMime,
                    coverData = tags.coverData,
                    hasCover = tags.hasCover || (tags.coverData?.isNotEmpty() == true) || existing.hasCover,
                    lyrics = tags.lyrics ?: existing.lyrics,
                    durationMs = existing.durationMs,
                    bitrate = existing.bitrate,
                    sampleRate = existing.sampleRate,
                    channels = existing.channels,
                )

                _uiState.update { it.copy(repairMessage = "正在写入标签...") }
                kotlinx.coroutines.yield()
                val success = engine.writeTagsToFile(current.path, merged)

                // 立即释放大字节数组，避免后续步骤继续持有
                merged.coverData = null

                if (success) {
                    // readTags 已不返回 coverData，只设 hasCover，避免内存累积
                    val newTags = TagService.readTags(current.path) ?: merged.copy(coverData = null)
                    val newReport = com.musictagrepair.data.CompletenessReport.check(newTags)
                    val newFile = current.copy(report = newReport)
                    _uiState.update { ui ->
                        ui.copy(
                            files = ui.files.map { if (it.path == current.path) newFile else it },
                            currentFile = newFile,
                            repairMessage = "修复成功",
                        )
                    }
                    // 更新持久化
                    store.save(_uiState.value.files)
                } else {
                    _uiState.update { it.copy(repairMessage = "写入失败") }
                }

                onDone(success)
            } catch (e: Throwable) {
                _uiState.update { it.copy(repairMessage = "修复失败: ${e.message}") }
                onDone(false)
            } finally {
                _uiState.update { it.copy(repairing = false) }
            }
        }
    }

    /**
     * 单文件替换（覆盖模式）：用搜索结果覆盖现有标签。
     * 仅保留文件本身的音频属性（duration / bitrate / sampleRate / channels），
     * 其余元数据（title/artist/album/year/track/genre/lyrics/cover）一律以搜索结果为准。
     *
     * 用于完整文件的情况下，用户认为在线搜索到的信息更准确时。
     */
    fun repairReplace(info: OnlineMusicInfo, onDone: (Boolean) -> Unit) {
        val current = _uiState.value.currentFile ?: run {
            onDone(false); return
        }
        viewModelScope.launch {
            _uiState.update { it.copy(repairing = true, repairMessage = "正在获取元数据...") }
            kotlinx.coroutines.yield()
            try {
                val tags = engine.fetchFullMetadata(info)
                val existing = current.report.currentTags
                val replaced = MusicTags(
                    title = tags.title,
                    artist = tags.artist,
                    album = tags.album,
                    albumArtist = tags.albumArtist,
                    year = tags.year,
                    trackNumber = tags.trackNumber,
                    trackTotal = tags.trackTotal,
                    discNumber = tags.discNumber,
                    discTotal = tags.discTotal,
                    genre = tags.genre,
                    coverMime = tags.coverMime,
                    coverData = tags.coverData,
                    hasCover = tags.hasCover || (tags.coverData?.isNotEmpty() == true),
                    lyrics = tags.lyrics,
                    // 这些是文件本身固有属性，搜索结果不提供，保留原值
                    durationMs = existing.durationMs,
                    bitrate = existing.bitrate,
                    sampleRate = existing.sampleRate,
                    channels = existing.channels,
                )

                _uiState.update { it.copy(repairMessage = "正在覆盖写入标签...") }
                kotlinx.coroutines.yield()
                val success = engine.writeTagsToFile(current.path, replaced)

                replaced.coverData = null
                tags.coverData = null

                if (success) {
                    val newTags = TagService.readTags(current.path) ?: replaced.copy(coverData = null)
                    val newReport = com.musictagrepair.data.CompletenessReport.check(newTags)
                    val newFile = current.copy(report = newReport)
                    _uiState.update { ui ->
                        ui.copy(
                            files = ui.files.map { if (it.path == current.path) newFile else it },
                            currentFile = newFile,
                            repairMessage = "替换成功",
                        )
                    }
                    store.save(_uiState.value.files)
                } else {
                    _uiState.update { it.copy(repairMessage = "写入失败") }
                }

                onDone(success)
            } catch (e: Throwable) {
                _uiState.update { it.copy(repairMessage = "替换失败: ${e.message}") }
                onDone(false)
            } finally {
                _uiState.update { it.copy(repairing = false) }
            }
        }
    }

    /**
     * 批量修复：遍历待修复文件，对每个文件搜索 → 取首条 → 拉元数据 → 写入 → 重新读 tags。
     *
     * 内存安全关键点：
     * 1. 启动后先 `_uiState.update { batchRepairing = true }` + `withContext(Main).yield()` 让 Compose
     *    先渲染进度卡片，避免用户感觉"按了没反应"
     * 2. 每文件用独立 try/catch 隔离，单文件失败不影响后续
     * 3. fetchFullMetadata 返回的 coverData 在写完立即 = null 释放，不进 UiState
     * 4. readTags 已不加载 coverData，只设 hasCover，列表内存占用稳定
     * 5. 每 5 个文件做一次持久化，避免崩溃丢全部进度
     */
    fun repairAll(onlyMissing: Boolean = true) {
        if (_uiState.value.batchRepairing) return
        if (_uiState.value.scanning) return

        val pending = _uiState.value.files.filter {
            !it.report.isComplete || !onlyMissing
        }
        if (pending.isEmpty()) {
            _uiState.update { it.copy(batchSummary = "没有需要修复的文件") }
            return
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    batchRepairing = true,
                    batchTotal = pending.size,
                    batchDone = 0,
                    batchSucceeded = 0,
                    batchFailed = 0,
                    batchCurrentFileName = "",
                    batchSummary = null,
                )
            }
            // 关键：让出主线程，让 Compose 把进度卡片渲染出来再开始干活
            kotlinx.coroutines.yield()
            // 再多等一帧，确保 BatchProgressBar 真的可见
            delay(50)

            var succeeded = 0
            var failed = 0
            for ((index, file) in pending.withIndex()) {
                _uiState.update {
                    it.copy(
                        batchCurrentFileName = file.filename,
                        batchDone = index,
                    )
                }

                val ok = try {
                    val results = engine.searchOnline(file)
                    val first = results.firstOrNull()
                    if (first == null) {
                        failed++
                        false
                    } else {
                        val tags = engine.fetchFullMetadata(first)
                        val existing = file.report.currentTags
                        val merged = MusicTags(
                            title = tags.title ?: existing.title,
                            artist = tags.artist ?: existing.artist,
                            album = tags.album ?: existing.album,
                            albumArtist = tags.albumArtist ?: existing.albumArtist,
                            year = tags.year ?: existing.year,
                            trackNumber = tags.trackNumber ?: existing.trackNumber,
                            trackTotal = tags.trackTotal ?: existing.trackTotal,
                            discNumber = tags.discNumber ?: existing.discNumber,
                            discTotal = tags.discTotal ?: existing.discTotal,
                            genre = tags.genre ?: existing.genre,
                            coverMime = tags.coverMime ?: existing.coverMime,
                            coverData = tags.coverData,
                            hasCover = tags.hasCover || (tags.coverData?.isNotEmpty() == true) || existing.hasCover,
                            lyrics = tags.lyrics ?: existing.lyrics,
                            durationMs = existing.durationMs,
                            bitrate = existing.bitrate,
                            sampleRate = existing.sampleRate,
                            channels = existing.channels,
                        )

                        val writeOk = engine.writeTagsToFile(file.path, merged)
                        // 立即释放大字节数组
                        merged.coverData = null
                        tags.coverData = null

                        if (writeOk) {
                            val newTags = TagService.readTags(file.path) ?: merged.copy(coverData = null)
                            val newReport = com.musictagrepair.data.CompletenessReport.check(newTags)
                            val newFile = file.copy(report = newReport)
                            _uiState.update { ui ->
                                ui.copy(
                                    files = ui.files.map { if (it.path == file.path) newFile else it },
                                )
                            }
                            succeeded++
                        } else {
                            failed++
                        }
                        writeOk
                    }
                } catch (e: Throwable) {
                    failed++
                    false
                }

                _uiState.update {
                    it.copy(
                        batchDone = index + 1,
                        batchSucceeded = succeeded,
                        batchFailed = failed,
                    )
                }

                // 让出主线程让 UI 刷新进度
                kotlinx.coroutines.yield()

                // 每 5 个文件持久化一次，崩溃时能保留进度
                if ((index + 1) % 5 == 0) {
                    store.save(_uiState.value.files)
                }
            }

            // 最终持久化
            store.save(_uiState.value.files)

            _uiState.update {
                it.copy(
                    batchRepairing = false,
                    batchCurrentFileName = "",
                    batchDone = pending.size,
                    batchSucceeded = succeeded,
                    batchFailed = failed,
                    batchSummary = "批量修复完成：成功 $succeeded / 失败 $failed / 共 ${pending.size}",
                )
            }
        }
    }

    fun dismissBatchSummary() {
        _uiState.update { it.copy(batchSummary = null) }
    }

    /**
     * 重新扫描当前目录
     */
    fun rescan() {
        val first = _uiState.value.files.firstOrNull()
        if (first == null) {
            scanAllMedia()
        } else {
            val dir = first.path.substringBeforeLast('/')
            scanDirectoryPath(dir)
        }
    }

    fun clearCurrentFile() {
        _uiState.update {
            it.copy(
                currentFile = null,
                searchResults = emptyList(),
                repairMessage = "",
            )
        }
    }

    /** 清空持久化并清空当前列表。 */
    fun clearFiles() {
        viewModelScope.launch {
            store.clear()
            _uiState.update {
                it.copy(
                    files = emptyList(),
                    scanPhase = ScanPhase.Idle,
                    scanStatus = "",
                )
            }
        }
    }

    override fun onCleared() {
        engine.dispose()
        super.onCleared()
    }
}

/**
 * 文件列表持久化：用 SharedPreferences 存 JSON。
 *
 * 不存 [MusicTags.coverData]（本来 readTags 也不读了），避免序列化大字节数组。
 * 数据量约 1000 首 × 200 字节 = 200KB，SharedPreferences 完全够用。
 */
private class FileListStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("file_list", Context.MODE_PRIVATE)
    private val key = "files_json"

    fun load(): List<FileStatus> {
        return try {
            val json = prefs.getString(key, null) ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val t = o.getJSONObject("tags")
                val tags = MusicTags(
                    title = t.optString("title").takeIf { it.isNotEmpty() },
                    artist = t.optString("artist").takeIf { it.isNotEmpty() },
                    album = t.optString("album").takeIf { it.isNotEmpty() },
                    albumArtist = t.optString("albumArtist").takeIf { it.isNotEmpty() },
                    year = if (t.has("year") && !t.isNull("year")) t.getInt("year") else null,
                    trackNumber = if (t.has("track") && !t.isNull("track")) t.getInt("track") else null,
                    trackTotal = if (t.has("trackTotal") && !t.isNull("trackTotal")) t.getInt("trackTotal") else null,
                    discNumber = if (t.has("discNo") && !t.isNull("discNo")) t.getInt("discNo") else null,
                    discTotal = if (t.has("discTotal") && !t.isNull("discTotal")) t.getInt("discTotal") else null,
                    genre = t.optString("genre").takeIf { it.isNotEmpty() },
                    hasCover = t.optBoolean("hasCover", false),
                    lyrics = t.optString("lyrics").takeIf { it.isNotEmpty() },
                    durationMs = t.optLong("durationMs", 0L),
                    bitrate = t.optInt("bitrate", 0),
                    sampleRate = t.optInt("sampleRate", 0),
                    channels = t.optInt("channels", 0),
                )
                FileStatus(
                    path = o.getString("path"),
                    filename = o.getString("filename"),
                    report = com.musictagrepair.data.CompletenessReport.check(tags),
                )
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    fun save(files: List<FileStatus>) {
        try {
            val arr = JSONArray()
            for (f in files) {
                val t = f.report.currentTags
                val tagsJson = JSONObject().apply {
                    put("title", t.title ?: "")
                    put("artist", t.artist ?: "")
                    put("album", t.album ?: "")
                    put("albumArtist", t.albumArtist ?: "")
                    put("year", t.year ?: JSONObject.NULL)
                    put("track", t.trackNumber ?: JSONObject.NULL)
                    put("trackTotal", t.trackTotal ?: JSONObject.NULL)
                    put("discNo", t.discNumber ?: JSONObject.NULL)
                    put("discTotal", t.discTotal ?: JSONObject.NULL)
                    put("genre", t.genre ?: "")
                    put("hasCover", t.hasCover)
                    put("lyrics", t.lyrics ?: "")
                    put("durationMs", t.durationMs)
                    put("bitrate", t.bitrate)
                    put("sampleRate", t.sampleRate)
                    put("channels", t.channels)
                }
                val o = JSONObject().apply {
                    put("path", f.path)
                    put("filename", f.filename)
                    put("tags", tagsJson)
                }
                arr.put(o)
            }
            prefs.edit().putString(key, arr.toString()).apply()
        } catch (_: Throwable) {
            // 持久化失败不影响主流程
        }
    }

    fun clear() {
        prefs.edit().remove(key).apply()
    }
}
