package com.example.ar

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.geometry.Offset
import androidx.core.content.ContextCompat
import com.example.domain.ShapeMode
import com.example.domain.SpatialMeasurementEngine
import com.example.domain.Vec3
import com.google.ar.core.Anchor
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.DepthPoint
import com.google.ar.core.Frame
import com.google.ar.core.HitResult
import com.google.ar.core.Plane
import com.google.ar.core.Point
import com.google.ar.core.Pose
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.UnavailableApkTooOldException
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException
import com.google.ar.core.exceptions.UnavailableSdkTooOldException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Owns the ARCore [Session] and the world-anchored measurement model, and renders the camera
 * feed through a [GLSurfaceView]. Every GL frame it hit-tests the screen center, resolves the
 * live target (surface hit, node snap, axis lock or box height), applies queued user commands,
 * projects the whole scene into view pixels and publishes:
 *
 * - [frameState]: everything the Compose overlay draws (planes, reticle, shapes, labels)
 * - [measurement]: the focused shape in world meters, for live results and saving
 * - [events]: one-shot feedback (point placed, shape completed, guidance messages)
 *
 * Threading: lifecycle methods run on the main thread; the measurement model is touched only on
 * the GL thread (via [commands]) except in [close], which runs after the GL thread is paused.
 */
class ArSessionController(context: Context) : GLSurfaceView.Renderer {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val backgroundRenderer = BackgroundRenderer()
    private val displayRotationHelper = DisplayRotationHelper(appContext)
    private val commands = ConcurrentLinkedQueue<Command>()
    private val captureRequests = ConcurrentLinkedQueue<CompletableDeferred<ArCapture?>>()

    @Volatile private var session: Session? = null
    @Volatile private var arConfig: Config? = null
    @Volatile private var isSessionResumed = false
    @Volatile private var status: ArSessionStatus = ArSessionStatus.Idle

    // Main-thread lifecycle bookkeeping.
    private var hostResumed = false
    private var glView: GLSurfaceView? = null
    private var resumeAttempts = 0
    private val retryResume = Runnable { tryResume() }

    // Settings requested by the UI, applied on the GL thread.
    @Volatile private var requestedMode: ShapeMode = ShapeMode.LINE
    @Volatile private var desiredTorch = false
    @Volatile private var desiredDepth = true
    @Volatile private var planesVisible = true
    private var appliedTorch = false
    private var appliedDepth = true
    @Volatile private var depthActive = false

    // GL-thread state.
    private var textureBoundTo: Session? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var frameCounter = 0L
    private val viewMatrix = FloatArray(16)
    private val projectionMatrix = FloatArray(16)

    // Measurement model (GL thread only).
    private var mode: ShapeMode = ShapeMode.LINE
    private val active = ArrayList<Anchor>()
    private val completed = ArrayList<CompletedShape>()
    private var lastPublished: ArMeasurementSnapshot? = null

    private val _frameState = MutableStateFlow(ArFrameState())
    val frameState: StateFlow<ArFrameState> = _frameState.asStateFlow()

    private val _measurement = MutableStateFlow(ArMeasurementSnapshot.empty(ShapeMode.LINE))
    val measurement: StateFlow<ArMeasurementSnapshot> = _measurement.asStateFlow()

    private val _events = MutableSharedFlow<ArEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<ArEvent> = _events.asSharedFlow()

    val isRunning: Boolean get() = isSessionResumed

    // ---------------------------------------------------------------------------------------
    // Public API (main thread)
    // ---------------------------------------------------------------------------------------

    fun setMode(newMode: ShapeMode) {
        requestedMode = newMode
    }

    fun placePoint() {
        commands.add(Command.Place)
    }

    fun undo() {
        commands.add(Command.Undo)
    }

    fun clearAll() {
        commands.add(Command.Clear)
    }

    fun finishShape() {
        commands.add(Command.Finish)
    }

    fun setTorchEnabled(enabled: Boolean) {
        desiredTorch = enabled
    }

    fun setDepthEnabled(enabled: Boolean) {
        desiredDepth = enabled
    }

    fun setPlanesVisible(visible: Boolean) {
        planesVisible = visible
    }

    /** Clears a reported failure so the UI can retry AR without reacting to the stale status. */
    fun acknowledgeFailure() {
        if (status is ArSessionStatus.Failed) publishStatus(ArSessionStatus.Idle)
    }

    /** Reads back the next rendered camera frame together with its overlay state. */
    suspend fun captureFrame(timeoutMs: Long = 2_000L): ArCapture? {
        if (!isSessionResumed) return null
        val request = CompletableDeferred<ArCapture?>()
        captureRequests.add(request)
        val result = withTimeoutOrNull(timeoutMs) { request.await() }
        if (result == null) captureRequests.remove(request)
        return result
    }

