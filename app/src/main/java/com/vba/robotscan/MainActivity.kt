package com.vba.robotscan

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.webkit.URLUtil
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject

/**
 * RobotScan — wrapper WebView générique pour terminaux Zebra.
 *
 * Une WebView plein écran héberge n'importe quel site (URL saisie/mémorisée) et
 * reçoit les scans de DataWedge via un Intent broadcast, qu'elle transmet au JS
 * par window.onScan({ data, type }).
 *
 * Robustesse : URL validée, page de repli en cas d'échec réseau, et file d'attente
 * des scans tant que la page n'est pas chargée (aucun scan perdu au démarrage).
 */
class MainActivity : Activity() {

    private lateinit var webView: WebView

    // Doit correspondre EXACTEMENT à l'action configurée dans le profil DataWedge
    // (Sortie Intent, mode Broadcast, Intent action = com.vba.robotscan.SCAN).
    private val scanAction = "com.vba.robotscan.SCAN"

    // URL chargée par la WebView, mémorisée et modifiable à chaud (appui long).
    private val prefs by lazy { getSharedPreferences("robotscan", MODE_PRIVATE) }
    private fun configuredUrl(): String? = prefs.getString("start_url", null)?.takeIf { it.isNotBlank() }

    // État de chargement + file d'attente des scans reçus avant que la page soit prête.
    private var pageReady = false
    private var onErrorPage = false
    private val pendingScans = ArrayList<String>()

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != scanAction) return
            val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
            val type = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
            pushScanToWeb(data, type)
        }
    }

    private val webClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            if (isHttp(url)) {
                pageReady = false
                onErrorPage = false
            }
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            // Les pages internes (accueil/erreur) utilisent un schéma "about:" et
            // ne rendent pas la page "prête" : les scans restent en file.
            if (!onErrorPage && isHttp(url)) {
                pageReady = true
                flushPendingScans()
            }
        }

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            if (request?.isForMainFrame == true) {
                onErrorPage = true
                pageReady = false
                showErrorPage()
            }
        }
    }

    private fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true   // IndexedDB / localStorage
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
        }
        webView.webViewClient = webClient
        webView.addJavascriptInterface(WebAppBridge(), "Android")

        // Appui long n'importe où → changer l'URL du site (mémorisée, sans rebuild).
        webView.setOnLongClickListener {
            showUrlDialog()
            true
        }

        val url = configuredUrl()
        if (url != null) {
            webView.loadUrl(url)
        } else {
            // Première ouverture : aucun site configuré → accueil + saisie directe.
            webView.loadDataWithBaseURL("about:welcome", welcomeHtml(), "text/html", "UTF-8", null)
            showUrlDialog()
        }
    }

    /** Boîte de dialogue pour saisir/changer l'URL (validée puis mémorisée). */
    private fun showUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://mon-site.fr/page.html"
            setText(configuredUrl() ?: "")
            setSelection(text.length)
        }
        AlertDialog.Builder(this)
            .setTitle("URL du site")
            .setView(input)
            .setPositiveButton("Charger") { _, _ ->
                var url = input.text.toString().trim()
                if (url.isEmpty()) return@setPositiveButton
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                if (!URLUtil.isValidUrl(url)) {
                    Toast.makeText(this, "URL invalide", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                prefs.edit().putString("start_url", url).apply()
                webView.loadUrl(url)
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Transmet un scan au JS, ou le met en file si la page n'est pas encore prête. */
    private fun pushScanToWeb(data: String, type: String) {
        val payload = JSONObject().put("data", data).put("type", type).toString()
        runOnUiThread {
            if (pageReady) {
                webView.evaluateJavascript("window.onScan && window.onScan($payload);", null)
            } else {
                pendingScans.add(payload)
            }
        }
    }

    private fun flushPendingScans() {
        if (pendingScans.isEmpty()) return
        val copy = ArrayList(pendingScans)
        pendingScans.clear()
        for (p in copy) {
            webView.evaluateJavascript("window.onScan && window.onScan($p);", null)
        }
    }

    private fun showErrorPage() {
        webView.loadDataWithBaseURL("about:error", errorHtml(configuredUrl() ?: ""), "text/html", "UTF-8", null)
    }

    private fun pageShell(body: String): String =
        """
        <!DOCTYPE html><html lang="fr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html,body{height:100%;margin:0}
          body{background:#2B3440;color:#fff;font-family:system-ui,sans-serif;
               display:flex;align-items:center;justify-content:center;text-align:center;padding:24px}
          .box{max-width:340px}
          .ico{font-size:64px;line-height:1}
          h1{font-size:22px;font-weight:600;margin:14px 0 6px}
          p{color:#c7ccd3;margin:6px 0}
          .url{color:#38BDF8;word-break:break-all;font-size:14px}
          .btn{display:inline-block;margin-top:16px;background:#38BDF8;color:#08202e;
               text-decoration:none;font-weight:600;padding:12px 22px;border-radius:10px}
          .hint{color:#8b94a0;font-size:13px;margin-top:18px}
        </style></head><body><div class="box">$body</div></body></html>
        """.trimIndent()

    private fun welcomeHtml(): String = pageShell(
        """
        <div class="ico">🤖</div>
        <h1>RobotScan</h1>
        <p>Aucun site configuré.</p>
        <p class="hint">Appui long sur l'écran pour saisir l'URL du site.</p>
        """.trimIndent()
    )

    private fun errorHtml(url: String): String = pageShell(
        """
        <div class="ico">📡</div>
        <h1>Connexion impossible</h1>
        <p class="url">${url.ifBlank { "—" }}</p>
        <a class="btn" href="${url.ifBlank { "about:welcome" }}">Réessayer</a>
        <p class="hint">Appui long pour changer l'URL.</p>
        """.trimIndent()
    )

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
