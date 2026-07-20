@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.bydmapcam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.unit.dp
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.PointType
import com.bydmapcam.location.GeoUtils

/** Shared create/edit form for a saved point. */
@Composable
private fun PointFormDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    initialType: PointType,
    initialRadius: Int,
    initialAlertEnabled: Boolean,
    initialSound: Boolean,
    initialInfoMode: Boolean,
    lat: Double,
    lng: Double,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: PointType, radiusM: Int, alertEnabled: Boolean, alertSound: Boolean, infoMode: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var radius by remember { mutableStateOf(initialRadius.toFloat()) }
    var alertEnabled by remember { mutableStateOf(initialAlertEnabled) }
    var sound by remember { mutableStateOf(initialSound) }
    var infoMode by remember { mutableStateOf(initialInfoMode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                onConfirm(name, type, radius.toInt(), alertEnabled, sound, infoMode)
            }) { Text(confirmLabel) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("ยกเลิก") }
        },
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อ (ไม่ใส่ก็ได้)") },
                    singleLine = true
                )
                Text("ประเภท", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PointType.entries.forEach { t ->
                        FilterChip(
                            selected = type == t,
                            onClick = {
                                type = t
                                // Reset alert options to the type's default when the type changes.
                                alertEnabled = t.defaultAlert
                                infoMode = false
                            },
                            label = { Text(t.label) }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("เตือนเมื่อเข้าใกล้", modifier = Modifier.weight(1f))
                    Switch(checked = alertEnabled, onCheckedChange = { alertEnabled = it })
                }
                if (alertEnabled) {
                    Text("รูปแบบเตือน", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = !infoMode,
                            onClick = { infoMode = false },
                            label = { Text("ปกติ (วง+เสียง)") }
                        )
                        FilterChip(
                            selected = infoMode,
                            onClick = { infoMode = true },
                            label = { Text("info (ไอคอนเด้ง 100 ม.)") }
                        )
                    }
                    if (infoMode) {
                        Text(
                            "ไม่มีวงรัศมี — ไอคอน+ชื่อจะเด้งใหญ่ขึ้นเมื่อเข้าใกล้ 100 ม. แล้วปิดเองเมื่อผ่านไป",
                            style = MaterialTheme.typography.bodySmall
                        )
                    } else {
                        Text("รัศมีเตือน: ${radius.toInt()} ม.")
                        Slider(
                            value = radius,
                            onValueChange = { radius = it },
                            valueRange = 100f..1000f,
                            steps = 8
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("เตือนด้วยเสียง", modifier = Modifier.weight(1f))
                            Switch(checked = sound, onCheckedChange = { sound = it })
                        }
                    }
                } else {
                    Text(
                        "จุดนี้จะแสดงบนแผนที่แต่ไม่เตือน",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Text(
                    text = "พิกัด: ${"%.5f".format(lat)}, ${"%.5f".format(lng)}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    )
}

@Composable
fun SavePointDialog(
    lat: Double,
    lng: Double,
    onDismiss: () -> Unit,
    onSave: (name: String, type: PointType, radiusM: Int, alertEnabled: Boolean, alertSound: Boolean, infoMode: Boolean) -> Unit
) {
    PointFormDialog(
        title = "บันทึกจุด",
        confirmLabel = "บันทึก",
        initialName = "",
        initialType = PointType.SPEED_CAMERA,
        initialRadius = 300,
        initialAlertEnabled = PointType.SPEED_CAMERA.defaultAlert,
        initialSound = true,
        initialInfoMode = false,
        lat = lat,
        lng = lng,
        onDismiss = onDismiss,
        onConfirm = onSave
    )
}

@Composable
fun EditPointDialog(
    point: AlertPoint,
    onDismiss: () -> Unit,
    onSave: (AlertPoint) -> Unit
) {
    PointFormDialog(
        title = "แก้ไขจุด",
        confirmLabel = "บันทึก",
        initialName = point.name,
        initialType = point.type,
        initialRadius = point.radiusM,
        initialAlertEnabled = point.alertEnabled,
        initialSound = point.alertSound,
        initialInfoMode = point.infoMode,
        lat = point.lat,
        lng = point.lng,
        onDismiss = onDismiss,
        onConfirm = { name, type, radiusM, alertEnabled, alertSound, infoMode ->
            onSave(
                point.copy(
                    name = name.ifBlank { type.label },
                    type = type,
                    radiusM = radiusM,
                    alertEnabled = alertEnabled,
                    alertSound = alertSound,
                    infoMode = infoMode
                )
            )
        }
    )
}

@Composable
fun PointListDialog(
    points: List<AlertPoint>,
    currentLat: Double?,
    currentLng: Double?,
    onDismiss: () -> Unit,
    onFocus: (AlertPoint) -> Unit,
    onEdit: (AlertPoint) -> Unit,
    onDelete: (AlertPoint) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf<PointType?>(null) }

    val shown = points
        .filter { typeFilter == null || it.type == typeFilter }
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .let { list ->
            if (currentLat != null && currentLng != null) {
                list.sortedBy { GeoUtils.distanceMeters(currentLat, currentLng, it.lat, it.lng) }
            } else {
                list
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("จุดที่บันทึก (${shown.size}/${points.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("ค้นหาชื่อ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = typeFilter == null,
                        onClick = { typeFilter = null },
                        label = { Text("ทั้งหมด") }
                    )
                    PointType.entries.forEach { t ->
                        FilterChip(
                            selected = typeFilter == t,
                            onClick = { typeFilter = t },
                            label = { Text(t.label) }
                        )
                    }
                }
                if (shown.isEmpty()) {
                    Text("ไม่พบจุด")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                        items(shown, key = { it.id }) { p ->
                            val dist = if (currentLat != null && currentLng != null) {
                                distStr(GeoUtils.distanceMeters(currentLat, currentLng, p.lat, p.lng))
                            } else {
                                null
                            }
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { onFocus(p) }
                                ) {
                                    Text(
                                        text = if (dist != null) "${p.name}  ·  $dist" else p.name,
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(text = pointDetail(p), style = MaterialTheme.typography.bodySmall)
                                }
                                TextButton(onClick = { onEdit(p) }) { Text("แก้ไข") }
                                TextButton(onClick = { onDelete(p) }) { Text("ลบ") }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    )
}

private fun distStr(m: Double): String =
    if (m >= 1000) "%.1f กม.".format(m / 1000.0) else "${m.toInt()} ม."

fun pointDetail(p: AlertPoint): String = when {
    !p.alertEnabled -> "${p.type.label} • ไม่เตือน"
    p.infoMode -> "${p.type.label} • info (เด้งใกล้ 100 ม.)"
    else -> "${p.type.label} • เตือน ${p.radiusM} ม. • ${if (p.alertSound) "เสียง" else "เงียบ"}"
}
