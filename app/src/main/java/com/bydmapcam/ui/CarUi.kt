package com.bydmapcam.ui

import android.content.res.Resources

/**
 * One definition of "this screen is a head unit, not a phone", shared by the activity (which scales
 * its whole density) and the service (which draws the over-other-apps banner with plain views).
 *
 * dp is a fixed physical size, so it can't know that a dash screen sits ~80 cm away instead of
 * ~30 cm in the hand — everything simply has to be bigger there.
 */
object CarUi {
    /** Tablet/head-unit breakpoint. */
    const val SW_DP = 600

    /** Tuned against the Atto 3's 12.8" panel. */
    const val SCALE = 1.9f

    /**
     * Deliberately reads the *system* configuration: the activity inflates its own density, after
     * which its config would report a narrower screen and the next check would undo the scaling.
     */
    val isCar: Boolean
        get() = Resources.getSystem().configuration.smallestScreenWidthDp >= SW_DP

    val scale: Float get() = if (isCar) SCALE else 1f
}
