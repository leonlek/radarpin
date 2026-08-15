package com.bydmapcam.parking

import com.bydmapcam.data.GeoPoint
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.Side
import com.bydmapcam.data.SideRule
import com.bydmapcam.location.GeoUtils
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * What one kerb of a block says about right now. [color] lives here, next to the meaning, because
 * the same four states are painted by the map layer, the info card and the parked-car warning —
 * kept as ARGB longs for the same reason [com.bydmapcam.data.PointType] is: not every consumer has
 * a Compose colour to hand.
 */
enum class ParkingState(val label: String, val color: Long) {
    /** Park here today. */
    ALLOWED("จอดได้วันนี้", 0xFF2E7D32),

    /** This kerb's day is the other one — legal tomorrow, not now. */
    WRONG_DAY("วันนี้ห้ามจอดฝั่งนี้", 0xFFC62828),

    /** The right kerb, but inside the block's no-parking hours. */
    BANNED_NOW("ช่วงเวลานี้ห้ามจอด", 0xFFEF6C00),

    /** This kerb is never parked on. */
    BANNED_ALWAYS("ห้ามจอดตลอด", 0xFF616161)
}

/**
 * Odd/even day parking, and where a car sits relative to a block.
 *
 * Everything here is pure: the same block and the same instant always give the same answer, which
 * is what lets the map colours, the parked-car check and the before-midnight reminder all agree
 * without passing state between them.
 */
object ParkingRules {

    /** Metres per degree — a flat-earth frame is exact enough over one city block. */
    private const val M_PER_DEG_LAT = 110_540.0
    private const val M_PER_DEG_LNG = 111_320.0

    /** How close to the centre line a car has to be to count as parked on this block. */
    const val ON_BLOCK_M = 25.0

    /** When the "the kerb swaps at midnight" reminder goes off. */
    const val REMIND_HOUR = 23
    const val REMIND_MINUTE = 30

    /**
     * Odd/even is the day of the month exactly as the street sign means it — including the 31st
     * running straight into the 1st, two odd days back to back. Nothing here tries to be cleverer
     * than the sign; see [flipsOvernight], which is where that quirk actually matters.
     */
    fun isOddDay(at: Long): Boolean = calendar(at).get(Calendar.DAY_OF_MONTH) % 2 == 1

    /** Changes exactly when the calendar day does — a cheap key for "redraw, it's tomorrow now". */
    fun dayKey(at: Long): Int = with(calendar(at)) {
        get(Calendar.YEAR) * 10_000 + get(Calendar.MONTH) * 100 + get(Calendar.DAY_OF_MONTH)
    }

    /** The kerb's rule alone, ignoring any hours ban — this is what changes at midnight. */
    fun dayStateOf(block: ParkingBlock, side: Side, at: Long): ParkingState =
        when (block.ruleOf(side)) {
            SideRule.NEVER -> ParkingState.BANNED_ALWAYS
            SideRule.ALWAYS -> ParkingState.ALLOWED
            SideRule.ODD_DAYS -> if (isOddDay(at)) ParkingState.ALLOWED else ParkingState.WRONG_DAY
            SideRule.EVEN_DAYS -> if (isOddDay(at)) ParkingState.WRONG_DAY else ParkingState.ALLOWED
        }

    /** The full answer for a kerb at an instant: the day rule, then the hours ban over the top. */
    fun stateOf(block: ParkingBlock, side: Side, at: Long): ParkingState {
        val day = dayStateOf(block, side, at)
        if (day != ParkingState.ALLOWED) return day
        return if (inBanWindow(block, at)) ParkingState.BANNED_NOW else ParkingState.ALLOWED
    }

    /** True while [at] falls in the block's no-parking hours, which may wrap past midnight. */
    fun inBanWindow(block: ParkingBlock, at: Long): Boolean {
        val from = block.banFromMin ?: return false
        val to = block.banToMin ?: return false
        if (from == to) return false
        val c = calendar(at)
        val m = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        return if (from < to) m in from until to else m >= from || m < to
    }

