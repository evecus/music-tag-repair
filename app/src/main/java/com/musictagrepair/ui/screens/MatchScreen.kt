package com.musictagrepair.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.musictagrepair.data.OnlineMusicInfo
import com.musictagrepair.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MatchScreen(
    viewModel: AppViewModel,
    onNavigateToResult: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val current = state.currentFile
    // 当前等待用户确认（修复 / 替换）的搜索结果，非 null 时显示选择弹窗
    var pendingAction by remember { mutableStateOf<OnlineMusicInfo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("匹配在线信息") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // 当前文件信息
            current?.let { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "本地文件",
                            fontSize = 12.sp,
                            color = Color.Gray,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            file.report.currentTags.title ?: file.filename,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        file.report.currentTags.artist?.let {
                            Text(it, fontSize = 13.sp, color = Color.Gray, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }

            if (state.searching) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(12.dp))
                        Text("正在搜索...", color = Color.Gray)
                    }
                }
                return@Column
            }

            if (state.searchResults.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("未找到匹配结果", color = Color.Gray)
                }
                return@Column
            }

            // 搜索结果
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(state.searchResults) { info ->
                    ResultItem(
                        info = info,
                        enabled = !state.repairing,
                        onClick = { pendingAction = info },
                    )
                }
            }

            if (state.repairing) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.size(10.dp))
                        Text(
                            state.repairMessage.ifBlank { "正在写入标签..." },
                            color = Color.Gray,
                            fontSize = 13.sp,
                        )
                    }
                }
            } else if (state.repairMessage.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(state.repairMessage, color = Color.Gray, fontSize = 13.sp)
                }
            }
        }
    }

    // 修复 / 替换选择弹窗
    pendingAction?.let { info ->
        val currentFile = state.currentFile
        val isComplete = currentFile?.report?.isComplete ?: false
        AlertDialog(
            onDismissRequest = { pendingAction = null },
            title = { Text("选择操作") },
            text = {
                Column {
                    Text(
                        "搜索结果：",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${info.name} - ${info.singer.ifBlank { "未知歌手" }}",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                    )
                    if (info.album.isNotBlank()) {
                        Text(
                            "专辑：${info.album}",
                            fontSize = 12.sp,
                            color = Color.Gray,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (isComplete)
                            "此文件标签已完整。可选择「修复」（仅补充缺失字段）或「替换」（用搜索结果覆盖现有标签）。"
                        else
                            "此文件标签不完整。点击「修复」将用此搜索结果补充缺失字段。",
                        fontSize = 12.sp,
                        color = Color.Gray,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val i = info
                        pendingAction = null
                        viewModel.repair(i) { success ->
                            if (success) onNavigateToResult()
                        }
                    },
                ) { Text("修复") }
            },
            dismissButton = {
                if (isComplete) {
                    // 完整文件提供"替换"选项，覆盖现有标签
                    TextButton(
                        onClick = {
                            val i = info
                            pendingAction = null
                            viewModel.repairReplace(i) { success ->
                                if (success) onNavigateToResult()
                            }
                        },
                    ) { Text("替换", color = Color(0xFFEF5350)) }
                } else {
                    // 不完整的文件不显示"替换"，只有"取消"
                    TextButton(onClick = { pendingAction = null }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun ResultItem(info: OnlineMusicInfo, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .then(
                if (enabled) Modifier.clickable { onClick() }
                else Modifier
            ),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.surface)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 封面
            if (info.coverUrl != null) {
                AsyncImage(
                    model = info.coverUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.LightGray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("🎵", fontSize = 24.sp)
                }
            }
            Spacer(Modifier.size(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    info.name,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    info.singer.ifBlank { "未知歌手" },
                    fontSize = 13.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (info.album.isNotBlank()) {
                    Text(
                        info.album,
                        fontSize = 12.sp,
                        color = Color.LightGray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            info.interval?.let {
                Text(it, fontSize = 12.sp, color = Color.LightGray)
            }
            // 源标签
            val srcColor = Color(com.musictagrepair.data.MusicSource.color(info.sourceId))
            Box(
                modifier = Modifier
                    .padding(start = 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(srcColor.copy(alpha = 0.1f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    com.musictagrepair.data.MusicSource.label(info.sourceId),
                    fontSize = 10.sp,
                    color = srcColor,
                    fontWeight = FontWeight.Medium,
                )
            }
            // 写入图标提示
            if (enabled) {
                Icon(
                    Icons.Filled.Save,
                    contentDescription = "写入此标签到文件",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp).size(20.dp),
                )
            }
        }
    }
}
