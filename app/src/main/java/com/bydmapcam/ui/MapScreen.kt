package com.bydmapcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.location.LocationBus

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val points by vm.points.collectAsState()
    val location by LocationBus.location.collectAsState()
    val activeIds by LocationBus.activeAlertIds.collectAsState()

    // Coordinates for a pending "save point" dialog — from the FAB (current location) or a map long-press.
    var pendingSave by remember { mutableStateOf<Pair<Double, Double>?>(null) }
    var showList by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }
    var selectedPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var focus by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    Box(Modifier.fillMaxSize()) {
        MapLibreMap(
            points = points,
            location = location,
            activeIds = activeIds,
            recenterTick = recenterTick,
            onMapLongClick = { lat, lng -> pendingSave = lat to lng },
            onMarkerClick = { id ->
                points.find { it.id == id }?.let {
                    selectedPoint = it
                    focus = it.lat to it.lng
                }
            },
            focus = focus,
            modifier = Modifier.fillMaxSize()
        )

        // Top overlays: stacked vertically below the status bar so they never overlap.
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            location?.let { loc -> SpeedChip(speedMps = loc.speed) }

            val activePoints = points.filter { it.id in activeIds }
            if (activePoints.isNotEmpty()) {
                AlertBanner(points = activePoints, modifier = Modifier.fillMaxWidth())
            }
        }

        // Bottom-right controls, clear of the navigation bar.
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End
        ) {
            SmallFloatingActionButton(onClick = { showSettings = true }) {
                Text("⚙", fontSize = 20.sp)
            }
            SmallFloatingActionButton(onClick = { recenterTick++ }) {
                LocateIcon(color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            SmallFloatingActionButton(onClick = { showList = true }) {
                Text("จุด")
            }
            ExtendedFloatingActionButton(onClick = {
                location?.let { pendingSave = it.latitude to it.longitude }
            }) {
                Text("บันทึกจุดนี้")
            }
        }

        selectedPoint?.let { p ->
            PointInfoCard(
                point = p,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(12.dp),
                onEdit = { editingPoint = p; selectedPoint = null },
                onDelete = { vm.delete(p); selectedPoint = null },
                onClose = { selectedPoint = null }
            )
        }
    }

    pendingSave?.let { (lat, lng) ->
        SavePointDialog(
            lat = lat,
            lng = lng,
            onDismiss = { pendingSave = null },
            onSave = { name, type, radius, alertEnabled, sound ->
                vm.savePoint(name, type, lat, lng, radius, alertEnabled, sound)
                pendingSave = null
            }
        )
    }

    if (showList) {
        PointListDialog(
            points = points,
            onDismiss = { showList = false },
            onFocus = { p ->
                showList = false
                selectedPoint = p
                focus = p.lat to p.lng
            },
            onEdit = { editingPoint = it },
            onDelete = { vm.delete(it) }
        )
    }

    editingPoint?.let { pt ->
        EditPointDialog(
            point = pt,
            onDismiss = { editingPoint = null },
            onSave = {
                vm.updatePoint(it)
                editingPoint = null
            }
        )
    }

    if (showSettings) {
        SettingsDialog(onDismiss = { showSettings = false })
    }
}

@Composable
private fun SpeedChip(speedMps: Float, modifier: Modifier = Modifier) {
    val kmh = (speedMps * 3.6f).toInt().coerceAtLeast(0)
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary,
        shape = MaterialTheme.shapes.large,
        shadowElevation = 4.dp
    ) {
        Text(
            text = "$kmh",
            color = Color.White,
            fontSize = 46.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun AlertBanner(points: List<AlertPoint>, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = Color(0xFFE53935),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            Text(
                text = "⚠ ใกล้จุดเตือน",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium
            )
            points.take(3).forEach {
                Text(text = "• ${it.name} (${it.type.label})", color = Color.White)
            }
        }
    }
}

@Composable
private fun PointInfoCard(
    point: AlertPoint,
    modifier: Modifier = Modifier,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(point.name, style = MaterialTheme.typography.titleMedium)
            Text(
                text = if (point.alertEnabled) {
                    "${point.type.label} • เตือน ${point.radiusM} ม. • ${if (point.alertSound) "เสียง" else "เงียบ"}"
                } else {
                    "${point.type.label} • ไม่เตือน"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "พิกัด: ${"%.5f".format(point.lat)}, ${"%.5f".format(point.lng)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("แก้ไข") }
                TextButton(onClick = onDelete) { Text("ลบ") }
                TextButton(onClick = onClose) { Text("ปิด") }
            }
        }
    }
}

/** Simple "locate me" crosshair drawn with Canvas (no icon dependency). */
@Composable
private fun LocateIcon(color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(22.dp)) {
        val stroke = size.minDimension * 0.09f
        val c = center
        val r = size.minDimension / 3.4f
        drawCircle(color = color, radius = r, center = c, style = Stroke(width = stroke))
        drawCircle(color = color, radius = r * 0.30f, center = c)
        val tick = size.minDimension * 0.14f
        drawLine(color, Offset(c.x, 0f), Offset(c.x, tick), strokeWidth = stroke)
        drawLine(color, Offset(c.x, size.height), Offset(c.x, size.height - tick), strokeWidth = stroke)
        drawLine(color, Offset(0f, c.y), Offset(tick, c.y), strokeWidth = stroke)
        drawLine(color, Offset(size.width, c.y), Offset(size.width - tick, c.y), strokeWidth = stroke)
    }
}
