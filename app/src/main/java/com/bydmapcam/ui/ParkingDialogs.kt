@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.bydmapcam.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.Side
import com.bydmapcam.data.SideRule
import com.bydmapcam.data.other
import com.bydmapcam.location.LocationBus
import com.bydmapcam.parking.ParkingRules
import com.bydmapcam.parking.ParkingState

/** Everything the block form produces; the geometry it belongs to is carried separately. */
data class ParkingFormResult(
    val name: String,
    val leftRule: SideRule,
    val rightRule: SideRule,
    val banFromMin: Int?,
    val banToMin: Int?
)

/**
 * The details step, reached once the street is traced and a kerb picked.
 *
 * It opens on the answer for the ordinary block — this kerb odd, the other even — so the common
 * case is name-it-and-save. The rare shapes (a kerb that is never parked on, an hours ban) are
 * behind switches that are off until asked for, because a driver mapping their own street mostly
 * wants "which side today", not a form.
 */
@Composable
fun ParkingBlockDialog(
    title: String,
    /** The kerb the driver tapped: the odd-day side of an ordinary block. */
    oddSide: Side,
    initialName: String,
    initialLeft: SideRule,
    initialRight: SideRule,
    initialBanFrom: Int?,
    initialBanTo: Int?,
    onDismiss: () -> Unit,
    onSave: (ParkingFormResult) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var otherBanned by remember {
        mutableStateOf(ruleOf(initialLeft, initialRight, oddSide.other()) == SideRule.NEVER)
    }
    // Only consulted while [otherBanned] is on: with nothing to alternate with, this kerb's own
    // rule stops being implied by the other one and has to be stated.
    var soloRule by remember {
        mutableStateOf(
            ruleOf(initialLeft, initialRight, oddSide).takeIf { it != SideRule.NEVER }
                ?: SideRule.ODD_DAYS
        )
    }
    var hasWindow by remember { mutableStateOf(initialBanFrom != null && initialBanTo != null) }
    var fromText by remember { mutableStateOf(initialBanFrom?.let(::minutesToHhMm) ?: "06:00") }
    var toText by remember { mutableStateOf(initialBanTo?.let(::minutesToHhMm) ?: "09:00") }

    val fromMin = parseHhMm(fromText)
    val toMin = parseHhMm(toText)
    val windowValid = !hasWindow || (fromMin != null && toMin != null && fromMin != toMin)

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                enabled = windowValid,
                onClick = {
                    val mine = if (otherBanned) soloRule else SideRule.ODD_DAYS
                    val theirs = if (otherBanned) SideRule.NEVER else SideRule.EVEN_DAYS
                    onSave(
                        ParkingFormResult(
                            name = name.ifBlank { "บล็อกจอดรถ" },
                            leftRule = if (oddSide == Side.LEFT) mine else theirs,
                            rightRule = if (oddSide == Side.LEFT) theirs else mine,
                            banFromMin = if (hasWindow) fromMin else null,
                            banToMin = if (hasWindow) toMin else null
                        )
                    )
                }
            ) { Text("บันทึก") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ยกเลิก") } },
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("ชื่อบล็อก") },
                    singleLine = true
                )
                Text(
                    text = if (otherBanned) {
                        "ฝั่งที่เลือกไว้: ${soloRule.label} · อีกฝั่ง: ห้ามจอดตลอด"
                    } else {
                        "ฝั่งที่เลือกไว้: จอดได้วันคี่ · อีกฝั่ง: จอดได้วันคู่"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("อีกฝั่งห้ามจอดตลอด")
                        Text(
                            "ถนนที่จอดได้ฝั่งเดียว ไม่มีการสลับ",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = otherBanned, onCheckedChange = { otherBanned = it })
                }
                if (otherBanned) {
                    Text("ฝั่งที่จอดได้ จอดได้วันไหน", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(SideRule.ODD_DAYS, SideRule.EVEN_DAYS, SideRule.ALWAYS).forEach { r ->
                            FilterChip(
                                selected = soloRule == r,
                                onClick = { soloRule = r },
                                label = { Text(r.label) }
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("มีช่วงเวลาห้ามจอด")
                        Text(
                            "เช่น ห้ามจอดชั่วโมงเร่งด่วน — ใช้กับทั้งสองฝั่ง",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(checked = hasWindow, onCheckedChange = { hasWindow = it })
                }
                if (hasWindow) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = fromText,
                            onValueChange = { fromText = it },
                            label = { Text("ตั้งแต่") },
                            placeholder = { Text("06:00") },
                            singleLine = true,
                            isError = fromMin == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        OutlinedTextField(
                            value = toText,
                            onValueChange = { toText = it },
                            label = { Text("ถึง") },
                            placeholder = { Text("09:00") },
                            singleLine = true,
                            isError = toMin == null,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                    if (!windowValid) {
                        Text(
                            "ใส่เวลาแบบ ชม:นาที เช่น 06:00",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }
    )
}

/**
 * What a tapped block says right now.
 *
 * Sides are named by the colour they are drawn in rather than "left"/"right": the driver is
 * looking at the map while they read this, and left-of-what is the one question the map answers
 * instantly and words don't.
 */
@Composable
fun ParkingInfoCard(
    block: ParkingBlock,
    at: Long,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val states = Side.entries.associateWith { ParkingRules.stateOf(block, it, at) }
    val allowed = states.entries.filter { it.value == ParkingState.ALLOWED }
    val headline = when {
        allowed.isEmpty() -> "วันนี้จอดไม่ได้ทั้งสองฝั่ง"
        else -> "วันนี้จอดได้ฝั่งเส้นเขียว"
    }
    val flipping = Side.entries.any { ParkingRules.flipsOvernight(block, it, at) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = block.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = headline,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color(
                    (if (allowed.isEmpty()) ParkingState.WRONG_DAY else ParkingState.ALLOWED).color
                )
            )
            Side.entries.forEach { side ->
                val state = states.getValue(side)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = Color(state.color),
                        shape = CircleShape,
                        modifier = Modifier.size(12.dp)
                    ) {}
                    Text(
                        text = "  ${block.ruleOf(side).label} — ${state.label}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            if (block.banFromMin != null && block.banToMin != null) {
                Text(
                    text = "ห้ามจอด ${minutesToHhMm(block.banFromMin)}–${minutesToHhMm(block.banToMin)} ทั้งสองฝั่ง",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (flipping) {
                Text(
                    text = "เที่ยงคืนนี้สลับฝั่ง",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onEdit) { Text("แก้ไข") }
                TextButton(onClick = onDelete) { Text("ลบ") }
                TextButton(onClick = onClose) { Text("ปิด") }
            }
        }
    }
}

/**
 * Every block the driver has mapped, nearest first.
 *
 * The map is the natural home for these — you look where you are — but it can only show the blocks
 * on screen, and "did I ever map this soi?" is a question about the ones that aren't. Sorting by
 * distance answers the other one ("anywhere to park near here tonight?") without a search.
 */
@Composable
fun ParkingListDialog(
    blocks: List<ParkingBlock>,
    currentLat: Double?,
    currentLng: Double?,
    at: Long,
    onDismiss: () -> Unit,
    onDraw: () -> Unit,
    onFocus: (ParkingBlock) -> Unit,
    onEdit: (ParkingBlock) -> Unit,
    onDelete: (ParkingBlock) -> Unit
) {
    var query by remember { mutableStateOf("") }
    // null = every block, true = somewhere to park today, false = nowhere.
    var openToday by remember { mutableStateOf<Boolean?>(null) }
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).dp.coerceIn(240.dp, 520.dp)

    val shown = blocks
        .filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
        .filter { openToday == null || (ParkingRules.allowedSide(it, at) != null) == openToday }
        .let { list ->
            if (currentLat != null && currentLng != null) {
                list.sortedBy { ParkingRules.nearest(it, currentLat, currentLng)?.distanceM ?: Double.MAX_VALUE }
            } else {
                list
            }
        }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("ปิด") } },
        dismissButton = { TextButton(onClick = onDraw) { Text("วาดบล็อกใหม่") } },
        title = { Text("บล็อกจอดรถ (${shown.size}/${blocks.size})") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (blocks.isEmpty()) {
                    Text("ยังไม่มีบล็อกที่บันทึกไว้ — กด \"วาดบล็อกใหม่\" แล้วแตะตามแนวถนน")
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("ค้นหาชื่อ") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(openToday == null, { openToday = null }, { Text("ทั้งหมด") })
                        FilterChip(openToday == true, { openToday = true }, { Text("วันนี้จอดได้") })
                        FilterChip(openToday == false, { openToday = false }, { Text("วันนี้จอดไม่ได้") })
                    }
                    if (shown.isEmpty()) {
                        Text("ไม่พบบล็อก")
                    } else {
                        LazyColumn(modifier = Modifier.heightIn(max = listMaxHeight)) {
                            items(shown, key = { it.id }) { b ->
                                ParkingListRow(
                                    block = b,
                                    at = at,
                                    distanceM = if (currentLat != null && currentLng != null) {
                                        ParkingRules.nearest(b, currentLat, currentLng)?.distanceM
                                    } else {
                                        null
                                    },
                                    onFocus = { onFocus(b) },
                                    onEdit = { onEdit(b) },
                                    onDelete = { onDelete(b) }
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        }
    )
}

@Composable
private fun ParkingListRow(
    block: ParkingBlock,
    at: Long,
    distanceM: Double?,
    onFocus: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val open = ParkingRules.allowedSide(block, at) != null
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onFocus)
            .padding(vertical = 4.dp)
    ) {
        // The colour carries the answer here exactly as it does on the map: green means there is a
        // kerb to park on today, red means this street has nothing for you.
        Surface(
            color = Color((if (open) ParkingState.ALLOWED else ParkingState.WRONG_DAY).color),
            shape = CircleShape,
            modifier = Modifier
                .padding(end = 10.dp)
                .size(14.dp)
        ) {}
        Column(Modifier.weight(1f)) {
            Text(
                text = distanceM?.let { "${block.name}  ·  ${distanceText(it)}" } ?: block.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = blockDetail(block, at),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
        TextButton(onClick = onEdit) { Text("แก้ไข") }
        TextButton(onClick = onDelete) { Text("ลบ") }
    }
}

/** One line of rules: what each kerb allows, plus the hours ban and today's verdict. Uses the
 *  short rule labels — a list row has one line to say it in, unlike the card on the map. */
private fun blockDetail(block: ParkingBlock, at: Long): String = buildString {
    val rules = Side.entries.map { block.ruleOf(it) }
    append(rules.joinToString(" / ") { it.shortLabel })
    if (block.banFromMin != null && block.banToMin != null) {
        append(" · ห้ามจอด ${minutesToHhMm(block.banFromMin)}–${minutesToHhMm(block.banToMin)}")
    }
    append(if (ParkingRules.allowedSide(block, at) != null) " · วันนี้จอดได้" else " · วันนี้จอดไม่ได้")
}

private fun distanceText(m: Double): String =
    if (m >= 1000) "%.1f กม.".format(m / 1000.0) else "${m.toInt()} ม."

/**
 * What the app says the moment the car settles on a mapped block — and only when there is
 * something to do about it: the wrong kerb, or the right one that stops being right at midnight.
 * A card confirming a correct park would be on screen for hours saying nothing.
 */
@Composable
fun ParkedOnCard(
    parked: LocationBus.ParkedOn,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val wrong = parked.state != ParkingState.ALLOWED
    val color = if (wrong) Color(parked.state.color) else Color(ParkingState.BANNED_NOW.color)
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = color,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Box {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, top = 12.dp, bottom = 12.dp, end = 88.dp)
            ) {
                Text(
                    text = if (wrong) parked.state.label else "เที่ยงคืนนี้ฝั่งจอดสลับ",
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = parked.blockName,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (wrong) {
                        "ลองย้ายไปจอดอีกฝั่ง"
                    } else {
                        "จะเตือนอีกครั้ง ${minutesToHhMm(ParkingRules.REMIND_HOUR * 60 + ParkingRules.REMIND_MINUTE)} น."
                    },
                    color = Color.White.copy(alpha = .9f),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            DismissCorner(cornerRadius = 12.dp, onDismiss = onDismiss)
        }
    }
}

/**
 * The instruction strip that owns the top of the screen while a block is being drawn. It states
 * the one thing to do next and carries the way out of the mode — nothing about drawing is
 * discoverable from the map itself, so it has to be said in words.
 */
@Composable
fun ParkingDrawBar(
    draft: ParkingDraft,
    onUndo: () -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 6.dp
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 6.dp)) {
            Text(
                text = when (draft.stage) {
                    ParkingDraft.Stage.PATH -> "แตะตามแนวถนน · จากหัวบล็อกถึงท้ายบล็อก"
                    ParkingDraft.Stage.SIDE -> "แตะฝั่งที่จอดได้วันคี่"
                },
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = when (draft.stage) {
                    ParkingDraft.Stage.PATH ->
                        if (draft.path.isEmpty()) "ถนนตรงแตะแค่ 2 จุดก็พอ ถนนโค้งแตะเพิ่มได้"
                        else "${draft.path.size} จุด"
                    ParkingDraft.Stage.SIDE -> "แตะข้างเส้นฝั่งที่ต้องการ — อีกฝั่งจะเป็นวันคู่เอง"
                },
                style = MaterialTheme.typography.bodySmall
            )
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onCancel) { Text("ยกเลิก") }
                if (draft.stage == ParkingDraft.Stage.PATH) {
                    TextButton(onClick = onUndo, enabled = draft.path.isNotEmpty()) { Text("ถอย") }
                    TextButton(onClick = onNext, enabled = draft.canAdvance) { Text("ต่อไป") }
                } else {
                    TextButton(onClick = onUndo) { Text("ย้อนกลับ") }
                }
            }
        }
    }
}

private fun ruleOf(left: SideRule, right: SideRule, side: Side): SideRule =
    if (side == Side.LEFT) left else right

/** Minutes from midnight as "06:00". */
fun minutesToHhMm(min: Int): String = "%02d:%02d".format(min / 60, min % 60)

/** Lenient "6:00" / "06:00" / "0600" → minutes from midnight; null when it isn't a time. */
fun parseHhMm(text: String): Int? {
    val digits = text.filter { it.isDigit() }
    val (h, m) = when {
        text.contains(':') -> {
            val parts = text.split(':')
            if (parts.size != 2) return null
            (parts[0].trim().toIntOrNull() ?: return null) to (parts[1].trim().toIntOrNull() ?: return null)
        }
        digits.length == 4 -> digits.substring(0, 2).toInt() to digits.substring(2).toInt()
        digits.length in 1..2 -> digits.toInt() to 0
        else -> return null
    }
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}
