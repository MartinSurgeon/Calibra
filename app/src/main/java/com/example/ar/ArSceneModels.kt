package com.example.ar

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Offset
import com.example.domain.ShapeMode
import com.example.domain.Vec3

/** Lifecycle of the ARCore session as seen by the UI. */
sealed interface ArSessionStatus {
    data object Idle : ArSessionStatus
    data object Starting : ArSessionStatus
    data object Running : ArSessionStatus
    data object Paused : ArSessionStatus

    /** [fatal] failures mean AR cannot run on this device/session; the UI falls back to 2D. */
    data class Failed(val message: String, val fatal: Boolean) : ArSessionStatus
}

enum class ArTrackingQuality { INITIALIZING, TRACKING, LIMITED, STOPPED }

/** What the center reticle is resting on. */
enum class ArSurfaceKind(val label: String) {
    HORIZONTAL("SURFACE"),
    VERTICAL("WALL"),
    DEPTH("DEPTH"),
    FEATURE("POINT"),
    NODE("SNAP"),
    HEIGHT("HEIGHT")
}

data class ArReticle(
    /** Where the reticle is drawn (the live point, or the screen center when nothing is hit). */
    val center: Offset,
    /** Perspective-correct ring lying on the hit surface; empty when nothing is hit. */
    val ring: List<Offset>,
    val kind: ArSurfaceKind?,
    val distanceM: Double?,
    /** "LEVEL" / "PLUMB" when the live segment is axis-locked. */
    val axisLock: String?
)

data class ArRenderEdge(
    val a: Offset,
    val b: Offset,
    /** World length in meters to label on the edge, or null for unlabeled guide lines. */
    val lengthM: Double?,
    val dashed: Boolean = false
)

data class ArCenterLabel(val position: Offset, val kind: Kind, val value: Double) {
    /** Values are SI: m² for AREA, m³ for VOLUME, degrees for ANGLE. */
    enum class Kind { AREA, VOLUME, ANGLE }
}

data class ArRenderShape(
    val mode: ShapeMode,
    val isActive: Boolean,
    val nodes: List<Offset>,
    val liveNode: Offset?,
    val edges: List<ArRenderEdge>,
    val fill: List<Offset>,
    val centerLabel: ArCenterLabel?
)

/** Everything the overlay needs to draw one AR frame, in view pixels. */
data class ArFrameState(
    /** First so `equals` short-circuits cheaply between frames. */
    val frameNumber: Long = 0L,
    val status: ArSessionStatus = ArSessionStatus.Idle,
    val tracking: ArTrackingQuality = ArTrackingQuality.INITIALIZING,
    val hint: String? = null,
    val surfacesFound: Boolean = false,
    val depthActive: Boolean = false,
    val viewWidth: Int = 0,
    val viewHeight: Int = 0,
    val planeDots: List<Offset> = emptyList(),
    val planeOutlines: List<List<Offset>> = emptyList(),
    val reticle: ArReticle? = null,
    val shapes: List<ArRenderShape> = emptyList()
)

/**
 * The measurement currently in focus (the shape being built, or the last finished one of the
 * active mode), in world meters. Feeds the ViewModel's live result and the save flow.
 */
data class ArMeasurementSnapshot(
    val mode: ShapeMode,
    /** Placed points followed by the live (reticle) point when [hasLivePoint] is true. */
    val points: List<Vec3>,
    val placedCount: Int,
    val hasLivePoint: Boolean,
    val isComplete: Boolean,
    val completedCount: Int
) {
    val hasAnyPoints: Boolean get() = placedCount > 0 || isComplete

    companion object {
        fun empty(mode: ShapeMode) = ArMeasurementSnapshot(
            mode = mode,
            points = emptyList(),
            placedCount = 0,
            hasLivePoint = false,
            isComplete = false,
            completedCount = 0
        )
    }
}

sealed interface ArEvent {
    data class PointPlaced(val count: Int) : ArEvent
    data class ShapeCompleted(val mode: ShapeMode) : ArEvent
    data class Message(val text: String) : ArEvent
}

/** A camera frame read back from GL together with the overlay state of that exact frame. */
class ArCapture(val bitmap: Bitmap, val state: ArFrameState)
