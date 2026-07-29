package com.bydmapcam.data

/**
 * [alertColor] is the banner colour for this kind of point, so a glance tells you what's ahead
 * before you've read a word: red for a camera you can be fined by, green for a charger, blue for
 * anything you merely saved. [alertColorNear] is the same hue, darkened, for "you're on top of it".
 *
 * Kept as ARGB longs rather than Compose colours because the over-other-apps banner is drawn by the
 * service with plain Android views and has to match exactly.
 */
enum class PointType(
    val label: String,
    val defaultAlert: Boolean,
    val alertColor: Long,
    val alertColorNear: Long
) {
    SPEED_CAMERA("กล้องจับความเร็ว", true, 0xFFE53935, 0xFFC1231F),
    POI("จุดสนใจ", false, 0xFF1565C0, 0xFF0D3F87),
    EV_STATION("ปั๊ม EV", false, 0xFF2E7D32, 0xFF1B5A1F)
}
