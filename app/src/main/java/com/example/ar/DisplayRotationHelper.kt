package com.example.ar

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import com.google.ar.core.Session

/**
 * Keeps ARCore's display geometry (rotation + viewport size) in sync with the device so the
 * camera image and projection matrices line up with the screen.
 */
internal class DisplayRotationHelper(context: Context) : DisplayManager.DisplayListener {

    private val displayManager =
        context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager

    @Volatile private var viewportChanged = false
    @Volatile private var viewportWidth = 0
    @Volatile private var viewportHeight = 0

    fun onResume() {
        displayManager.registerDisplayListener(this, null)
        viewportChanged = true
    }

    fun onPause() {
        displayManager.unregisterDisplayListener(this)
    }

    /** Called from the GL thread when the surface size changes. */
    fun onSurfaceChanged(width: Int, height: Int) {
        viewportWidth = width
        viewportHeight = height
        viewportChanged = true
    }

    /** Called from the GL thread before [Session.update]. */
    fun updateSessionIfNeeded(session: Session) {
        if (!viewportChanged || viewportWidth <= 0 || viewportHeight <= 0) return
        val rotation = displayManager.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: 0
        session.setDisplayGeometry(rotation, viewportWidth, viewportHeight)
        viewportChanged = false
    }

    override fun onDisplayAdded(displayId: Int) = Unit
    override fun onDisplayRemoved(displayId: Int) = Unit
    override fun onDisplayChanged(displayId: Int) {
        viewportChanged = true
    }
}
