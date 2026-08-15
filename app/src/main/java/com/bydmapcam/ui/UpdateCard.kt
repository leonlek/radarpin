package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bydmapcam.update.Updates

/**
 * The one thing an app with no store behind it has to say for itself now and then.
 *
 * It sits with the other top cards rather than as a dialog because a driver who opened the app to
 * see a camera warning should not have to dismiss a box about software first — and it keeps the
 * download page in reach on every failure, since a head unit that refuses to let us install is a
 * likely outcome, not an edge case.
 */
@Composable
fun UpdateCard(
    state: Updates.State,
    onUpdate: (Updates.State.Available) -> Unit,
    onDismiss: () -> Unit,
    onOpenPage: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (state is Updates.State.Idle) return
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            when (state) {
                is Updates.State.Available -> {
                    Text(
                        text = "มีเวอร์ชันใหม่ (build ${state.versionCode})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (state.notes.isNotBlank()) {
                        Text(
                            text = state.notes,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("ไว้ก่อน") }
                        TextButton(onClick = { onUpdate(state) }) { Text("อัปเดต") }
                    }
                }

                is Updates.State.Downloading -> {
                    Text("กำลังโหลด ${state.percent}%", style = MaterialTheme.typography.titleMedium)
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp, bottom = 10.dp, end = 8.dp)
                    )
                }

                Updates.State.Installing -> {
                    Text("กำลังติดตั้ง", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "กด \"ติดตั้ง\" ในหน้าที่ระบบเด้งขึ้นมา",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss) { Text("ปิด") }
                    }
                }

                is Updates.State.Failed -> {
                    Text("อัปเดตไม่สำเร็จ", style = MaterialTheme.typography.titleMedium)
                    Text(state.message, style = MaterialTheme.typography.bodySmall)
                    Row(
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        TextButton(onClick = onDismiss) { Text("ปิด") }
                        TextButton(onClick = onOpenPage) { Text("เปิดหน้าโหลด") }
                    }
                }

                Updates.State.Idle -> Unit
            }
        }
    }
}
