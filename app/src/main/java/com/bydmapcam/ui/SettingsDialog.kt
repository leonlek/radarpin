package com.bydmapcam.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bydmapcam.settings.Settings

@Composable
fun SettingsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var tts by remember { mutableStateOf(Settings.ttsEnabled(context)) }
    var overlay by remember { mutableStateOf(Settings.overlayEnabled(context)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("ตั้งค่า") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("เสียงพูดเตือน (TTS)")
                        Text(
                            "พูดชื่อจุดตอนเข้าใกล้",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = tts,
                        onCheckedChange = {
                            tts = it
                            Settings.setTtsEnabled(context, it)
                        }
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("แบนเนอร์ทับแอปอื่น")
                        Text(
                            "เด้งเตือนแม้เปิดแอปอื่น (ต้องอนุญาต \"แสดงทับแอปอื่น\")",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = overlay,
                        onCheckedChange = { checked ->
                            overlay = checked
                            Settings.setOverlayEnabled(context, checked)
                            if (checked && !Settings.canDrawOverlays(context)) {
                                runCatching {
                                    context.startActivity(
                                        Intent(
                                            AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                            Uri.parse("package:${context.packageName}")
                                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    )
                                }
                            }
                        }
                    )
                }
            }
        }
    )
}
