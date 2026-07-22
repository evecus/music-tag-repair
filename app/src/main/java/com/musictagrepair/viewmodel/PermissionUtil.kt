package com.musictagrepair.viewmodel

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings

/**
 * 权限工具：负责检测 / 引导申请音频读取与 All Files Access。
 *
 * - Android 13+ (TIRAMISU)：使用 [Manifest.permission.READ_MEDIA_AUDIO]
 * - Android 13 以下：使用 [Manifest.permission.READ_EXTERNAL_STORAGE]
 * - Android 11+ (R)：可选授予 All Files Access（[Environment.isExternalStorageManager]），
 *   jaudiotagger 通过 java.io.File 读写任意路径的音频标签时需要。
 */
object PermissionUtil {

    fun hasAudioPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return context.checkSelfPermission(Manifest.permission.READ_MEDIA_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        }
        return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED
    }

    /** Android 11+ 才有 All Files Access 概念，更低版本直接返回 true（已通过 READ_EXTERNAL_STORAGE 拿到）。 */
    fun hasAllFilesAccess(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager()
        }
        return true
    }

    /** 跳转到当前应用的「所有文件访问」系统设置页。Android 11+ 才可用。 */
    fun requestAllFilesAccess(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
