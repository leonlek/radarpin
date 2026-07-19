package com.bydmapcam.location

import kotlinx.coroutines.flow.MutableStateFlow

/** Tracks whether our own UI is currently visible, so the service only shows the
 *  over-other-apps overlay when the user is NOT looking at our in-app banner. */
object AppState {
    val inForeground = MutableStateFlow(false)
}
