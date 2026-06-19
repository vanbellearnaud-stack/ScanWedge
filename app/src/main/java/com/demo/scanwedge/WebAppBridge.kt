package com.demo.scanwedge

import android.util.Log
import android.webkit.JavascriptInterface

/**
 * Pont JS → natif. Exposé à la page sous le nom global `Android`.
 * Étape 1 : un simple log pour vérifier le sens JS → natif.
 */
class WebAppBridge {
    @JavascriptInterface
    fun log(msg: String) {
        Log.d("ScanWedge/web", msg)
    }
}
