@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
    lat: Double,
    lng: Double,
    onDismiss: () -> Unit,
    onConfirm: (name: String, type: PointType, radiusM: Int, alertEnabled: Boolean, alertSound: Boolean) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var type by remember { mutableStateOf(initialType) }
    var radius by remember { mutableStateOf(initialRadius.toFloat()) }
    var alertEnabled by remember { mutableStateOf(initialAlertEnabled) }
    var sound by remember { mutableStateOf(initialSound) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = { onConfirm(name, type, radius.toInt(), alertEnabled, sound) }) { Text(confirmLabel) }
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
                                // Reset the alert default when the type changes.
                                alertEnabled = t.defaultAlert
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
    onSave: (name: String, type: PointType, radiusM: Int, alertEnabled: Boolean, alertSound: Boolean) -> Unit
) {
    PointFormDialog(
        title = "บันทึกจุด",
        confirmLabel = "บันทึก",
        initialName = "",
        initialType = PointType.SPEED_CAMERA,
        initialRadius = 300,
        initialAlertEnabled = PointType.SPEED_CAMERA.defaultAlert,
        initialSound = true,
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
        lat = point.lat,
        lng = point.lng,
        onDismiss = onDismiss,
        onConfirm = { name, type, radiusM, alertEnabled, alertSound ->
            onSave(
                point.copy(
                    name = name.ifBlank { type.label },
                    type = type,
                    radiusM = radiusM,
                    alertEnabled = alertEnabled,
                    alertSound = alertSound
                )
            )
        }
    )
}

@Composable
fun PointListDialog(
    points: List<AlertPoint>,
    onDismiss: () -> Unit,
    onEdit: (AlertPoint) -> Unit,
    onDelete: (AlertPoint) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("จุดที่บันทึก (${points.size})") },
        text = {
            if (points.isEmpty()) {
                Text("ยังไม่มีจุดที่บันทึก")
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(points, key = { it.id }) { p ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    text = pointDetail(p),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { onEdit(p) }) { Text("แก้ไข") }
                            TextButton(onClick = { onDelete(p) }) { Text("ลบ") }
                        }
                        HorizontalDivider()
                    }
                }
            }
        }
    )
}

private fun pointDetail(p: AlertPoint): String =
    if (!p.alertEnabled) {
        "${p.type.label} • ไม่เตือน"
    } else {
        "${p.type.label} • เตือน ${p.radiusM} ม. • ${if (p.alertSound) "เสียง" else "เงียบ"}"
    }