    /** Wires a freshly created [GLSurfaceView] to this renderer. Call once per view. */
    fun configureView(view: GLSurfaceView) {
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(2)
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        view.setRenderer(this)
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        view.setWillNotDraw(false)
    }

    fun onHostResume(view: GLSurfaceView) {
        glView = view
        hostResumed = true
        resumeAttempts = 0
        tryResume()
    }

    fun onHostPause(view: GLSurfaceView) {
        hostResumed = false
        mainHandler.removeCallbacks(retryResume)
        displayRotationHelper.onPause()
        view.onPause() // blocks until the GL thread has stopped drawing
        if (isSessionResumed) {
            isSessionResumed = false
            try {
                session?.pause()
            } catch (t: Throwable) {
                Log.w(TAG, "Session pause failed", t)
            }
        }
        if (status !is ArSessionStatus.Failed) publishStatus(ArSessionStatus.Paused)
        failPendingCaptures()
    }

    /** Releases the camera and all anchors. The controller can be resumed again afterwards. */
    fun close() {
        mainHandler.removeCallbacks(retryResume)
        glView?.onPause()
        glView = null
        hostResumed = false
        val s = session
        session = null
        arConfig = null
        if (s != null) {
            try {
                if (isSessionResumed) s.pause()
                s.close()
            } catch (t: Throwable) {
                Log.w(TAG, "Session close failed", t)
            }
        }
        isSessionResumed = false
        textureBoundTo = null
        active.clear()
        completed.clear()
        lastPublished = null
        _measurement.value = ArMeasurementSnapshot.empty(requestedMode)
        failPendingCaptures()
        publishStatus(ArSessionStatus.Idle)
    }

    // ---------------------------------------------------------------------------------------
    // Session lifecycle
    // ---------------------------------------------------------------------------------------

