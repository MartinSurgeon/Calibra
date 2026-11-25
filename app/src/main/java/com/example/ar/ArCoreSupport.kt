package com.example.ar

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.util.Log
import com.google.ar.core.ArCoreApk
import kotlinx.coroutines.delay

/** Coarse ARCore availability, collapsed from [ArCoreApk.Availability] for the UI. */
enum class ArAvailability(val label: String) {
    CHECKING("Checking ARCore support…"),
    SUPPORTED("ARCore ready — world-scale tracking"),
    NEEDS_INSTALL("Google Play Services for AR needs to be installed or updated"),
    UNSUPPORTED("This device does not support ARCore");

    val isUsable: Boolean get() = this == SUPPORTED
}

object ArCoreSupport {

    private const val TAG = "CalibraAR"

    /**
     * Queries ARCore support, polling while the answer is transient (the first query after
     * process start can take a moment while the Play Store is consulted).
     */
    suspend fun checkAvailability(context: Context): ArAvailability {
        repeat(30) {
            val availability = try {
                ArCoreApk.getInstance().checkAvailability(context.applicationContext)
            } catch (t: Throwable) {
                Log.w(TAG, "ARCore availability check failed", t)
                return ArAvailability.UNSUPPORTED
            }
            if (!availability.isTransient) return map(availability)
            delay(200L)
        }
        return ArAvailability.UNSUPPORTED
    }

    private fun map(availability: ArCoreApk.Availability): ArAvailability = when (availability) {
        ArCoreApk.Availability.SUPPORTED_INSTALLED -> ArAvailability.SUPPORTED
        ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
        ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED -> ArAvailability.NEEDS_INSTALL
        else -> ArAvailability.UNSUPPORTED
    }

    /**
     * Starts (or completes) the Play Store install flow for Google Play Services for AR.
     * Returns true when ARCore is installed and ready, false when an install was requested
     * (the activity will pause and resume) or the user declined.
     */
    fun requestInstall(activity: Activity, userRequested: Boolean): Boolean = try {
        ArCoreApk.getInstance().requestInstall(activity, userRequested) ==
            ArCoreApk.InstallStatus.INSTALLED
    } catch (t: Throwable) {
        Log.w(TAG, "ARCore install request failed", t)
        false
    }
}

/** Walks the [ContextWrapper] chain to find the hosting [Activity], if any. */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
