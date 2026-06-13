package com.gijimemo.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gijimemo.data.model.Session
import com.gijimemo.data.model.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SessionCard(session: Session, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(text = session.title)
            Text(text = formatDate(session.createdAt))
            Text(text = "状态: ${session.status.label()}")
        }
    }
}

private fun SessionStatus.label(): String = when (this) {
    SessionStatus.RECORDING -> "录音中"
    SessionStatus.STOPPED -> "已停止"
    SessionStatus.TRANSCRIBING -> "转写中"
    SessionStatus.READY -> "已就绪"
    SessionStatus.SHARED -> "已发送"
    SessionStatus.ERROR -> "失败"
}

private fun formatDate(epoch: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))
