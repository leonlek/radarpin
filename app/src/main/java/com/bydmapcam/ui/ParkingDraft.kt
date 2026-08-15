package com.bydmapcam.ui

import com.bydmapcam.data.GeoPoint
import com.bydmapcam.data.ParkingBlock
import com.bydmapcam.data.Side
import com.bydmapcam.data.SideRule
import com.bydmapcam.parking.ParkingRules

/**
 * A parking block in the middle of being drawn.
 *
 * The two stages are deliberately separate map gestures rather than one form: tracing the street
 * and naming the kerb are different questions, and the second one is only answerable once the
 * first is on screen — "which side is odd?" means nothing until there is a line to have sides.
 */
data class ParkingDraft(
    val path: List<GeoPoint> = emptyList(),
    val stage: Stage = Stage.PATH
) {
    enum class Stage {
        /** Tapping out the centre line of the block. */
        PATH,

        /** Line drawn; tapping the kerb that may be parked on during odd days. */
        SIDE
    }

    /** Two taps on the same spot draw a line with no sides to pick — don't let that step start. */
    val canAdvance: Boolean get() = path.size >= 2 && ParkingRules.pathLengthM(path) >= MIN_BLOCK_M

    private companion object {
        const val MIN_BLOCK_M = 10.0
    }
}

/**
 * The kerb the driver originally tapped, recovered from a saved block so editing opens on the same
 * question it was created with: the odd-day side, or — on a one-sided street — the usable one.
 */
fun ParkingBlock.pickedSide(): Side = when {
    leftRule == SideRule.ODD_DAYS -> Side.LEFT
    rightRule == SideRule.ODD_DAYS -> Side.RIGHT
    rightRule == SideRule.NEVER -> Side.LEFT
    leftRule == SideRule.NEVER -> Side.RIGHT
    else -> Side.LEFT
}

/** Middle of the traced line — where the camera should land when the block is picked from a list. */
fun ParkingBlock.center(): Pair<Double, Double> {
    val first = path.first()
    val last = path.last()
    return (first.lat + last.lat) / 2 to (first.lng + last.lng) / 2
}

/** What the details dialog is about to save: the geometry plus the kerb the driver picked. */
data class PendingBlock(
    val path: List<GeoPoint>,
    val oddSide: Side,
    /** Non-null when the rules of an existing block are being edited rather than a new one drawn. */
    val existing: ParkingBlock? = null
)
