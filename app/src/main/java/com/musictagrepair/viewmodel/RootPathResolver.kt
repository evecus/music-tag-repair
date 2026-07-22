package com.musictagrepair.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import androidx.documentfile.provider.DocumentFile
import java.io.File

/**
 * 将 SAF tree Uri 映射到真实文件系统路径
 *
 * 在 Android 11+ 上，普通应用无法直接访问其他应用的数据，
 * 但可以访问自身外部存储 / 公共音乐目录 / 已授权的 SAF 目录。
 *
 * 这里尝试两种策略：
 * 1. 从 tree Uri 提取相对路径，拼接到已知存储根路径下
 * 2. 直接用 DocumentFile.fromTreeUri 遍历（更安全但需要复制流）
 *
 * 为简化 jaudiotagger 的使用（它需要 java.io.File），我们采用方案 1。
 * 如果方案 1 无法访问，则提示用户。
 */
class RootPathResolver(private val app: Application) {

    /**
     * 解析 tree Uri 到文件系统路径
     *
     * tree Uri 形如:
     *   content://com.android.externalstorage.documents/tree/primary%3AMusic
     *   content://com.android.externalstorage.documents/tree/primary%3AMusic%2Fsubfolder
     *   content://com.android.externalstorage.documents/tree/1111-2222%3AMusic
     */
    fun resolve(treeUri: Uri): String? {
        val uriStr = treeUri.toString()
        if (!uriStr.startsWith("content://com.android.externalstorage.documents")) {
            // 不是外部存储，无法解析为 File
            return null
        }

        // 提取 tree 后的 path
        val treePath = treeUri.getQueryParameter("tree") ?: return null
        // primary:Music  →  /storage/emulated/0/Music
        // 1111-2222:Music  →  /storage/1111-2222/Music

        val (root, relative) = if (treePath.startsWith("primary:")) {
            "/storage/emulated/0" to treePath.removePrefix("primary:")
        } else {
            val parts = treePath.split(":", limit = 2)
            if (parts.size == 2) {
                "/storage/${parts[0]}" to parts[1]
            } else {
                "/storage/emulated/0" to ""
            }
        }

        val full = if (relative.isEmpty()) root else "$root/$relative"
        return if (File(full).exists()) full else null
    }
}
