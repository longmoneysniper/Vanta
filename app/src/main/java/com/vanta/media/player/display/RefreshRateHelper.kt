package com.vanta.media.player.display

import android.app.Activity
import android.os.Build
import android.view.WindowManager
import kotlin.math.abs

object RefreshRateHelper {
    fun lockCinema24Hz(activity: Activity, enable: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        val window = activity.window ?: return
        val display = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display
        } else {
            @Suppress("DEPRECATION")
            window.windowManager.defaultDisplay
        } ?: return

        val params: WindowManager.LayoutParams = window.attributes
        if (!enable) {
            params.preferredDisplayModeId = 0
            window.attributes = params
            return
        }

        val targetFps = 23.976f
        val bestMode = display.supportedModes.minByOrNull { abs(it.refreshRate - targetFps) }
        if (bestMode != null && abs(bestMode.refreshRate - 24f) < 1.5f) {
            params.preferredDisplayModeId = bestMode.modeId
            window.attributes = params
        }
    }
}
