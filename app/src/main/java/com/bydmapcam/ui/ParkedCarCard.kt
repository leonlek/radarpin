package com.bydmapcam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bydmapcam.location.GeoUtils
import com.bydmapcam.location.ParkedSpot
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val parkedTimeFmt = SimpleDateFormat("HH:mm", Locale.forLanguageTag("th-TH"))

/**
 * The way back to the car.
 *
 * Only appears once you are far enough away that you can't see it — standing next to the car, this
 * would be a card explaining where you are — and it says the two things a person walking across a
 * car park can act on: how far, and which way. The exact spot is on the map behind it; the words
 * are for the times you don't want to look at a map at all.
 */
@Composable
fun ParkedCarCard(
    spot: ParkedSpot.Spot,
    currentLat: Double,
    currentLng: Double,
    onShowOnMap: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val distance = GeoUtils.distanceMeters(currentLat, currentLng, spot.point.lat, spot.point.lng)
    if (distance < ParkedSpot.NEAR_M) return
    val bearing = GeoUtils.bearingDeg(currentLat, currentLng, spot.point.lat, spot.point.lng)
    val far = if (distance >= 1000) "%.1f กม.".format(distance / 1000.0) else "${distance.toInt()} ม."

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Text(
                text = "รถจอดอยู่ห่าง $far · ทาง${ParkedSpot.compass(bearing)}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "จอดเมื่อ ${parkedTimeFmt.format(Date(spot.atMillis))} น.",
                style = MaterialTheme.typography.bodySmall
            )
            Row(
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                TextButton(onClick = onDismiss) { Text("ปิด") }
                TextButton(onClick = onShowOnMap) { Text("ดูบนแผนที่") }
            }
        }
    }
}
