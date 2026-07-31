package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bydmapcam.data.Trip
import com.bydmapcam.data.avgKmPerPercent
import com.bydmapcam.data.bahtPerKm
import com.bydmapcam.data.costBaht
import com.bydmapcam.data.fullRangeKm
import com.bydmapcam.data.kmPerPercent
import com.bydmapcam.data.socUsed
import com.bydmapcam.trip.TripTracker
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact live panel shown only while a trip is running: distance + elapsed driving time, the
 * measured km/1% once the driver has entered two % readings (falling back to the learned average),
 * a 🔋 button to enter the current % and a "จบ" button.
 */
@Composable
fun TripStatusCard(
    trip: TripTracker.Active,
    avgKmPerPercent: Double?,
    onSetSoc: () -> Unit,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Ticks once a second so the elapsed time counts up on its own.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(trip.startTime) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val measured = trip.kmPerPercentSoFar
    val socTo = trip.soc
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "🚗 %.1f กม.".format(trip.distanceKm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                )
                Text(
                    text = buildString {
                        append("⏱ ").append(fmtElapsed(now - trip.startTime))
                        append("  ·  ")
                        // Until a second % reading exists there's nothing measured for THIS trip,
                        // so say plainly that the figure comes from previous ones.
                        when {
                            measured != null && socTo != null ->
                                append("🔋 %.1f กม./1%% (%d→%d%%)".format(measured, trip.startSoc, socTo))
                            avgKmPerPercent != null ->
                                append("ทริปก่อน ๆ %.1f กม./1%%".format(avgKmPerPercent))
                            else -> append("แตะ 🔋 ใส่ % อีกครั้งเพื่อดู กม./1%")
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1
                )
            }
            Spacer(Modifier.width(10.dp))
            FilledTonalButton(
                onClick = onSetSoc,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("🔋 ${trip.latestSoc}%", maxLines = 1, softWrap = false)
            }
            Spacer(Modifier.width(6.dp))
            FilledTonalButton(
                onClick = onFinish,
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
            ) {
                Text("จบ", maxLines = 1, softWrap = false)
            }
        }
    }
}

@Composable
fun StartTripDialog(
    onDismiss: () -> Unit,
    onOpenHistory: () -> Unit,
    onStart: (startSoc: Int) -> Unit
) {
    var soc by remember { mutableStateOf("") }
    val pct = soc.toIntOrNull()
    val valid = pct != null && pct in 0..100
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onStart(pct!!) }) { Text("เริ่ม") }
        },
        title = { Text("เริ่มทริป") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("อ่านเลข % แบตจากหน้าปัดรถแล้วกรอก", style = MaterialTheme.typography.bodySmall)
                PercentField(
                    value = soc,
                    onValueChange = { soc = it },
                    label = "แบตตอนนี้ (%)",
                    autoFocus = true
                )
                TextButton(onClick = onOpenHistory) { Text("ดูประวัติทริปที่ผ่านมา") }
            }
        }
    )
}

/** Enter the % battery read off the dash mid-trip, to get the km/1% at this very moment. */
@Composable
fun TripSocDialog(
    startSoc: Int,
    distanceKm: Double,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit
) {
    var soc by remember { mutableStateOf("") }
    val pct = soc.toIntOrNull()
    val valid = pct != null && pct in 0..100
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onSet(pct!!) }) { Text("บันทึก") }
        },
        title = { Text("ใส่ % แบตตอนนี้") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "อ่านเลข % แบตจากหน้าปัดรถตอนนี้ แล้วจะเห็น กม./1% ณ ตอนนี้บนการ์ดทริป",
                    style = MaterialTheme.typography.bodySmall
                )
                LabelValue("เริ่มทริปที่", "$startSoc%")
                LabelValue("วิ่งมาแล้ว", "%.1f กม.".format(distanceKm))
                PercentField(
                    value = soc,
                    onValueChange = { soc = it },
                    label = "แบตตอนนี้ (%)",
                    autoFocus = true
                )
                // Live preview of what the card will show, so a typo is obvious before saving.
                val used = pct?.let { startSoc - it }
                Text(
                    text = when {
                        !valid -> " "
                        used == null || used <= 0 -> "แบตไม่ลด — ยังคิด กม./1% ไม่ได้"
                        // Every literal % must be escaped as %% — this whole string goes through format().
                        else -> "→ ใช้ไป %d%%  ·  %.1f กม./1%%".format(used, distanceKm / used)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    )
}

