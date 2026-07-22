package com.musictagrepair.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.musictagrepair.data.MissingField

/**
 * 完整性分数指示器
 */
@Composable
fun ScoreIndicator(score: Int, size: Int = 44) {
    val color = when {
        score == 100 -> Color(0xFF4CAF50)
        score >= 60 -> Color(0xFFFFA726)
        else -> Color(0xFFEF5350)
    }
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(color.copy(alpha = 0.12f))
            .background(Color.Transparent, shape = CircleShape)
            .padding(0.dp),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(color.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "$score",
                color = color,
                fontWeight = FontWeight.Bold,
                fontSize = (size * 0.35).sp,
            )
        }
    }
}

/**
 * 缺失字段标签
 */
@Composable
fun MissingFieldsChips(fields: List<MissingField>) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        fields.forEach { f ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color(0xFFFFCDD2).copy(alpha = 0.5f))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
                Text(
                    text = f.label,
                    color = Color(0xFFC62828),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }
    }
}