    /**
     * Whether a car parked on [side] has to be moved before midnight. False on the 31st→1st (both
     * odd, the kerb doesn't swap) and false for a kerb whose rule doesn't depend on the date at
     * all — the reminder has to be silent on the nights nothing changes, or it stops being read.
     */
    fun flipsOvernight(block: ParkingBlock, side: Side, at: Long): Boolean {
        if (dayStateOf(block, side, at) != ParkingState.ALLOWED) return false
        return dayStateOf(block, side, nextDay(at)) != ParkingState.ALLOWED
    }

    /** When tonight's reminder should fire; at least a minute out if it's already past 23:30. */
    fun reminderAt(at: Long): Long {
        val c = calendar(at).apply {
            set(Calendar.HOUR_OF_DAY, REMIND_HOUR)
            set(Calendar.MINUTE, REMIND_MINUTE)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return if (c.timeInMillis <= at) at + 60_000L else c.timeInMillis
    }

    /** Which kerb the car is on, and how far it is from the block's centre line. */
    data class Hit(val distanceM: Double, val side: Side)

    /**
     * Nearest point on the block to a position, in a local metre frame centred on that position.
     * The sign of the cross product against the segment gives the kerb: positive is left of the
     * direction the path was drawn, which is the same frame the driver picked the side in.
     */
    fun nearest(block: ParkingBlock, lat: Double, lng: Double): Hit? =
        nearestOnPath(block.path, lat, lng)

    /** The same question about a path that isn't a saved block yet — one being drawn. */
    fun nearestOnPath(path: List<GeoPoint>, lat: Double, lng: Double): Hit? {
        if (path.size < 2) return null
        val cosLat = cos(Math.toRadians(lat))
        fun x(p: GeoPoint) = (p.lng - lng) * M_PER_DEG_LNG * cosLat
        fun y(p: GeoPoint) = (p.lat - lat) * M_PER_DEG_LAT

        var best: Hit? = null
        for (i in 0 until path.size - 1) {
            val ax = x(path[i])
            val ay = y(path[i])
            val bx = x(path[i + 1])
            val by = y(path[i + 1])
            val abx = bx - ax
            val aby = by - ay
            val len2 = abx * abx + aby * aby
            if (len2 == 0.0) continue
            // The car is the origin of this frame, so A→car is simply -A.
            val t = (((-ax) * abx) + ((-ay) * aby)) / len2
            val clamped = t.coerceIn(0.0, 1.0)
            val cx = ax + abx * clamped
            val cy = ay + aby * clamped
            val d = sqrt(cx * cx + cy * cy)
            if (best == null || d < best.distanceM) {
                val cross = abx * (-ay) - aby * (-ax)
                best = Hit(d, if (cross >= 0) Side.LEFT else Side.RIGHT)
            }
        }
        return best
    }

    /** Length of a traced path in metres — a line with no length has no sides to pick. */
    fun pathLengthM(path: List<GeoPoint>): Double {
        var total = 0.0
        for (i in 0 until path.size - 1) {
            total += GeoUtils.distanceMeters(
                path[i].lat, path[i].lng, path[i + 1].lat, path[i + 1].lng
            )
        }
        return total
    }

    /** The block the car is standing on, if any — nearest centre line within [ON_BLOCK_M]. */
    fun blockAt(blocks: List<ParkingBlock>, lat: Double, lng: Double): Pair<ParkingBlock, Hit>? =
        blocks.mapNotNull { b -> nearest(b, lat, lng)?.let { b to it } }
            .filter { it.second.distanceM <= ON_BLOCK_M }
            .minByOrNull { it.second.distanceM }

    private fun calendar(at: Long): Calendar =
        Calendar.getInstance().apply { timeInMillis = at }

    private fun nextDay(at: Long): Long =
        calendar(at).apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis
}
