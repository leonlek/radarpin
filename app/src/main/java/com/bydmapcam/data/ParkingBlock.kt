package com.bydmapcam.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** A plain lat/lng pair — the path is geometry we store ourselves, not a MapLibre type. */
data class GeoPoint(val lat: Double, val lng: Double)

/**
 * Which kerb of the block a rule belongs to, named relative to **the direction the path was
 * drawn**, not to the compass. That is the only frame the driver can point at: they trace the
 * block on the map and then tap the kerb they mean, and both halves of that gesture live in the
 * same frame. Turning it into a compass side would mean asking "north or south?" about a road
 * nobody thinks of that way.
 */
enum class Side { LEFT, RIGHT }

fun Side.other(): Side = if (this == Side.LEFT) Side.RIGHT else Side.LEFT

/**
 * What one kerb of a block allows. The usual Bangkok block is a pair — one kerb [ODD_DAYS], the
 * other [EVEN_DAYS] — but a kerb that can never be parked on ([NEVER]) or one with no alternation
 * at all ([ALWAYS]) are both real, and spelling each side out separately covers them without a
 * special case.
 */
enum class SideRule(val label: String) {
    ODD_DAYS("จอดได้วันคี่"),
    EVEN_DAYS("จอดได้วันคู่"),
    ALWAYS("จอดได้ทุกวัน"),
    NEVER("ห้ามจอดตลอด")
}

/**
 * One stretch of street between two junctions, with the parking rule for each kerb.
 *
 * A block is stored as a single row covering *both* kerbs because that is how the rule works: the
 * two sides alternate as a pair, and splitting them into two rows would let them drift into a
 * state the street can't be in (both sides odd).
 *
 * [banFromMin]/[banToMin] are minutes from midnight and may wrap past it (22:00–06:00). Both null
 * means the driver only wanted the which-side-today guidance and didn't fill a time window in.
 */
@Entity(tableName = "parking_blocks")
data class ParkingBlock(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Centre line of the block, in the order it was drawn; the kerbs are drawn offset from it. */
    val path: List<GeoPoint>,
    val leftRule: SideRule,
    val rightRule: SideRule,
    val banFromMin: Int? = null,
    val banToMin: Int? = null,
    val createdAt: Long
) {
    fun ruleOf(side: Side): SideRule = if (side == Side.LEFT) leftRule else rightRule
}
