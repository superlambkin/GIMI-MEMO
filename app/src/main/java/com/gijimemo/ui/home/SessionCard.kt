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
            Text(text = "ステータス: ${session.status.label()}")
        }
    }
}

private fun SessionStatus.label(): String = when (this) {
    SessionStatus.RECORDING -> "録音中"
    SessionStatus.STOPPED -> "停止済み"
    SessionStatus.TRANSCRIBING -> "文字起こし中"
    SessionStatus.READY -> "完了"
    SessionStatus.SHARED -> "共有済み"
    SessionStatus.ERROR -> "失敗"
}

private fun formatDate(epoch: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))
