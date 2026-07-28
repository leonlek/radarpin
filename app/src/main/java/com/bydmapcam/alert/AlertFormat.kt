package com.bydmapcam.alert

/**
 * Wording shared by the in-app banner and the over-other-apps overlay, so both read the same.
 *
 * The countdown stops at [FLOOR_M]: closer than that the metres are mostly GPS jitter and the exact
 * number stops being useful to a driver — you're at the point, eyes on the road, not on the digits.
 */
object AlertFormat {
    const val FLOOR_M = 100

    /** "อีก 350 ม." while counting down, then a fixed "ถึงจุดแล้ว" once inside [FLOOR_M]. */
    fun countdown(meters: Int): String =
        if (meters >= FLOOR_M) "อีก $meters ม." else "ถึงจุดแล้ว"
}
