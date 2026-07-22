package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Compact live panel shown only while a trip is running (distance so far + learned avg km/1%). */
@Composable
fun TripStatusCard(
    distanceKm: Double,
    avgKmPerPercent: Double?,
    onFinish: () -> Unit,
    modifier: Modifier = Modifier
) {
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
                    text = "🚗 %.1f กม.".format(distanceKm),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = avgKmPerPercent?.let { "⌀ %.1f กม./1%%".format(it) } ?: "⌀ — กม./1%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Spacer(Modifier.width(14.dp))
            FilledTonalButton(onClick = onFinish) { Text("จบ") }
        }
    }
}

@Composable
fun StartTripDialog(
    onDismiss: () -> Unit,
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
                PercentField(value = soc, onValueChange = { soc = it }, label = "แบตตอนนี้ (%)")
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
                PercentField(value = soc, onValueChange = { soc = it }, label = "แบตตอนนี้ (%)")
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
private fun PercentField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = { onValueChange(it.filter { c -> c.isDigit() }.take(3)) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth()
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
