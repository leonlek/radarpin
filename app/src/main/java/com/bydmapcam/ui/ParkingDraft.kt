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
    /**
     * How long the path was after each tap. One tap can now bring a whole curve of the road with
     * it, and undo has to mean "that tap" — pressing it eight times to take back one touch would
     * be a worse tool than the one that made you tap eight times in the first place.
     */
    val taps: List<Int> = emptyList(),
    val stage: Stage = Stage.PATH,
    /**
     * The block being re-traced, if this draft is a correction rather than a new block. Its rules
     * ride along untouched — a line drawn slightly off the road is the common mistake, and having
     * to delete the block and re-enter which kerb is odd is a punishment for a wobbly finger.
     */
    val editing: ParkingBlock? = null
) {
    enum class Stage {
        /** Tapping out the centre line of the block. */
        PATH,

        /** Line drawn; tapping the kerb that may be parked on during odd days. */
        SIDE
    }

    /** Two taps on the same spot draw a line with no sides to pick — don't let that step start. */
    val canAdvance: Boolean get() = path.size >= 2 && ParkingRules.pathLengthM(path) >= MIN_BLOCK_M

    /** The path with [points] appended, remembered as one tap so undo can take them back together. */
    fun plus(points: List<GeoPoint>): ParkingDraft {
        if (points.isEmpty()) return this
        val grown = path + points
        return copy(path = grown, taps = taps + grown.size)
    }

    /** Back one tap — or, on a line loaded for re-tracing, back one of its original vertices. */
    fun undo(): ParkingDraft {
        if (path.isEmpty()) return this
        val cut = if (taps.size >= 2) taps[taps.size - 2] else 0
        return copy(path = path.take(cut), taps = taps.dropLast(1))
    }

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
