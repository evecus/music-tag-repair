package com.musictagrepair.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictagrepair.data.MissingField
import com.musictagrepair.ui.components.ScoreIndicator
import com.musictagrepair.viewmodel.AppViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ResultScreen(
    viewModel: AppViewModel,
    onBack: () -> Unit,
) {
    val state by viewModel.uiState.collectAsState()
    val file = state.currentFile

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (file == null) {
                Text("无数据", color = Color.Gray)
                return@Scaffold
            }

            Spacer(Modifier.height(40.dp))

            // 成功图标
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(RoundedCornerShape(40.dp))
                    .background(Color(0xFF4CAF50).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = Color(0xFF4CAF50),
                )
            }

            Spacer(Modifier.height(20.dp))
            Text("修复完成", fontWeight = FontWeight.Bold, fontSize = 22.sp)
            Spacer(Modifier.height(8.dp))
            Text(
                file.report.currentTags.title ?: file.filename,
                fontSize = 14.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(32.dp))

            // 分数对比
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("修复后分数", fontSize = 12.sp, color = Color.Gray)
                    Spacer(Modifier.height(8.dp))
                    ScoreIndicator(score = file.report.score, size = 60)
                }
            }

            Spacer(Modifier.height(24.dp))

            // 标签信息卡片
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val tags = file.report.currentTags
                    TagRow("标题", tags.title)
                    TagRow("歌手", tags.artist)
                    TagRow("专辑", tags.album)
                    TagRow("年份", tags.year?.toString())
                    TagRow("歌词", if (tags.lyrics != null) "✓ 已写入" else null)
                    TagRow("封面", if (tags.hasCover) "✓ 已写入" else null)
                }
            }

            Spacer(Modifier.height(32.dp))

            // 完整性
            if (file.report.isComplete) {
                Text(
                    "✓ 标签已完整",
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Medium,
                )
            } else {
                Text(
                    "仍缺失: ${file.report.missingFields.joinToString("、") { it.label }}",
                    color = Color(0xFFFFA726),
                )
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onBack,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = RoundedCornerShape(14.dp),
            ) {
                Text("返回列表")
            }
        }
    }
}

@Composable
private fun TagRow(label: String, value: String?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = Color.Gray,
            modifier = Modifier.width(80.dp),
        )
        Text(
            value ?: "—",
            fontSize = 14.sp,
            color = if (value != null) MaterialTheme.colorScheme.onSurface else Color.LightGray,
            fontWeight = FontWeight.Medium,
        )
    }
}
