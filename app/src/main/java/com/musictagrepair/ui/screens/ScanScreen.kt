package com.musictagrepair.ui.screens

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.musictagrepair.viewmodel.AppViewModel
import com.musictagrepair.viewmodel.PermissionUtil
import com.musictagrepair.viewmodel.ScanPhase

/** 扫描范围。 */
enum class ScanScope { All, Dir }

@Composable
fun ScanScreen(
    viewModel: AppViewModel,
    onNavigateToFiles: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var scanScope by remember { mutableStateOf(ScanScope.All) }
    var selectedDirPath by remember { mutableStateOf<String?>(null) }
    var selectedTreeUri by remember { mutableStateOf<Uri?>(null) }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    // SAF 目录选择
    val dirPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            try {
                val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                context.contentResolver.takePersistableUriPermission(uri, flags)
            } catch (_: Throwable) {}
            selectedTreeUri = uri
            // 立即解析路径用于显示；解析失败也允许继续（VM 内部会处理）
            selectedDirPath = viewModel.resolveDir(uri) ?: uri.toString()
        }
    }

    LaunchedEffect(Unit) {
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            perms.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            perms.add(Manifest.permission.READ_MEDIA_AUDIO)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (perms.isNotEmpty()) {
            val toRequest = perms.filter {
                ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isNotEmpty()) permissionLauncher.launch(toRequest.toTypedArray())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // 标题
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.LibraryMusic,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.size(12.dp))
            Column {
                Text("音乐标签修复", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                Text("选择扫描范围并开始", fontSize = 12.sp, color = Color.Gray)
            }
        }

        // All Files Access 未授权提示
        if (!state.allFilesAccessGranted) {
            AllFilesAccessBanner(
                onClick = { PermissionUtil.requestAllFilesAccess(context) },
            )
        }

        // 扫描范围标题
        Text(
            "扫描范围",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            modifier = Modifier.padding(top = 4.dp),
        )

        // 选项 1：全设备音频
        ScopeCard(
            selected = scanScope == ScanScope.All,
            onClick = {
                scanScope = ScanScope.All
                selectedTreeUri = null
                selectedDirPath = null
            },
            icon = Icons.Filled.GraphicEq,
            title = "全设备音频",
            subtitle = "通过媒体库扫描本机所有音频文件（推荐）",
        )

        // 选项 2：特定目录音频
        ScopeCard(
            selected = scanScope == ScanScope.Dir,
            onClick = { scanScope = ScanScope.Dir },
            icon = Icons.Filled.Folder,
            title = "特定目录音频",
            subtitle = selectedDirPath ?: "点击下方按钮选择目录",
        )

        // 选目录按钮（仅 Dir 模式显示）
        if (scanScope == ScanScope.Dir) {
            OutlinedButton(
                onClick = { dirPicker.launch(null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Icon(Icons.Filled.FolderOpen, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (selectedDirPath == null) "选择目录" else "更换目录")
            }
        }

        Spacer(Modifier.size(4.dp))

        // 开始扫描按钮
        Button(
            onClick = {
                when (scanScope) {
                    ScanScope.All -> viewModel.scanAllMedia()
                    ScanScope.Dir -> {
                        val uri = selectedTreeUri
                        val path = selectedDirPath
                        if (uri != null) viewModel.scanDirectory(uri)
                        else if (path != null) viewModel.scanDirectoryPath(path)
                    }
                }
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = !state.scanning &&
                (scanScope == ScanScope.All || selectedTreeUri != null || selectedDirPath != null),
        ) {
            Icon(Icons.Filled.Search, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(if (state.scanning) "扫描中..." else "开始扫描")
        }

        // 扫描进度
        if (state.scanning) {
            ScanProgressBar(state)
        } else if (state.scanStatus.isNotBlank() && state.scanPhase == ScanPhase.Done) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE8F5E9))
                    .padding(12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    state.scanStatus,
                    color = Color(0xFF2E7D32),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                )
            }
        }

        Spacer(Modifier.weight(1f))

        // 进入音乐目录按钮（仅当有结果时可点）
        Button(
            onClick = onNavigateToFiles,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = RoundedCornerShape(14.dp),
            enabled = state.files.isNotEmpty() && !state.scanning,
        ) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null)
            Spacer(Modifier.size(8.dp))
            Text(
                if (state.files.isNotEmpty())
                    "进入音乐目录（${state.files.size} 首）"
                else
                    "进入音乐目录",
            )
        }
    }
}

@Composable
private fun ScopeCard(
    selected: Boolean,
    onClick: () -> Unit,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
) {
    val borderColor = if (selected) MaterialTheme.colorScheme.primary else Color.LightGray.copy(alpha = 0.5f)
    val bgColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
    else MaterialTheme.colorScheme.surface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.5.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary
                    else Color.LightGray.copy(alpha = 0.3f)
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (selected) Color.White else Color.Gray,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(
                subtitle,
                fontSize = 12.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun AllFilesAccessBanner(onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFFFFF3E0))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFE65100),
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(8.dp))
            Text(
                "需要「所有文件访问」权限",
                fontWeight = FontWeight.SemiBold,
                color = Color(0xFFE65100),
                fontSize = 13.sp,
            )
        }
        Text(
            "Android 11+ 上读写音频标签需要该权限，否则扫描到文件也无法读取 / 修改标签。",
            fontSize = 11.sp,
            color = Color(0xFF6D4C41),
        )
        OutlinedButton(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("前往系统设置授权", fontSize = 13.sp)
        }
    }
}

@Composable
private fun ScanProgressBar(state: com.musictagrepair.viewmodel.UiState) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                when (state.scanPhase) {
                    ScanPhase.Discovering -> "正在发现音频文件..."
                    ScanPhase.ReadingTags -> "正在读取音频标签..."
                    else -> "扫描中..."
                },
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
        }

        when (state.scanPhase) {
            ScanPhase.Discovering -> {
                Text(
                    "已发现 ${state.discoveredCount} 个音频文件",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
            ScanPhase.ReadingTags -> {
                val progress = if (state.scanTotal > 0) {
                    state.scannedCount.toFloat() / state.scanTotal
                } else 0f
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )
                Text(
                    "${state.scannedCount} / ${state.scanTotal}",
                    fontSize = 12.sp,
                    color = Color.Gray,
                )
            }
            else -> {}
        }

        if (state.currentFileName.isNotBlank()) {
            Text(
                state.currentFileName,
                fontSize = 11.sp,
                color = Color.Gray,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
