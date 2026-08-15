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

    /** How far a mitered corner may reach, as a multiple of the offset, before it is cut short. */
    private const val MAX_MITER = 4.0

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

    /** The number on the sign: what "odd" and "even" are actually about. */
    fun dayOfMonth(at: Long): Int = calendar(at).get(Calendar.DAY_OF_MONTH)

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

    /** The kerb that may be parked on at [at], or null when neither can be — both are real. */
    fun allowedSide(block: ParkingBlock, at: Long): Side? =
        Side.entries.firstOrNull { stateOf(block, it, at) == ParkingState.ALLOWED }

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

    /**
     * The traced centre line pushed [metres] sideways onto one kerb.
     *
     * Done in metres here rather than with MapLibre's pixel `line-offset` because a kerb is a
     * real distance from the middle of the road, not a screen distance: a fixed pixel offset sits
     * neatly beside the road at one zoom and buried under it at the next, since the basemap draws
     * roads wider as you zoom in. Offsetting the geometry keeps the two kerbs straddling the road
     * at every zoom, which is also what makes them readable — the road stays visible between them.
     *
     * Corners are mitered properly: the averaged normal of the two adjoining segments is *shorter*
     * than the offset by the cosine of half the turn, so simply scaling it pinches the kerb inward
     * at every bend — by a third at a right angle — and on the outside of a curve that pinch puts
     * the line back on the road, which is exactly what a curved block looked like. Dividing by that
     * cosine restores the distance; the clamp stops a hairpin from throwing a spike into the next
     * district, where a bevel would be the right answer and nobody traces a block anyway.
     */
    fun offsetPath(path: List<GeoPoint>, side: Side, metres: Double): List<GeoPoint> {
        if (path.size < 2) return path
        val sign = if (side == Side.LEFT) 1.0 else -1.0
        // Left of travel in a local metre frame (x east, y north) is (-dy, dx) normalised.
        val normals = ArrayList<Pair<Double, Double>>(path.size - 1)
        for (i in 0 until path.size - 1) {
            val cosLat = cos(Math.toRadians(path[i].lat))
            val dx = (path[i + 1].lng - path[i].lng) * M_PER_DEG_LNG * cosLat
            val dy = (path[i + 1].lat - path[i].lat) * M_PER_DEG_LAT
            val len = sqrt(dx * dx + dy * dy)
            normals.add(if (len == 0.0) 0.0 to 0.0 else (-dy / len) to (dx / len))
        }
        return path.mapIndexed { i, p ->
            val a = normals[(i - 1).coerceAtLeast(0)]
            val b = normals[i.coerceAtMost(normals.size - 1)]
            var nx = a.first + b.first
            var ny = a.second + b.second
            val len = sqrt(nx * nx + ny * ny)
            if (len == 0.0) return@mapIndexed p
            nx /= len
            ny /= len
            // cos of half the turn: the miter direction against either segment's own normal.
            val cosHalf = nx * a.first + ny * a.second
            val reach = metres * (if (cosHalf < 1.0 / MAX_MITER) MAX_MITER else 1.0 / cosHalf)
            val cosLat = cos(Math.toRadians(p.lat))
            GeoPoint(
                lat = p.lat + sign * ny * reach / M_PER_DEG_LAT,
                lng = p.lng + sign * nx * reach / (M_PER_DEG_LNG * cosLat)
            )
        }
    }

    /** The point half way along a path, by distance — where a label for the whole block belongs. */
    fun midpoint(path: List<GeoPoint>): GeoPoint? {
        if (path.isEmpty()) return null
        if (path.size == 1) return path[0]
        val half = pathLengthM(path) / 2.0
        var walked = 0.0
        for (i in 0 until path.size - 1) {
            val seg = GeoUtils.distanceMeters(
                path[i].lat, path[i].lng, path[i + 1].lat, path[i + 1].lng
            )
            if (walked + seg >= half) {
                val t = if (seg == 0.0) 0.0 else (half - walked) / seg
                return GeoPoint(
                    lat = path[i].lat + (path[i + 1].lat - path[i].lat) * t,
                    lng = path[i].lng + (path[i + 1].lng - path[i].lng) * t
                )
            }
            walked += seg
        }
        return path.last()
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
