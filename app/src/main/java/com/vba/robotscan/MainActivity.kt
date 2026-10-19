package com.vba.robotscan

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.MediaStore
import android.text.InputType
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import org.json.JSONObject
import java.io.File

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

    // Relances automatiques après un échec de chargement. À froid et hors-ligne, la
    // toute 1re navigation échoue souvent avant que le service worker du site ne
    // prenne la main ; une relance depuis la page aboutit alors (offline compris).
    private var autoRetries = 0
    private val retryDelaysMs = longArrayOf(400, 1200, 3000)

    // Quand le dialogue URL est ouvert, un scan (QR contenant l'URL) remplit ce champ
    // au lieu d'être transmis à la page web.
    private var urlInput: EditText? = null

    private val scanReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != scanAction) return
            val data = intent.getStringExtra("com.symbol.datawedge.data_string") ?: return
            val type = intent.getStringExtra("com.symbol.datawedge.label_type") ?: ""
            handleScan(data, type)
        }
    }

    /** Aiguille un scan : vers le champ URL si le dialogue est ouvert, sinon vers la page. */
    private fun handleScan(data: String, type: String) {
        val field = urlInput
        if (field != null) {
            runOnUiThread {
                field.setText(data.trim())
                field.setSelection(field.text.length)
            }
            return
        }
        pushScanToWeb(data, type)
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
                autoRetries = 0
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
                if (autoRetries < retryDelaysMs.size) {
                    showRetryPage(retryDelaysMs[autoRetries])
                    autoRetries++
                } else {
                    showErrorPage()
                }
            }
        }
    }

    private fun isHttp(url: String?): Boolean =
        url != null && (url.startsWith("http://") || url.startsWith("https://"))

    // ── L'APPAREIL PHOTO pour la page web (un champ photo, input type="file" avec capture) ──
    // Sans WebChromeClient, la WebView ignore ce champ : rien ne s'ouvre. Ici : avec « capture », l'appareil photo
    // directement ; sans, le choix entre l'appareil photo et les images du terminal. La photo est écrite par
    // l'appareil photo dans le cache de RobotScan (PhotoProvider), puis rendue à la page. Aucune permission à demander :
    // c'est l'appareil photo du terminal qui prend la photo (RobotScan ne déclare pas CAMERA, sinon il devrait l'obtenir).
    private val choixFichier = 41
    private var fichierEnAttente: ValueCallback<Array<Uri>>? = null
    private var photoEnCours: Uri? = null

    private val chromeClient = object : WebChromeClient() {
        override fun onShowFileChooser(
            view: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: WebChromeClient.FileChooserParams?
        ): Boolean {
            fichierEnAttente?.onReceiveValue(null)   // un choix resté en suspens : la WebView n'en rouvrirait plus
            fichierEnAttente = filePathCallback
            val photo = intentionPhoto()
            val images = try { fileChooserParams?.createIntent() } catch (_: Exception) { null }
            val intention = when {
                fileChooserParams?.isCaptureEnabled == true -> photo
                images != null -> Intent.createChooser(images, "Photo").putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf<Parcelable>(photo))
                else -> photo
            }
            return try {
                startActivityForResult(intention, choixFichier)
                true
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this@MainActivity, "Pas d'appareil photo sur ce terminal", Toast.LENGTH_SHORT).show()
                fichierEnAttente = null
                filePathCallback?.onReceiveValue(null)
                true
            }
        }
    }

    private fun dossierPhotos(): File = File(cacheDir, "photos").apply { mkdirs() }

    /** L'appareil photo, qui écrira la photo à une adresse de PhotoProvider (les photos de plus d'un jour sont effacées). */
    private fun intentionPhoto(): Intent {
        val dossier = dossierPhotos()
        val hier = System.currentTimeMillis() - 24L * 3600 * 1000
        dossier.listFiles()?.forEach { if (it.lastModified() < hier) it.delete() }
        val uri = Uri.parse("content://$packageName.photos/photo-${System.currentTimeMillis()}.jpg")
        photoEnCours = uri
        return Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            clipData = ClipData.newRawUri("photo", uri)   // (la permission d'écrire suit le ClipData)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != choixFichier) {
            super.onActivityResult(requestCode, resultCode, data)
            return
        }
        val rappel = fichierEnAttente
        fichierEnAttente = null
        var resultat: Array<Uri>? = null
        if (resultCode == RESULT_OK) {
            val choisie = data?.data
            val photo = photoEnCours
            if (choisie != null) {
                resultat = arrayOf(choisie)   // une image prise dans le terminal
            } else if (photo != null && File(dossierPhotos(), photo.lastPathSegment ?: "").length() > 0) {
                resultat = arrayOf(photo)     // la photo que l'appareil photo vient d'écrire
            }
        }
        photoEnCours = null
        rappel?.onReceiveValue(resultat)
    }

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
        webView.webChromeClient = chromeClient   // l'appareil photo des champs <input type="file"> (voir onShowFileChooser)
        webView.addJavascriptInterface(WebAppBridge(), "Android")

        // Appui long n'importe où → changer l'URL du site (mémorisée, sans rebuild).
        // Protégé par un code (BuildConfig.URL_PIN) pour que l'utilisateur final ne le change pas.
        webView.setOnLongClickListener {
            askPinThenShowUrlDialog()
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

    /**
     * Demande le code avant d'ouvrir le dialogue de changement d'URL. Sans code
     * configuré au build (BuildConfig.URL_PIN vide), on ouvre directement.
     */
    private fun askPinThenShowUrlDialog() {
        val pin = BuildConfig.URL_PIN
        if (pin.isEmpty()) return showUrlDialog()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "Code"
        }
        AlertDialog.Builder(this)
            .setTitle("Code d'accès")
            .setMessage("Saisis le code pour modifier l'URL du site.")
            .setView(input)
            .setPositiveButton("Valider") { _, _ ->
                if (input.text.toString() == pin) {
                    showUrlDialog()
                } else {
                    Toast.makeText(this, "Code incorrect", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Annuler", null)
            .show()
    }

    /** Boîte de dialogue pour saisir/changer l'URL (au clavier ou par scan d'un QR). */
    private fun showUrlDialog() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            hint = "https://mon-site.fr  —  ou scanne un QR"
            setText(configuredUrl() ?: "")
            setSelection(text.length)
        }
        urlInput = input   // les scans iront dans ce champ tant que le dialogue est ouvert
        val dialog = AlertDialog.Builder(this)
            .setTitle("URL du site")
            .setMessage("Tape l'URL, ou scanne un QR code qui la contient (gâchette).")
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
                autoRetries = 0
                webView.loadUrl(url)
            }
            .setNegativeButton("Annuler", null)
            .create()
        dialog.setOnDismissListener { urlInput = null } // on rebascule les scans vers la page
        dialog.show()
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

    /**
     * Écran « Chargement… » qui relance la navigation depuis la page après [delayMs],
     * exactement comme un clic sur « Réessayer » (navigation initiée par la page).
     */
    private fun showRetryPage(delayMs: Long) {
        val url = configuredUrl() ?: return showErrorPage()
        webView.loadDataWithBaseURL("about:retry", retryHtml(url, delayMs), "text/html", "UTF-8", null)
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

    private fun retryHtml(url: String, delayMs: Long): String = pageShell(
        """
        <div class="ico">🤖</div>
        <h1>Chargement…</h1>
        <p class="url">$url</p>
        <script>setTimeout(function(){ location.replace(${JSONObject.quote(url)}); }, $delayMs);</script>
        """.trimIndent()
    )

    private fun errorHtml(url: String): String = pageShell(
        """
        <div class="ico">📡</div>
        <h1>Connexion impossible</h1>
        <p class="url">${url.ifBlank { "—" }}</p>
        <a class="btn" href="${url.ifBlank { "about:welcome" }}">Réessayer</a>
        <p class="hint">Nouvel essai automatique toutes les 15 s.<br>Appui long pour changer l'URL.</p>
        ${if (url.isBlank()) "" else "<script>setTimeout(function(){ location.replace(" + JSONObject.quote(url) + "); }, 15000);</script>"}
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
