package com.bydmapcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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

    var showSave by remember { mutableStateOf(false) }
    var showList by remember { mutableStateOf(false) }
    var editingPoint by remember { mutableStateOf<AlertPoint?>(null) }
    var recenterTick by remember { mutableIntStateOf(0) }
    var showSettings by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        MapLibreMap(
            points = points,
            location = location,
            activeIds = activeIds,
            recenterTick = recenterTick,
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
            ExtendedFloatingActionButton(onClick = { if (location != null) showSave = true }) {
                Text("บันทึกจุดนี้")
            }
        }
    }

    val loc = location
    if (showSave && loc != null) {
        SavePointDialog(
            lat = loc.latitude,
            lng = loc.longitude,
            onDismiss = { showSave = false },
            onSave = { name, type, radius, alertEnabled, sound ->
                vm.savePoint(name, type, loc.latitude, loc.longitude, radius, alertEnabled, sound)
                showSave = false
            }
        )
    }

    if (showList) {
        PointListDialog(
            points = points,
            onDismiss = { showList = false },
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
