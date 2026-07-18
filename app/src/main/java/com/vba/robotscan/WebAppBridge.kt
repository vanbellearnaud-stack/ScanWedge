package com.vba.robotscan

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Pont JS → natif. Exposé à la page sous le nom global `Android`.
 * Simple log pour tracer le sens JS → natif (adb logcat -s RobotScan/web).
 */
class WebAppBridge {
    @JavascriptInterface
    fun log(msg: String) {
        Log.d("RobotScan/web", msg)
    }
}
