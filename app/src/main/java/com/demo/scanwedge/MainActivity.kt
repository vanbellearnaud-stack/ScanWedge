package com.demo.scanwedge

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Wrapper minimal : une WebView plein écran qui héberge l'appli web embarquée
 * (assets/index.html) et lui pousse chaque scan reçu de DataWedge via un Intent.
 *
 * Étape 1 (dérisquage) : on prouve juste que la chaîne
 *   DataWedge → Intent (broadcast) → BroadcastReceiver → WebView/JS  s'allume.
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    // Doit correspondre EXACTEMENT à l'action configurée dans le profil DataWedge
    // (Sortie Intent, mode Broadcast, Intent action = com.demo.scanwedge.SCAN).
    private val scanAction = "com.demo.scanwedge.SCAN"

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != scanAction) return
            val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
            val type = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
            pushScanToWeb(data, type)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true   // requis pour IndexedDB / localStorage (étape 2)
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppBridge(), "Android")

        // Option B : la WebView charge l'appli web déployée. L'offline est assuré
        // par le service worker de la PWA (une fois la page chargée une 1re fois en
        // ligne). Pour un offline garanti dès le démarrage à froid, on embarquerait
        // l'appli dans assets/ (file:///android_asset/...).
        webView.loadUrl("https://www.pep35.cloud/mobile.html")
    }

    /** Transmet un scan au JS : window.onScan({ data, type }). */
    private fun pushScanToWeb(data: String, type: String) {
        val payload = JSONObject().put("data", data).put("type", type).toString()
        runOnUiThread {
            webView.evaluateJavascript("window.onScan && window.onScan($payload);", null)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter().apply {
            addAction(scanAction)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        // Le broadcast vient d'une autre appli (DataWedge) → receiver EXPORTED sur Android 13+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(scanReceiver, filter)
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unregisterReceiver(scanReceiver)
        } catch (_: IllegalArgumentException) {
            // déjà désenregistré
        }
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