    private fun tryResume() {
        val view = glView ?: return
        if (!hostResumed || isSessionResumed) return
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            publishStatus(ArSessionStatus.Failed("Camera permission is required for AR", fatal = true))
            return
        }
        val s = session ?: createSession() ?: return
        publishStatus(ArSessionStatus.Starting)
        try {
            s.resume()
        } catch (e: CameraNotAvailableException) {
            // Usually the previous camera user (CameraX) is still releasing the device.
            if (++resumeAttempts <= MAX_RESUME_ATTEMPTS) {
                mainHandler.postDelayed(retryResume, RESUME_RETRY_MS)
            } else {
                publishStatus(
                    ArSessionStatus.Failed("The camera is busy — close other camera apps and retry", fatal = true)
                )
            }
            return
        } catch (t: Throwable) {
            Log.e(TAG, "Session resume failed", t)
            publishStatus(ArSessionStatus.Failed("ARCore couldn't start the camera", fatal = true))
            return
        }
        isSessionResumed = true
        displayRotationHelper.onResume()
        view.onResume()
    }

    private fun createSession(): Session? {
        val message = try {
            val s = Session(appContext)
            val cfg = Config(s)
            cfg.setPlaneFindingMode(Config.PlaneFindingMode.HORIZONTAL_AND_VERTICAL)
            cfg.setFocusMode(Config.FocusMode.AUTO)
            cfg.setUpdateMode(Config.UpdateMode.LATEST_CAMERA_IMAGE)
            cfg.setLightEstimationMode(Config.LightEstimationMode.DISABLED)
            cfg.setInstantPlacementMode(Config.InstantPlacementMode.DISABLED)
            val useDepth = desiredDepth && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
            cfg.setDepthMode(if (useDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
            cfg.setFlashMode(Config.FlashMode.OFF)
            s.configure(cfg)
            appliedDepth = desiredDepth
            appliedTorch = false
            depthActive = useDepth
            arConfig = cfg
            session = s
            return s
        } catch (e: UnavailableArcoreNotInstalledException) {
            "Google Play Services for AR is not installed"
        } catch (e: UnavailableApkTooOldException) {
            "Google Play Services for AR needs an update"
        } catch (e: UnavailableSdkTooOldException) {
            "This build of Calibra needs a newer ARCore SDK"
        } catch (e: UnavailableDeviceNotCompatibleException) {
            "This device doesn't support ARCore"
        } catch (t: Throwable) {
            Log.e(TAG, "Session creation failed", t)
            "ARCore failed to start"
        }
        publishStatus(ArSessionStatus.Failed(message, fatal = true))
        return null
    }

    private fun publishStatus(newStatus: ArSessionStatus) {
        status = newStatus
        _frameState.update { it.copy(status = newStatus) }
    }

    // ---------------------------------------------------------------------------------------
    // GLSurfaceView.Renderer (GL thread)
    // ---------------------------------------------------------------------------------------

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.07f, 0.08f, 0.1f, 1f)
        backgroundRenderer.createOnGlThread()
        textureBoundTo = null // new GL context => new texture name
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewWidth = width
        viewHeight = height
        displayRotationHelper.onSurfaceChanged(width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session
        if (s == null || !isSessionResumed || viewWidth <= 0 || viewHeight <= 0) {
            failPendingCaptures()
            return
        }
        try {
            renderFrame(s)
        } catch (t: Throwable) {
            Log.e(TAG, "AR frame failed", t)
            failPendingCaptures()
        }
    }

    private fun renderFrame(s: Session) {
        if (textureBoundTo !== s) {
            s.setCameraTextureName(backgroundRenderer.textureId)
            textureBoundTo = s
        }
        applyPendingConfig(s)
        displayRotationHelper.updateSessionIfNeeded(s)

        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            publishStatus(ArSessionStatus.Failed("The camera became unavailable", fatal = false))
            failPendingCaptures()
            return
        }
        backgroundRenderer.draw(frame)

        if (requestedMode != mode) applyMode(requestedMode)

        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        camera.getViewMatrix(viewMatrix, 0)
        camera.getProjectionMatrix(projectionMatrix, 0, ArProjector.NEAR_CLIP, ArProjector.FAR_CLIP)
        val projector = ArProjector(viewMatrix, projectionMatrix, viewWidth, viewHeight)
        val cameraPose = camera.displayOrientedPose
        val cameraPos = cameraPose.toVec3()

        val hit = if (tracking) centerHit(frame, camera) else null
        var target = if (tracking) resolveTarget(hit, projector, cameraPose) else null
        if (processCommands(s, target, tracking)) {
            target = if (tracking) resolveTarget(hit, projector, cameraPose) else null
        }

        val planes = buildPlaneVisuals(s, projector, cameraPos, tracking && planesVisible)
        val quality = when (camera.trackingState) {
            TrackingState.TRACKING -> ArTrackingQuality.TRACKING
            TrackingState.STOPPED -> ArTrackingQuality.STOPPED
            else -> if (camera.trackingFailureReason == TrackingFailureReason.NONE) {
                ArTrackingQuality.INITIALIZING
            } else {
                ArTrackingQuality.LIMITED
            }
        }
        val hint = when {
            camera.trackingState == TrackingState.PAUSED -> failureHint(camera.trackingFailureReason)
            camera.trackingState == TrackingState.STOPPED -> "AR tracking stopped — leave and reopen Measure"
            !planes.anyPlane && target == null -> "Move your phone slowly to scan nearby surfaces"
            target == null -> "Aim the reticle at a highlighted surface"
            else -> null
        }

        if (status !is ArSessionStatus.Running) {
            status = ArSessionStatus.Running
        }
        frameCounter++
        val state = ArFrameState(
            frameNumber = frameCounter,
            status = ArSessionStatus.Running,
            tracking = quality,
            hint = hint,
            surfacesFound = planes.anyPlane,
            depthActive = depthActive,
            viewWidth = viewWidth,
            viewHeight = viewHeight,
            planeDots = planes.dots,
            planeOutlines = planes.outlines,
            reticle = if (tracking) buildReticle(target, projector, cameraPos) else null,
            shapes = if (tracking) buildShapes(projector, target) else emptyList()
        )
        _frameState.value = state
        publishMeasurement(target)
        serviceCaptures(state)
    }

    private fun applyPendingConfig(s: Session) {
        val cfg = arConfig ?: return
        val wantDepth = desiredDepth
        val wantTorch = desiredTorch
        if (wantDepth == appliedDepth && wantTorch == appliedTorch) return
        val useDepth = wantDepth && s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
        cfg.setDepthMode(if (useDepth) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED)
        cfg.setFlashMode(if (wantTorch) Config.FlashMode.TORCH else Config.FlashMode.OFF)
        try {
            s.configure(cfg)
        } catch (t: Throwable) {
            Log.w(TAG, "AR reconfigure failed (torch=$wantTorch, depth=$useDepth)", t)
            cfg.setFlashMode(Config.FlashMode.OFF)
            runCatching { s.configure(cfg) }
            if (wantTorch) emit(ArEvent.Message("The flashlight isn't available in AR on this device"))
        }
        depthActive = useDepth
        appliedDepth = wantDepth
        appliedTorch = wantTorch
    }

    // ---------------------------------------------------------------------------------------
    // Targeting
    // ---------------------------------------------------------------------------------------

    private class SurfaceHit(
        val hit: HitResult,
        val pose: Pose,
        val position: Vec3,
        val kind: ArSurfaceKind
    )

    private class Target(
        val position: Vec3,
        val kind: ArSurfaceKind,
        /** Hit to anchor on directly (keeps the anchor attached to the refining plane). */
        val hit: HitResult?,
        /** Existing node we snapped onto. */
        val snapAnchor: Anchor?,
        /** Pose whose X/Z axes span the surface, for the reticle ring. */
        val ringPose: Pose?,
        val axisLock: String?,
        val closesPolygon: Boolean
    )

    private fun centerHit(frame: Frame, camera: Camera): SurfaceHit? {
        val hits = try {
            frame.hitTest(viewWidth / 2f, viewHeight / 2f)
        } catch (t: Throwable) {
            return null
        }
        for (hit in hits) {
            val pose = hit.hitPose
            val kind = when (val trackable = hit.trackable) {
                is Plane -> if (trackable.isPoseInPolygon(pose) &&
                    distanceToPlane(pose, camera.pose) > 0f
                ) {
                    if (trackable.type == Plane.Type.VERTICAL) ArSurfaceKind.VERTICAL else ArSurfaceKind.HORIZONTAL
                } else null
                is DepthPoint -> ArSurfaceKind.DEPTH
                is Point -> if (trackable.orientationMode == Point.OrientationMode.ESTIMATED_SURFACE_NORMAL) {
                    ArSurfaceKind.FEATURE
                } else null
                else -> null
            } ?: continue
            return SurfaceHit(hit, pose, pose.toVec3(), kind)
        }
        return null
    }

    private fun resolveTarget(hit: SurfaceHit?, projector: ArProjector, cameraPose: Pose): Target? {
        // 1. Box height: intersect the camera's view ray with the base's extrusion axis.
        if (mode == ShapeMode.SNAP_3D && active.size == 3) {
            val placed = active.map { it.pose.toVec3() }
            val base = SpatialMeasurementEngine.rectangleCorners(placed[0], placed[1], placed[2])
            val axis = SpatialMeasurementEngine.baseNormal(base)
            val forward = -Vec3.of(cameraPose.zAxis)
            val t = SpatialMeasurementEngine.closestAxisParameter(base[2], axis, cameraPose.toVec3(), forward)
                ?: return null
            val height = t.coerceIn(-MAX_HEIGHT_M, MAX_HEIGHT_M)
            return Target(base[2] + axis * height, ArSurfaceKind.HEIGHT, null, null, null, null, false)
        }

        // 2. Snap onto an existing node near the screen center.
        val center = Offset(viewWidth / 2f, viewHeight / 2f)
        val snapRadius = max(SNAP_RADIUS_MIN_PX, viewWidth * 0.055f)
        var best: Anchor? = null
        var bestDistance = snapRadius
        val lastActive = active.lastOrNull()
        fun consider(anchor: Anchor) {
            if (anchor === lastActive || anchor.trackingState == TrackingState.STOPPED) return
            val screen = projector.project(anchor.pose.toVec3()) ?: return
            val d = (screen - center).getDistance()
            if (d < bestDistance) {
                bestDistance = d
                best = anchor
            }
        }
        active.forEach(::consider)
        completed.forEach { shape -> shape.anchors.forEach(::consider) }
        best?.let { node ->
            val closes = mode == ShapeMode.POLYGON && active.size >= 3 && node === active.first()
            return Target(node.pose.toVec3(), ArSurfaceKind.NODE, null, node, node.pose, null, closes)
        }

        // 3. Plain surface hit, optionally axis-locked for lines (level / plumb).
        val surface = hit ?: return null
        var position = surface.position
        var lock: String? = null
        if (mode == ShapeMode.LINE && active.size == 1) {
            val start = active[0].pose.toVec3()
            val delta = position - start
            val length = delta.length
            if (length > MIN_AXIS_LOCK_LENGTH_M) {
                val elevation = abs(delta.y) / length // sine of the angle above the horizon
                if (elevation > cos(AXIS_LOCK_RAD)) {
                    position = Vec3(start.x, position.y, start.z)
                    lock = "PLUMB"
                } else if (elevation < sin(AXIS_LOCK_RAD)) {
                    position = Vec3(position.x, start.y, position.z)
                    lock = "LEVEL"
                }
            }
        }
        return Target(
            position = position,
            kind = surface.kind,
            hit = if (lock == null) surface.hit else null,
            snapAnchor = null,
            ringPose = surface.pose,
            axisLock = lock,
            closesPolygon = false
        )
    }

    // ---------------------------------------------------------------------------------------
    // Commands
    // ---------------------------------------------------------------------------------------

    private sealed interface Command {
        data object Place : Command
        data object Undo : Command
        data object Clear : Command
        data object Finish : Command
    }

    private class CompletedShape(val mode: ShapeMode, val anchors: List<Anchor>)

    /** Applies queued commands; returns true if the model changed. */
    private fun processCommands(s: Session, target: Target?, tracking: Boolean): Boolean {
        var changed = false
        while (true) {
            val command = commands.poll() ?: break
            val didChange = when (command) {
                Command.Place -> place(s, target, tracking)
                Command.Undo -> undoLast()
                Command.Clear -> clearModel()
                Command.Finish -> finishPolygon()
            }
            changed = changed || didChange
        }
        return changed
    }

    private fun place(s: Session, target: Target?, tracking: Boolean): Boolean {
        if (!tracking) {
            emit(ArEvent.Message("Hold steady — AR tracking is recovering"))
            return false
        }
        if (target == null) {
            val heightStep = mode == ShapeMode.SNAP_3D && active.size == 3
            emit(
                ArEvent.Message(
                    if (heightStep) "Point the phone at the top edge to set the height"
                    else "Aim the reticle at a detected surface first"
                )
            )
            return false
        }
        if (target.closesPolygon) {
            completeActive()
            return true
        }
        val anchor = try {
            when {
                target.snapAnchor != null -> s.createAnchor(target.snapAnchor.pose)
                target.hit != null -> target.hit.createAnchor()
                else -> s.createAnchor(
                    Pose.makeTranslation(
                        target.position.x.toFloat(),
                        target.position.y.toFloat(),
                        target.position.z.toFloat()
                    )
                )
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Anchor creation failed", t)
            emit(ArEvent.Message("Couldn't place a point there — try again"))
            return false
        }
        active.add(anchor)
        emit(ArEvent.PointPlaced(active.size))
        val required = SpatialMeasurementEngine.requiredPoints(mode)
        val full = required != null && active.size >= required
        val polygonCap = mode == ShapeMode.POLYGON && active.size >= SpatialMeasurementEngine.MAX_POLYGON_POINTS
        if (full || polygonCap) completeActive()
        return true
    }

    private fun completeActive() {
        if (active.isEmpty()) return
        completed.add(CompletedShape(mode, ArrayList(active)))
        active.clear()
        while (completed.size > MAX_COMPLETED_SHAPES) {
            completed.removeAt(0).anchors.forEach { it.detach() }
        }
        emit(ArEvent.ShapeCompleted(mode))
    }

    private fun undoLast(): Boolean {
        if (active.isNotEmpty()) {
            active.removeAt(active.lastIndex).detach()
            return true
        }
        if (completed.isEmpty()) return false
        val last = completed.removeAt(completed.lastIndex)
        if (last.mode == mode && last.anchors.size > 1) {
            // Step back into the finished shape instead of deleting it outright.
            active.addAll(last.anchors.dropLast(1))
            last.anchors.last().detach()
        } else {
            last.anchors.forEach { it.detach() }
        }
        return true
    }

    private fun finishPolygon(): Boolean {
        if (mode == ShapeMode.POLYGON && active.size >= 3) {
            completeActive()
            return true
        }
        if (active.isNotEmpty()) emit(ArEvent.Message("Place at least 3 points to close the area"))
        return false
    }

    private fun clearModel(): Boolean {
        active.forEach { it.detach() }
        active.clear()
        completed.forEach { shape -> shape.anchors.forEach { it.detach() } }
        completed.clear()
        return true
    }

    private fun applyMode(newMode: ShapeMode) {
        active.forEach { it.detach() }
        active.clear()
        mode = newMode
    }

    private fun emit(event: ArEvent) {
        _events.tryEmit(event)
    }

    // ---------------------------------------------------------------------------------------
    // Scene building
    // ---------------------------------------------------------------------------------------

    private class PlaneVisuals(
        val anyPlane: Boolean,
        val dots: List<Offset>,
        val outlines: List<List<Offset>>
    )

    private fun buildPlaneVisuals(
        s: Session,
        projector: ArProjector,
        cameraPos: Vec3,
        draw: Boolean
    ): PlaneVisuals {
        val dots = ArrayList<Offset>()
        val outlines = ArrayList<List<Offset>>()
        var anyPlane = false
        var budget = MAX_PLANE_DOTS
        val local = FloatArray(3)
        val world = FloatArray(3)

        for (plane in s.getAllTrackables(Plane::class.java)) {
            if (plane.trackingState != TrackingState.TRACKING || plane.subsumedBy != null) continue
            anyPlane = true
            if (!draw) continue

            val polygon = plane.polygon
            polygon.rewind()
            val count = polygon.remaining() / 2
            if (count < 3) continue
            val xs = FloatArray(count)
            val zs = FloatArray(count)
            for (i in 0 until count) {
                xs[i] = polygon.get()
                zs[i] = polygon.get()
            }
            val pose = plane.centerPose
            fun toWorld(x: Float, z: Float): Vec3 {
                local[0] = x
                local[1] = 0f
                local[2] = z
                pose.transformPoint(local, 0, world, 0)
                return Vec3.of(world)
            }

            val outline = ArrayList<Offset>(count)
            for (i in 0 until count) {
                val p = projector.project(toWorld(xs[i], zs[i])) ?: break
                outline.add(p)
            }
            if (outline.size == count) outlines.add(outline)

            if (budget <= 0) continue
            val extentX = plane.extentX
            val extentZ = plane.extentZ
            var spacing = PLANE_DOT_SPACING_M
            while ((extentX / spacing) * (extentZ / spacing) > budget && spacing < 1f) spacing *= 1.4f
            var x = -extentX / 2f + spacing / 2f
            while (x < extentX / 2f && budget > 0) {
                var z = -extentZ / 2f + spacing / 2f
                while (z < extentZ / 2f && budget > 0) {
                    if (pointInPolygon(x, z, xs, zs)) {
                        val p = toWorld(x, z)
                        if (p.distanceTo(cameraPos) < MAX_DOT_DISTANCE_M) {
                            val screen = projector.project(p)
                            if (screen != null && projector.isOnScreen(screen)) {
                                dots.add(screen)
                                budget--
                            }
                        }
                    }
                    z += spacing
                }
                x += spacing
            }
        }
        return PlaneVisuals(anyPlane, dots, outlines)
    }

    private fun buildReticle(target: Target?, projector: ArProjector, cameraPos: Vec3): ArReticle {
        val screenCenter = Offset(viewWidth / 2f, viewHeight / 2f)
        if (target == null) return ArReticle(screenCenter, emptyList(), null, null, null)
        val center = projector.project(target.position) ?: screenCenter
        val distance = target.position.distanceTo(cameraPos)
        val ring = target.ringPose?.let { pose ->
            val ax = Vec3.of(pose.xAxis)
            val az = Vec3.of(pose.zAxis)
            val radius = (distance * 0.035).coerceIn(0.015, 0.12)
            val points = (0 until RETICLE_SEGMENTS).mapNotNull { k ->
                val theta = 2.0 * PI * k / RETICLE_SEGMENTS
                projector.project(target.position + (ax * cos(theta) + az * sin(theta)) * radius)
            }
            if (points.size == RETICLE_SEGMENTS) points else null
        } ?: emptyList()
        return ArReticle(center, ring, target.kind, distance, target.axisLock)
    }

    private fun buildShapes(projector: ArProjector, target: Target?): List<ArRenderShape> {
        val shapes = ArrayList<ArRenderShape>(completed.size + 1)
        completed.forEach { shape ->
            shapes.add(
                buildShape(
                    mode = shape.mode,
                    placed = shape.anchors.map { it.pose.toVec3() },
                    firstNormal = shape.anchors.firstOrNull()?.pose?.let { Vec3.of(it.yAxis) },
                    live = null,
                    isActive = false,
                    closesPolygon = false,
                    projector = projector
                )
            )
        }
        if (active.isNotEmpty()) {
            shapes.add(
                buildShape(
                    mode = mode,
                    placed = active.map { it.pose.toVec3() },
                    firstNormal = Vec3.of(active[0].pose.yAxis),
                    live = target?.position,
                    isActive = true,
                    closesPolygon = target?.closesPolygon == true,
                    projector = projector
                )
            )
        }
        return shapes
    }

    private fun buildShape(
        mode: ShapeMode,
        placed: List<Vec3>,
        firstNormal: Vec3?,
        live: Vec3?,
        isActive: Boolean,
        closesPolygon: Boolean,
        projector: ArProjector
    ): ArRenderShape {
        val liveIsLast = live != null && !closesPolygon
        val pts = if (live != null && liveIsLast) placed + live else placed
        val edges = ArrayList<ArRenderEdge>()
        var fillWorld: List<Vec3> = emptyList()
        var labelAt: Vec3? = null
        var labelKind = ArCenterLabel.Kind.AREA
        var labelValue = 0.0

        fun edge(a: Vec3, b: Vec3, labeled: Boolean = true, dashed: Boolean = false) {
            projector.projectSegment(a, b)?.let { (sa, sb) ->
                edges.add(ArRenderEdge(sa, sb, if (labeled) a.distanceTo(b) else null, dashed))
            }
        }

        fun rectangle(corners: List<Vec3>) {
            edge(corners[0], corners[1])
            edge(corners[1], corners[2])
            edge(corners[2], corners[3], labeled = false)
            edge(corners[3], corners[0], labeled = false)
            fillWorld = corners
            labelAt = Vec3.centroid(corners)
            labelKind = ArCenterLabel.Kind.AREA
            labelValue = corners[0].distanceTo(corners[1]) * corners[1].distanceTo(corners[2])
        }

        when (mode) {
            ShapeMode.LINE -> if (pts.size >= 2) edge(pts[0], pts[1], dashed = liveIsLast)

            ShapeMode.ANGLE -> {
                for (i in 0 until pts.size - 1) edge(pts[i], pts[i + 1], dashed = liveIsLast && i == pts.size - 2)
                if (pts.size >= 3) {
                    val vertex = pts[1]
                    val a = pts[0] - vertex
                    val b = pts[2] - vertex
                    val la = a.length
                    val lb = b.length
                    if (la > 1e-4 && lb > 1e-4) {
                        val na = a * (1.0 / la)
                        val nb = b * (1.0 / lb)
                        val theta = acos((na dot nb).coerceIn(-1.0, 1.0))
                        val radius = 0.3 * min(la, lb)
                        if (sin(theta) > 1e-3) {
                            val arc = (0..ARC_SEGMENTS).map { k ->
                                val t = k.toDouble() / ARC_SEGMENTS
                                val dir = (na * sin((1 - t) * theta) + nb * sin(t * theta)) * (1.0 / sin(theta))
                                vertex + dir * radius
                            }
                            for (k in 0 until ARC_SEGMENTS) edge(arc[k], arc[k + 1], labeled = false)
                        }
                        labelAt = vertex + (na + nb).normalized() * (radius * 1.7)
                        labelKind = ArCenterLabel.Kind.ANGLE
                        labelValue = Math.toDegrees(theta)
                    }
                }
            }

            ShapeMode.CIRCLE -> if (pts.size >= 2) {
                val center = pts[0]
                val rim = pts[1]
                val n = (firstNormal ?: Vec3.UP).normalized().let { if (it == Vec3.ZERO) Vec3.UP else it }
                var u = rim - center
                u -= n * (u dot n)
                val radius = center.distanceTo(rim)
                if (u.length > 1e-6 && radius > 1e-4) {
                    u = u.normalized()
                    val w = n cross u
                    val ring = (0..RING_SEGMENTS).map { k ->
                        val theta = 2.0 * PI * k / RING_SEGMENTS
                        center + (u * cos(theta) + w * sin(theta)) * radius
                    }
                    for (k in 0 until RING_SEGMENTS) edge(ring[k], ring[k + 1], labeled = false)
                    edge(center - u * radius, center + u * radius, dashed = liveIsLast)
                    fillWorld = ring.dropLast(1)
                    labelAt = center + w * (radius * 0.45)
                    labelKind = ArCenterLabel.Kind.AREA
                    labelValue = PI * radius * radius
                }
            }

            ShapeMode.RECTANGLE -> when {
                pts.size == 2 -> edge(pts[0], pts[1], dashed = liveIsLast)
                pts.size >= 3 -> rectangle(SpatialMeasurementEngine.rectangleCorners(pts[0], pts[1], pts[2]))
            }

            ShapeMode.POLYGON -> {
                for (i in 0 until pts.size - 1) edge(pts[i], pts[i + 1], dashed = liveIsLast && i == pts.size - 2)
                if (pts.size >= 3) {
                    val closed = !isActive || closesPolygon
                    edge(pts.last(), pts.first(), labeled = closed, dashed = !closed)
                    fillWorld = pts
                    labelAt = Vec3.centroid(pts)
                    labelKind = ArCenterLabel.Kind.AREA
                    labelValue = SpatialMeasurementEngine.polygonArea(pts)
                }
            }

            ShapeMode.SNAP_3D -> when {
                pts.size == 2 -> edge(pts[0], pts[1], dashed = liveIsLast)
                pts.size == 3 -> rectangle(SpatialMeasurementEngine.rectangleCorners(pts[0], pts[1], pts[2]))
                pts.size >= 4 -> {
                    val c = SpatialMeasurementEngine.boxCorners(pts)
                    edge(c[0], c[1])
                    edge(c[1], c[2])
                    edge(c[2], c[3], labeled = false)
                    edge(c[3], c[0], labeled = false)
                    for (i in 0..3) edge(c[4 + i], c[4 + (i + 1) % 4], labeled = false)
                    for (i in 0..3) edge(c[i], c[4 + i], labeled = i == 2, dashed = liveIsLast && i != 2)
                    fillWorld = c.subList(4, 8)
                    labelAt = Vec3.centroid(c)
                    labelKind = ArCenterLabel.Kind.VOLUME
                    val w = c[0].distanceTo(c[1])
                    val d = c[1].distanceTo(c[2])
                    val h = c[2].distanceTo(c[6])
                    labelValue = w * d * h
                }
            }
        }

        val fill = if (fillWorld.size >= 3) {
            val projected = fillWorld.mapNotNull { projector.project(it) }
            if (projected.size == fillWorld.size) projected else emptyList()
        } else emptyList()
        val centerLabel = labelAt?.let { pos ->
            projector.project(pos)?.let { ArCenterLabel(it, labelKind, labelValue) }
        }
        return ArRenderShape(
            mode = mode,
            isActive = isActive,
            nodes = placed.mapNotNull { projector.project(it) },
            liveNode = if (live != null && liveIsLast) projector.project(live) else null,
            edges = edges,
            fill = fill,
            centerLabel = centerLabel
        )
    }

    private fun publishMeasurement(target: Target?) {
        val snapshot = if (active.isNotEmpty()) {
            val placed = active.map { it.pose.toVec3() }
            val live = target?.takeIf { !it.closesPolygon }?.position
            ArMeasurementSnapshot(
                mode = mode,
                points = if (live != null) placed + live else placed,
                placedCount = placed.size,
                hasLivePoint = live != null,
                isComplete = false,
                completedCount = completed.size
            )
        } else {
            val last = completed.lastOrNull { it.mode == mode }
            if (last != null) {
                ArMeasurementSnapshot(
                    mode = mode,
                    points = last.anchors.map { it.pose.toVec3() },
                    placedCount = last.anchors.size,
                    hasLivePoint = false,
                    isComplete = true,
                    completedCount = completed.size
                )
            } else {
                ArMeasurementSnapshot.empty(mode).copy(completedCount = completed.size)
            }
        }
        val previous = lastPublished
        val significant = previous == null ||
            previous.mode != snapshot.mode ||
            previous.placedCount != snapshot.placedCount ||
            previous.hasLivePoint != snapshot.hasLivePoint ||
            previous.isComplete != snapshot.isComplete ||
            previous.completedCount != snapshot.completedCount ||
            SpatialMeasurementEngine.maxDeviation(previous.points, snapshot.points) > PUBLISH_EPSILON_M
        if (significant) {
            lastPublished = snapshot
            _measurement.value = snapshot
        }
    }

    // ---------------------------------------------------------------------------------------
    // Capture
    // ---------------------------------------------------------------------------------------

    private fun serviceCaptures(state: ArFrameState) {
        if (captureRequests.isEmpty()) return
        var bitmap = readFramebuffer(viewWidth, viewHeight)
        while (true) {
            val request = captureRequests.poll() ?: break
            request.complete(bitmap?.let { ArCapture(it, state) })
            bitmap = null // only the first requester receives the frame
        }
    }

    private fun failPendingCaptures() {
        while (true) {
            val request = captureRequests.poll() ?: break
            request.complete(null)
        }
    }

    private fun readFramebuffer(width: Int, height: Int): Bitmap? = try {
        val buffer = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val raw = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        raw.copyPixelsFromBuffer(buffer)
        // GL's origin is bottom-left; flip into a mutable, top-left oriented bitmap.
        val flipped = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(flipped).apply {
            scale(1f, -1f, width / 2f, height / 2f)
            drawBitmap(raw, 0f, 0f, null)
        }
        raw.recycle()
        flipped
    } catch (t: Throwable) {
        Log.w(TAG, "Frame readback failed", t)
        null
    }

    private companion object {
        const val TAG = "CalibraAR"
        const val MAX_RESUME_ATTEMPTS = 8
        const val RESUME_RETRY_MS = 300L
        const val MAX_COMPLETED_SHAPES = 12
        const val SNAP_RADIUS_MIN_PX = 44f
        const val AXIS_LOCK_RAD = 4.0 * PI / 180.0
        const val MIN_AXIS_LOCK_LENGTH_M = 0.03
        const val MAX_HEIGHT_M = 8.0
        const val PLANE_DOT_SPACING_M = 0.14f
        const val MAX_PLANE_DOTS = 1_400
        const val MAX_DOT_DISTANCE_M = 6.0
        const val RETICLE_SEGMENTS = 28
        const val RING_SEGMENTS = 48
        const val ARC_SEGMENTS = 16
        const val PUBLISH_EPSILON_M = 0.002

        fun Pose.toVec3() = Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble())

        /** Signed distance of the camera from the plane along its normal (>0 = in front). */
        fun distanceToPlane(planePose: Pose, cameraPose: Pose): Float {
            val normal = planePose.yAxis
            return (cameraPose.tx() - planePose.tx()) * normal[0] +
                (cameraPose.ty() - planePose.ty()) * normal[1] +
                (cameraPose.tz() - planePose.tz()) * normal[2]
        }

        fun pointInPolygon(x: Float, z: Float, xs: FloatArray, zs: FloatArray): Boolean {
            var inside = false
            var j = xs.size - 1
            for (i in xs.indices) {
                if ((zs[i] > z) != (zs[j] > z) &&
                    x < (xs[j] - xs[i]) * (z - zs[i]) / (zs[j] - zs[i]) + xs[i]
                ) {
                    inside = !inside
                }
                j = i
            }
            return inside
        }

        fun failureHint(reason: TrackingFailureReason): String = when (reason) {
            TrackingFailureReason.NONE -> "Move your phone slowly to start tracking"
            TrackingFailureReason.BAD_STATE -> "AR hit a problem — leave and reopen Measure"
            TrackingFailureReason.INSUFFICIENT_LIGHT -> "Too dark — turn on the flashlight or add light"
            TrackingFailureReason.EXCESSIVE_MOTION -> "Moving too fast — slow down"
            TrackingFailureReason.INSUFFICIENT_FEATURES -> "Aim at a surface with more texture or detail"
            TrackingFailureReason.CAMERA_UNAVAILABLE -> "The camera is in use by another app"
        }
    }
}
