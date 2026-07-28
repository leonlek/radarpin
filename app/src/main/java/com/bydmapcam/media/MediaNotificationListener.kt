package com.bydmapcam.media

import android.service.notification.NotificationListenerService

/**
 * Does nothing with notifications — it exists purely because Android only hands out other apps'
 * MediaSessions to a component the user has granted "notification access" to. Enabling it is what
 * turns the media bar from blind transport keys into a real "now playing" readout.
 */
class MediaNotificationListener : NotificationListenerService()