@Composable
fun FinishTripDialog(
    distanceKm: Double,
    startSoc: Int,
    onDismiss: () -> Unit,
    onDiscard: () -> Unit,
    onFinish: (endSoc: Int, pricePerKwh: Double?) -> Unit
) {
    var soc by remember { mutableStateOf("") }
    var showPrice by remember { mutableStateOf(false) }
    var price by remember { mutableStateOf("") }
    val pct = soc.toIntOrNull()
    val valid = pct != null && pct in 0..100
    val noDrop = valid && pct!! >= startSoc
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onFinish(pct!!, if (showPrice) price.toDoubleOrNull() else null) }
            ) { Text("บันทึก") }
        },
        title = { Text("จบทริป") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelValue("ระยะทางที่วิ่ง", "%.1f กม.".format(distanceKm))
                Text("แบตเริ่มทริป: $startSoc%", style = MaterialTheme.typography.bodySmall)
                PercentField(
                    value = soc,
                    onValueChange = { soc = it },
                    label = "แบตตอนนี้ (%)",
                    autoFocus = true
                )
                if (noDrop) {
                    Text(
                        "แบตไม่ลด — จะไม่คำนวณ กม./1% ให้",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
                if (showPrice) {
                    OutlinedTextField(
                        value = price,
                        onValueChange = { price = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("ค่าไฟ ฿/kWh") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
                    TextButton(onClick = { showPrice = true }) { Text("+ ใส่ค่าไฟ (ไม่บังคับ)") }
                }
                HorizontalDivider()
                TextButton(onClick = onDiscard) {
                    Text("ทิ้งทริปนี้ (ไม่บันทึก)", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    )
}

/** Shown once on launch when a trip was left running from a previous run (engine off before "จบ"). */
@Composable
fun RestoreTripDialog(
    startSoc: Int,
    distanceKm: Double,
    startTime: Long,
    onContinue: () -> Unit,
    onFinish: () -> Unit,
    onDiscard: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onContinue, // tap-outside = keep driving (least destructive)
        dismissButton = { TextButton(onClick = onDiscard) { Text("ทิ้ง") } },
        confirmButton = { TextButton(onClick = onFinish) { Text("จบทริป") } },
        title = { Text("มีทริปค้าง") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("เหลือค้างจากคราวก่อน (ยังไม่ได้กดจบ):", style = MaterialTheme.typography.bodySmall)
                LabelValue("เริ่มที่แบต", "$startSoc%")
                LabelValue("ระยะสะสม", "%.1f กม.".format(distanceKm))
                LabelValue("เริ่มเมื่อ", fmtTripTime(startTime))
                TextButton(onClick = onContinue) { Text("↩ ขับต่อ (นับระยะต่อ)") }
            }
        }
    )
}

@Composable
fun TripSummaryDialog(
    trip: Trip,
    onOpenHistory: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        dismissButton = { TextButton(onClick = onOpenHistory) { Text("ประวัติ") } },
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("สรุปทริป") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelValue("ระยะทาง", "%.1f กม.".format(trip.distanceKm))
                LabelValue(
                    "ใช้แบต",
                    if (trip.socUsed > 0) "${trip.socUsed}%  (${trip.startSoc}→${trip.endSoc})"
                    else "แบตไม่ลด (${trip.startSoc}→${trip.endSoc})"
                )
                HorizontalDivider()
                val kmPct = trip.kmPerPercent
                Text(
                    text = kmPct?.let { "🔋 %.1f กม. / 1%%".format(it) } ?: "🔋 — กม. / 1%",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                LabelValue("เต็มถัง ≈", trip.fullRangeKm?.let { "%.0f กม.".format(it) } ?: "—")
                trip.bahtPerKm()?.let { perKm ->
                    LabelValue(
                        "ค่าไฟ",
                        "≈ ฿%.0f · ฿%.2f/กม.".format(trip.costBaht() ?: 0.0, perKm)
                    )
                }
            }
        }
    )
}

@Composable
fun TripHistoryDialog(
    trips: List<Trip>,
    onDismiss: () -> Unit
) {
    val avg = remember(trips) { avgKmPerPercent(trips) }
    var remain by remember { mutableStateOf("") }
    val remainPct = remain.toIntOrNull()?.takeIf { it in 0..100 }
    val estRange = if (avg != null && remainPct != null) avg * remainPct else null
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        title = { Text("ประวัติทริป (${trips.size} ล่าสุด)") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                LabelValue("เฉลี่ย", avg?.let { "%.1f กม./1%%".format(it) } ?: "— (ยังไม่พอ)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = remain,
                        onValueChange = { remain = it.filter { c -> c.isDigit() }.take(3) },
                        label = { Text("แบตเหลือ %") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.width(150.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = estRange?.let { "→ ~%.0f กม.".format(it) } ?: "→ วิ่งได้อีก ~? กม.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                HorizontalDivider()
                if (trips.isEmpty()) {
                    Text("ยังไม่มีทริป")
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(trips, key = { it.id }) { t ->
                            Column(Modifier.padding(vertical = 6.dp)) {
                                Text(fmtTripTime(t.endTime), style = MaterialTheme.typography.bodySmall)
                                Text(
                                    buildString {
                                        append("%.1f กม. · ".format(t.distanceKm))
                                        append(if (t.socUsed > 0) "${t.socUsed}% · " else "แบตไม่ลด · ")
                                        append(t.kmPerPercent?.let { "%.1f กม./1%%".format(it) } ?: "—")
                                        t.bahtPerKm()?.let { append(" · ฿%.2f/กม.".format(it)) }
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun PercentField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    /** The one field worth typing in — take focus and raise the keypad so it's one tap, not three. */
    autoFocus: Boolean = false
) {
    val focus = remember { FocusRequester() }
    if (autoFocus) {
        LaunchedEffect(Unit) { focus.requestFocus() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (autoFocus) Modifier.focusRequester(focus) else Modifier)
    )
}

@Composable
private fun LabelValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.Medium)
    }
}

private val tripTimeFmt = SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("th-TH"))
private fun fmtTripTime(millis: Long): String = tripTimeFmt.format(Date(millis))

/** Stopwatch-style elapsed time: m:ss under an hour, h:mm:ss past it. */
private fun fmtElapsed(millis: Long): String {
    val total = (millis / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
