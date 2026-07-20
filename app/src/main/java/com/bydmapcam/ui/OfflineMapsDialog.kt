package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bydmapcam.offline.MapCamera
import com.bydmapcam.offline.OfflineMaps
import org.maplibre.android.offline.OfflineRegion

@Composable
fun OfflineMapsDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val bounds = remember { MapCamera.bounds }

    var regions by remember { mutableStateOf<List<OfflineMaps.RegionInfo>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var bytes by remember { mutableLongStateOf(0L) }
    var error by remember { mutableStateOf<String?>(null) }
    var activeRegion by remember { mutableStateOf<OfflineRegion?>(null) }

    fun reload() {
        OfflineMaps.list(context) { regions = it; loading = false }
    }
    LaunchedEffect(Unit) { reload() }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        confirmButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) { Text("ปิด") }
        },
        title = { Text("แผนที่ออฟไลน์") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (downloading) {
                    Text("กำลังโหลด… ${(progress * 100).toInt()}%  (${OfflineMaps.formatSize(bytes)})")
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    TextButton(onClick = {
                        activeRegion?.let { OfflineMaps.delete(it) { reload() } }
                        downloading = false
                        activeRegion = null
                    }) { Text("ยกเลิก") }
                } else {
                    Text(
                        "เก็บแผนที่บริเวณที่เห็นบนจอไว้ใช้ตอนไม่มีเน็ต (ถ.ระดับซูม 10–15)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Button(
                        onClick = {
                            val b = bounds ?: return@Button
                            error = null
                            downloading = true
                            progress = 0f
                            bytes = 0L
                            val c = b.center
                            val name = "พื้นที่ (%.2f, %.2f)".format(c.latitude, c.longitude)
                            OfflineMaps.startDownload(
                                context = context,
                                bounds = b,
                                name = name,
                                pixelRatio = context.resources.displayMetrics.density,
                                onRegionReady = { activeRegion = it },
                                onProgress = { f, bt -> progress = f; bytes = bt },
                                onComplete = {
                                    downloading = false
                                    activeRegion = null
                                    reload()
                                },
                                onError = { msg ->
                                    downloading = false
                                    activeRegion = null
                                    error = msg
                                    reload()
                                }
                            )
                        },
                        enabled = bounds != null,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("โหลดพื้นที่ที่เห็นบนจอ") }

                    if (bounds == null) {
                        Text(
                            "เปิดแผนที่ให้แสดงพื้นที่ก่อน แล้วค่อยกลับมาโหลด",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    error?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    }

                    HorizontalDivider()
                    Text("พื้นที่ที่โหลดไว้", fontWeight = FontWeight.SemiBold)
                    when {
                        loading -> Text("กำลังโหลด…", style = MaterialTheme.typography.bodySmall)
                        regions.isEmpty() -> Text(
                            "ยังไม่มี",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        else -> Column(
                            Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            regions.forEach { r ->
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(Modifier.weight(1f)) {
                                        Text(r.name, style = MaterialTheme.typography.bodyMedium)
                                        Text(
                                            OfflineMaps.formatSize(r.sizeBytes),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    TextButton(onClick = {
                                        OfflineMaps.delete(r.region) { reload() }
                                    }) { Text("ลบ") }
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}
