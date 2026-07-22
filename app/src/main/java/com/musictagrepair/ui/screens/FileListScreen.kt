package com.musictagrepair.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictagrepair.data.FileStatus
import com.musictagrepair.ui.components.MissingFieldsChips
import com.musictagrepair.ui.components.ScoreIndicator
import com.musictagrepair.viewmodel.AppViewModel

/** 文件列表筛选模式。 */
enum class ListFilter(val label: String) {
    All("全部"),
    Incomplete("待修复"),
    Complete("完整"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileListScreen(
    viewModel: AppViewModel,
    onNavigateToMatch: () -> Unit,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showConfirmDialog by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf(ListFilter.All) }

    // 批量修复完成后弹出 Snackbar 提示
    LaunchedEffect(state.batchSummary) {
        state.batchSummary?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissBatchSummary()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("文件列表") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回扫描页")
                    }
                },
                actions = {
                    if (state.files.isNotEmpty()) {
                        IconButton(onClick = { viewModel.rescan() }) {
                            Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.files.isEmpty()) {
            EmptyState(modifier = Modifier.padding(padding))
        } else {
            Column(modifier = Modifier
                .fillMaxSize()
                .padding(padding)) {
                // 筛选 + 统计栏
                FilterBar(
                    total = state.totalCount,
                    complete = state.completeCount,
                    incomplete = state.incompleteCount,
                    currentFilter = filter,
                    onFilterChange = { filter = it },
                    onRepairAllClick = {
                        if (state.incompleteCount > 0 && !state.batchRepairing) {
                            showConfirmDialog = true
                        }
                    },
                    batchRepairing = state.batchRepairing,
                )

                // 批量修复进度卡片
                if (state.batchRepairing) {
                    BatchProgressBar(state)
                }

                // 当前筛选模式小标题
                val filteredFiles = when (filter) {
                    ListFilter.All -> state.files
                    ListFilter.Incomplete -> state.files.filter { !it.report.isComplete }
                    ListFilter.Complete -> state.files.filter { it.report.isComplete }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "${filter.label} · ${filteredFiles.size} 首",
                        fontSize = 12.sp,
                        color = Color.Gray,
                        fontWeight = FontWeight.Medium,
                    )
                    if (filter != ListFilter.All) {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { filter = ListFilter.All }) {
                            Text("返回全部", fontSize = 12.sp)
                        }
                    }
                }

                // 文件列表
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filteredFiles) { file ->
                        FileTile(
                            file = file,
                            enabled = !state.batchRepairing,
                            onClick = {
                                if (!state.batchRepairing) {
                                    viewModel.searchOnline(file)
                                    onNavigateToMatch()
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("批量修复") },
            text = {
                Text(
                    "将对 ${state.incompleteCount} 个待修复文件自动进行在线匹配并写入标签。\n" +
                        "自动选取每个文件的第一个搜索结果（通常最贴合）。\n" +
                        "串行执行，预计每首约 2-5 秒。可以在列表中实时查看进度和分数变化。",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showConfirmDialog = false
                        viewModel.repairAll(onlyMissing = true)
                    },
                ) { Text("开始修复") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun EmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Filled.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Color.LightGray,
        )
        Spacer(Modifier.height(12.dp))
        Text("还没有扫描文件", color = Color.Gray)
        Spacer(Modifier.height(4.dp))
        Text("请点击左上角返回扫描页", fontSize = 13.sp, color = Color.Gray)
    }
}

@Composable
private fun FilterBar(
    total: Int,
    complete: Int,
    incomplete: Int,
    currentFilter: ListFilter,
    onFilterChange: (ListFilter) -> Unit,
    onRepairAllClick: () -> Unit,
    batchRepairing: Boolean,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                label = "全部 $total",
                selected = currentFilter == ListFilter.All,
                color = MaterialTheme.colorScheme.primary,
                onClick = { onFilterChange(ListFilter.All) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                label = "待修复 $incomplete",
                selected = currentFilter == ListFilter.Incomplete,
                color = Color(0xFFEF5350),
                onClick = { onFilterChange(ListFilter.Incomplete) },
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                label = "完整 $complete",
                selected = currentFilter == ListFilter.Complete,
                color = Color(0xFF4CAF50),
                onClick = { onFilterChange(ListFilter.Complete) },
                modifier = Modifier.weight(1f),
            )
        }

        if (incomplete > 0) {
            Button(
                onClick = onRepairAllClick,
                enabled = !batchRepairing,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                if (batchRepairing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = Color.White,
                    )
                    Spacer(Modifier.size(8.dp))
                    Text("修复中...", fontSize = 13.sp)
                } else {
                    Icon(Icons.Filled.AutoFixHigh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("一键修复 $incomplete 首", fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(
    label: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bgColor by animateColorAsState(
        targetValue = if (selected) color else color.copy(alpha = 0.08f),
        label = "bg",
    )
    val textColor by animateColorAsState(
        targetValue = if (selected) Color.White else color,
        label = "tx",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = textColor,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun BatchProgressBar(state: com.musictagrepair.viewmodel.UiState) {
    val progress = if (state.batchTotal > 0) {
        state.batchDone.toFloat() / state.batchTotal
    } else 0f
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f))
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
                "正在批量修复...",
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "${state.batchSucceeded}✓  ${state.batchFailed}✗",
                fontSize = 12.sp,
                color = Color.Gray,
            )
        }

        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(6.dp),
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "${state.batchDone} / ${state.batchTotal}",
                fontSize = 12.sp,
                color = Color.Gray,
                fontWeight = FontWeight.Medium,
            )
            if (state.batchCurrentFileName.isNotBlank()) {
                Spacer(Modifier.size(8.dp))
                Text(
                    state.batchCurrentFileName,
                    fontSize = 11.sp,
                    color = Color.Gray,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun FileTile(file: FileStatus, enabled: Boolean, onClick: () -> Unit) {
    val report = file.report
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (enabled) MaterialTheme.colorScheme.surface
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ScoreIndicator(score = report.score, size = 44)
            Spacer(Modifier.size(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    report.currentTags.title ?: file.filename,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 15.sp,
                )
                Spacer(Modifier.height(4.dp))
                report.currentTags.artist?.let {
                    Text(
                        it,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontSize = 13.sp,
                        color = Color.Gray,
                    )
                }
                if (!report.isComplete) {
                    Spacer(Modifier.height(6.dp))
                    MissingFieldsChips(report.missingFields)
                }
            }
            if (report.isComplete) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = Color(0xFF4CAF50))
            } else {
                Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.Gray)
            }
        }
    }
}
