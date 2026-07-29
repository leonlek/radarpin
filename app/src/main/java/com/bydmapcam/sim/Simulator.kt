package com.bydmapcam.sim

import android.location.Location
import android.os.Build
import com.bydmapcam.data.AlertPoint
import com.bydmapcam.data.PointType
import com.bydmapcam.media.MediaLink
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fakes the states you'd otherwise have to go driving to see — an alert counting down, an alert
 * you've arrived at, two at once, music playing.
 *
 * It never touches the real data: [state] is merged over the live values in the UI layer only, and
 * the fake points carry negative ids so they can't collide with anything in the database. The
 * button that drives it only appears on an emulator ([isEmulator]).
 */
object Simulator {
    data class State(
        val label: String,
        val points: List<AlertPoint> = emptyList(),
        val activeIds: Set<Long> = emptySet(),
        val distances: Map<Long, Int> = emptyMap(),
        val infoIds: Set<Long> = emptySet(),
        val speedKmh: Int? = null,
        val nowPlaying: MediaLink.NowPlaying? = null
    )

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    val isEmulator: Boolean by lazy {
        Build.FINGERPRINT.startsWith("generic") ||
            Build.FINGERPRINT.lowercase().contains("emulator") ||
            Build.MODEL.contains("Emulator") ||
            Build.MODEL.contains("Android SDK built for") ||
            Build.PRODUCT.startsWith("sdk")
    }

    fun clear() {
        _state.value = null
    }

    /** One camera ahead, still counting down. */
    fun alertFar(here: Location?) = set(
        State(
            label = "เตือนไกล 350 ม.",
            points = listOf(fakePoint(ID_A, "กล้องหน้าโรงเรียน", PointType.SPEED_CAMERA, here, 0.0035)),
            activeIds = setOf(ID_A),
            distances = mapOf(ID_A to 350),
            speedKmh = 88
        )
    )

    /** Inside the radius — the countdown has bottomed out at "ถึงจุดแล้ว". */
    fun alertNear(here: Location?) = set(
        State(
            label = "ถึงจุดเตือนแล้ว",
            points = listOf(fakePoint(ID_A, "กล้องหน้าโรงเรียน", PointType.SPEED_CAMERA, here, 0.0006)),
            activeIds = setOf(ID_A),
            distances = mapOf(ID_A to 60),
            speedKmh = 64
        )
    )

    /** Two points at once — checks the "ถัดไป" row and that the card doesn't grow unbounded. */
    fun alertTwo(here: Location?) = set(
        State(
            label = "เตือน 2 จุดพร้อมกัน",
            points = listOf(
                fakePoint(ID_A, "กล้องหน้าโรงเรียน", PointType.SPEED_CAMERA, here, 0.0018),
                fakePoint(ID_B, "ปั๊ม EV บางจาก", PointType.EV_STATION, here, 0.0042)
            ),
            activeIds = setOf(ID_A, ID_B),
            distances = mapOf(ID_A to 180, ID_B to 430),
            speedKmh = 96
        )
    )

    /** A charger ahead — green banner. */
    fun alertEv(here: Location?) = set(
        State(
            label = "เตือนปั๊ม EV",
            points = listOf(fakePoint(ID_A, "ปั๊ม EV บางจาก", PointType.EV_STATION, here, 0.0024)),
            activeIds = setOf(ID_A),
            distances = mapOf(ID_A to 240),
            speedKmh = 78
        )
    )

    /** A saved place ahead — pops open on the map rather than taking over with a banner. */
    fun alertPoi(here: Location?) = set(
        State(
            label = "จุดสนใจเด้ง",
            points = listOf(fakePoint(ID_A, "ร้านกาแฟบ้านสวน", PointType.POI, here, 0.0012)),
            infoIds = setOf(ID_A),
            distances = mapOf(ID_A to 130),
            speedKmh = 64
        )
    )

    /** INFO style: no banner and no beep, the icon just pops up on the map within ~200 m. */
    fun infoPop(here: Location?) = set(
        State(
            label = "จุดแบบ info เด้ง",
            points = listOf(
                fakePoint(ID_C, "ทางร่วมทางแยก", PointType.POI, here, 0.0009).copy(infoMode = true)
            ),
            infoIds = setOf(ID_C),
            distances = mapOf(ID_C to 90),
            speedKmh = 58
        )
    )

    /** Everything at once — the honest test of whether the screen still reads while driving. */
    fun everything(here: Location?) = set(
        State(
            label = "ทุกอย่างพร้อมกัน",
            points = listOf(
                fakePoint(ID_A, "กล้องหน้าโรงเรียน", PointType.SPEED_CAMERA, here, 0.0018),
                fakePoint(ID_B, "ปั๊ม EV บางจาก", PointType.EV_STATION, here, 0.0042),
                fakePoint(ID_C, "ทางร่วมทางแยก", PointType.POI, here, 0.0009).copy(infoMode = true)
            ),
            activeIds = setOf(ID_A, ID_B),
            distances = mapOf(ID_A to 180, ID_B to 430, ID_C to 90),
            infoIds = setOf(ID_C),
            speedKmh = 104,
            nowPlaying = MediaLink.NowPlaying("ลมเปลี่ยนทิศ", "บอย โกสิยพงษ์", playing = true)
        )
    )

    /** Music from another app, without needing another app. */
    fun media() = set(
        State(
            label = "แถบเพลง",
            nowPlaying = MediaLink.NowPlaying("ลมเปลี่ยนทิศ", "บอย โกสิยพงษ์", playing = true),
            speedKmh = 72
        )
    )

    private fun set(s: State) {
        _state.value = s
    }

    /** Drops the fake point a little north of wherever we are, so it lands on screen. */
    private fun fakePoint(
        id: Long,
        name: String,
        type: PointType,
        here: Location?,
        offsetDeg: Double
    ) = AlertPoint(
        id = id,
        name = name,
        type = type,
        lat = (here?.latitude ?: 13.7563) + offsetDeg,
        lng = here?.longitude ?: 100.5018,
        radiusM = 500,
        alertEnabled = true,
        alertSound = false, // a demo shouldn't beep at you
        infoMode = false,
        createdAt = 0L
    )

    private const val ID_A = -101L
    private const val ID_B = -102L
    private const val ID_C = -103L
}
