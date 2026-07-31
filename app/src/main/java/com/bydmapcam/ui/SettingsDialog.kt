package com.bydmapcam.ui

import android.content.Intent
import android.widget.Toast
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bydmapcam.media.MediaLink
import com.bydmapcam.settings.MeIcon
import com.bydmapcam.settings.Settings

@Composable
fun SettingsDialog(
    headingUp: Boolean,
    onHeadingUpChange: (Boolean) -> Unit,
    meIcon: MeIcon,
    onMeIconChange: (MeIcon) -> Unit,
    onImportCameras: () -> Unit,
    onOpenOffline: () -> Unit,
    onOpenTripHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var tts by remember { mutableStateOf(Settings.ttsEnabled(context)) }
    var overlay by remember { mutableStateOf(Settings.overlayEnabled(context)) }
    var directionAware by remember { mutableStateOf(Settings.directionAware(context)) }
    var autoBoot by remember { mutableStateOf(Settings.autoStartOnBoot(context)) }
    val mediaAccess = MediaLink.hasAccess(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("ตั้งค่า") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
                SettingRow(
                    title = "หมุนแผนที่ตามทิศทางขับ",
                    subtitle = "โหมดขับ: ทิศที่ขับอยู่ด้านบนเสมอ"
                ) {
                    Switch(checked = headingUp, onCheckedChange = onHeadingUpChange)
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("ไอคอนรถบนแผนที่")
                    Text(
                        "เลือกว่าจะให้ตำแหน่งรถเป็นลูกศรหรือรูปรถ",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MeIcon.entries.forEach { option ->
                            val selected = option == meIcon
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(MaterialTheme.shapes.medium)
                                    .background(
                                        if (selected) MaterialTheme.colorScheme.primaryContainer
                                        else MaterialTheme.colorScheme.surfaceVariant
                                    )
                                    .clickable { onMeIconChange(option) }
                                    .padding(horizontal = 10.dp, vertical = 8.dp)
                            ) {
                                Image(
                                    painter = painterResource(option.res),
                                    contentDescription = option.label,
                                    modifier = Modifier.size(34.dp)
                                )
                                Text(option.label, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
                SettingRow(
                    title = "เตือนเฉพาะทิศเข้าหา",
                    subtitle = "ไม่เตือนจุดที่ขับเลยผ่านไปแล้ว/สวนทาง (ตอนจอดเตือนทุกจุด)"
                ) {
                    Switch(
                        checked = directionAware,
                        onCheckedChange = {
                            directionAware = it
                            Settings.setDirectionAware(context, it)
                        }
                    )
                }
                SettingRow(
                    title = "เสียงพูดเตือน (TTS)",
                    subtitle = "พูดชื่อจุดตอนเข้าใกล้"
                ) {
                    Switch(
                        checked = tts,
                        onCheckedChange = {
                            tts = it
                            Settings.setTtsEnabled(context, it)
                        }
                    )
                }
                SettingRow(
                    title = "แบนเนอร์ทับแอปอื่น",
                    subtitle = "เด้งเตือนแม้เปิดแอปอื่น (ต้องอนุญาต \"แสดงทับแอปอื่น\")"
                ) {
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
                HorizontalDivider()
                SettingRow(
                    title = "เปิดแอปเองตอนสตาร์ทรถ",
                    subtitle = "เปิดแอปอัตโนมัติเมื่อจอบูตเสร็จ (ต้องอนุญาต \"แสดงทับแอปอื่น\")"
                ) {
                    Switch(
                        checked = autoBoot,
                        onCheckedChange = {
                            autoBoot = it
                            Settings.setAutoStartOnBoot(context, it)
                        }
                    )
                }
                SettingRow(
                    title = "แสดงชื่อเพลงที่กำลังเล่น",
                    subtitle = if (mediaAccess)
                        "อนุญาตแล้ว — แถบเพลงจะขึ้นชื่อเพลงที่กำลังเล่น"
                    else
                        "ปุ่มเล่น/หยุดใช้ได้อยู่แล้ว · ชื่อเพลงต้องเปิด \"การเข้าถึงการแจ้งเตือน\" ซึ่งจอ BYD บล็อกไว้ (ขึ้น \"IVI system does not support this operation\") — บนมือถือเปิดได้ปกติ"
                ) {
                    TextButton(onClick = {
                        if (!MediaLink.openAccessSettings(context)) {
                            Toast.makeText(
                                context,
                                "เครื่องนี้ไม่มีหน้า \"การเข้าถึงการแจ้งเตือน\" — แถบคุมเพลงจึงใช้ไม่ได้",
                                Toast.LENGTH_LONG
                            ).show()
                        }
                    }) { Text(if (mediaAccess) "จัดการ" else "อนุญาต") }
                }
                SettingRow(
                    title = "นำเข้าฐานกล้องทั่วไทย",
                    subtitle = "ดึงกล้องจับความเร็ว (OpenStreetMap) เพิ่มลงแผนที่"
                ) {
                    TextButton(onClick = onImportCameras) { Text("นำเข้า") }
                }
                SettingRow(
                    title = "แผนที่ออฟไลน์",
                    subtitle = "เก็บพื้นที่ที่เห็นบนจอไว้ใช้ตอนไม่มีเน็ต"
                ) {
                    TextButton(onClick = onOpenOffline) { Text("จัดการ") }
                }
                SettingRow(
                    title = "ประวัติทริป",
                    subtitle = "กม./1% + ค่าเฉลี่ย + ประมาณระยะที่เหลือ (5 ทริปล่าสุด)"
                ) {
                    TextButton(onClick = onOpenTripHistory) { Text("ดู") }
                }
            }
        }
    )
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    control: @Composable () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        control()
    }
}
