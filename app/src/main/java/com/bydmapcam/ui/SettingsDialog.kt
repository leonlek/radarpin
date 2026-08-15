package com.bydmapcam.ui

import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.bydmapcam.parking.ParkingRules
import com.bydmapcam.settings.MeIcon
import com.bydmapcam.settings.Settings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsDialog(
    headingUp: Boolean,
    onHeadingUpChange: (Boolean) -> Unit,
    meIcon: MeIcon,
    onMeIconChange: (MeIcon) -> Unit,
    parkingLines: Boolean,
    onParkingLinesChange: (Boolean) -> Unit,
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
    var parkingReminder by remember { mutableStateOf(Settings.parkingReminder(context)) }
    val mediaAccess = MediaLink.hasAccess(context)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("ตั้งค่า") },
        text = {
            // Scrollable: the list has outgrown a head unit's dialog height, and a row that falls
            // off the bottom is indistinguishable from a row that isn't in the build at all.
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(18.dp)
            ) {
                // Which build is actually on the car — the only way to answer that from the seat,
                // and the first thing worth knowing when a change "didn't show up".
                Text(
                    text = appVersion(context),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
                    title = "แสดงเส้นที่จอดรถ",
                    subtitle = "ระบายขอบถนนที่บันทึกไว้ — เขียว = วันนี้จอดฝั่งนี้ได้ แดง = ห้าม (เห็นตอนซูมเข้า)"
                ) {
                    Switch(checked = parkingLines, onCheckedChange = onParkingLinesChange)
                }
                SettingRow(
                    title = "เตือนย้ายรถก่อนเที่ยงคืน",
                    subtitle = "จอดถูกฝั่งแต่พรุ่งนี้สลับ — เตือน ${minutesToHhMm(ParkingRules.REMIND_HOUR * 60 + ParkingRules.REMIND_MINUTE)} น. ให้ย้ายทัน"
                ) {
                    Switch(
                        checked = parkingReminder,
                        onCheckedChange = {
                            parkingReminder = it
                            Settings.setParkingReminder(context, it)
                        }
                    )
                }
                HorizontalDivider()
                SettingRow(
                    title = "เปิดแอปเองตอนสตาร์ทรถ",
                    subtitle = "เปิดแอปเมื่อจอบูต จอตื่นหลังดับเกิน 2 นาที หรือรถเริ่มออกตัวหลังจอดนาน (ต้องอนุญาต \"แสดงทับแอปอื่น\")"
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
                    title = "บริการเฝ้าเตือน",
                    subtitle = aliveStatus(context)
                ) {}
                SettingRow(
                    title = "สัญญาณเปิดแอปล่าสุด",
                    subtitle = bootStatus(context)
                ) {
                    if (!Settings.canDrawOverlays(context)) {
                        TextButton(onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        AndroidSettings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                        Uri.parse("package:${context.packageName}")
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            }
                        }) { Text("ให้สิทธิ์") }
                    }
                }
                SettingRow(
                    title = "แถบควบคุมเพลง",
                    subtitle = if (mediaAccess)
                        "อนุญาตแล้ว — แถบควบคุมพร้อมชื่อเพลงจะขึ้นเมื่อมีเพลงเล่นอยู่"
                    else
                        "ต้องเปิด \"การเข้าถึงการแจ้งเตือน\" ก่อน ไม่งั้นแถบจะไม่ขึ้นเลย · จอ BYD บล็อกหน้านี้ไว้ (ขึ้น \"IVI system does not support this operation\") และเสียงจาก CarPlay ก็สั่งเดินหน้า/ถอยหลังไม่ได้อยู่ดี — บนมือถือเปิดได้ปกติ"
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

/** "RadarPin 0.1.0 (build 14)" — read off the installed package, so it can't drift from reality. */
private fun appVersion(context: Context): String = runCatching {
    val info = context.packageManager.getPackageInfo(context.packageName, 0)
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        info.longVersionCode
    } else {
        @Suppress("DEPRECATION") info.versionCode.toLong()
    }
    // The Android version is here because it decides which of the background rules apply at all:
    // the restrictions that broke auto-start arrived in 14, and a head unit may be years older.
    "RadarPin ${info.versionName} (build $code) · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"
}.getOrDefault("RadarPin")

private val bootTimeFmt = SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("th-TH"))

/**
 * Whether the alert service was running through the last stretch. This is the question under the
 * question: if the heartbeat stops when the car is switched off and only resumes when the app is
 * opened by hand, nothing in the app can wake it — no broadcast is coming, and only the head
 * unit's own auto-start list can help.
 */
private fun aliveStatus(context: Context): String {
    val alive = Settings.aliveAt(context)
    if (alive == 0L) return "ยังไม่เคยรายงานตัว — เปิดแอปค้างไว้สักครู่แล้วดูใหม่"
    val started = Settings.processStartAt(context)
    val screenOff = Settings.screenOffSeenAt(context)
    return buildString {
        // The process-start time is the decisive one: if it predates the last power-up we were
        // running the whole time the car was off, and if it doesn't we were killed and rebuilt.
        append("เริ่มทำงานเมื่อ ${bootTimeFmt.format(Date(started))}")
        append(" · รายงานตัวล่าสุด ${bootTimeFmt.format(Date(alive))}")
        append(
            if (screenOff == 0L) " · ไม่เคยเห็นจอดับเลย (จอนี้ไม่บอก Android ว่าดับ)"
            else " · เห็นจอดับล่าสุด ${bootTimeFmt.format(Date(screenOff))}"
        )
    }
}

/**
 * Plain-language read-out of what the head unit did at power-up. This is the whole diagnostic: from
 * the driver's seat every cause of "the app didn't open by itself" looks the same, and only the
 * receiver can tell them apart — no broadcast at all vs. a broadcast whose window got blocked.
 */
private fun bootStatus(context: Context): String {
    val trace = Settings.bootTrace(context)
        ?: return "ยังไม่เคยได้รับทั้งสัญญาณบูตและสัญญาณจอตื่น — ถ้าดับเครื่องแล้วสตาร์ทใหม่ยังขึ้นข้อความเดิม แปลว่าจอฆ่าแอปตอนดับเครื่อง ต้องไปเปิดในรายการ \"เปิดแอปอัตโนมัติ\" ของตัวจอเอง"
    val at = bootTimeFmt.format(Date(trace.atMillis))
    val what = trace.action.substringAfterLast('.')
    val tail = when (trace.result) {
        Settings.BOOT_OFF -> "ตอนนั้นสวิตช์ด้านบนปิดอยู่ จึงไม่ได้เปิดแอป"
        Settings.BOOT_STARTED -> "สั่งเปิดแอปแล้ว (มีสิทธิ์แสดงทับแอปอื่นครบ)"
        Settings.BOOT_NO_OVERLAY -> "สั่งเปิดแอปแล้ว แต่ยังไม่ได้สิทธิ์ \"แสดงทับแอปอื่น\" ระบบจึงบล็อกหน้าต่างไว้"
        else -> "สั่งเปิดแอปไม่สำเร็จ"
    }
    return "$what · $at — $tail"
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
