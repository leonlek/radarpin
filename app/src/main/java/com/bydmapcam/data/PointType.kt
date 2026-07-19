package com.bydmapcam.data

enum class PointType(val label: String, val defaultAlert: Boolean) {
    SPEED_CAMERA("กล้องจับความเร็ว", true),
    POI("จุดสนใจ", false),
    EV_STATION("ปั๊ม EV", false)
}
