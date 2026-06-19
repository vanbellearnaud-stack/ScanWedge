# ScanWedge — wrapper WebView + Intent DataWedge (TC22)

Wrapper Android minimal qui héberge une appli web dans une **WebView** et lui pousse
les scans du terminal Zebra via les **Intents DataWedge** (mode Broadcast).

> **Étape 1 (ce dépôt)** : dérisquer le pont. L'appli affiche brut chaque scan reçu
> (donnée + symbologie) pour prouver la chaîne
> `DataWedge → Intent → BroadcastReceiver → WebView/JS`.
> L'appli web offline complète (référentiel embarqué, file de sorties, synchro) est
> l'**étape 2**, à greffer une fois ce maillon validé.

Pourquoi un wrapper natif plutôt qu'une PWA Chrome : l'Intent livre **chaque scan en
un seul bloc** (donnée + symbologie + octets bruts possibles), ce qui élimine *par
construction* l'entrelacement de l'émulation clavier. Les fichiers web sont embarqués
(`file:///android_asset/...`) → démarrage hors-ligne sans service worker.

## Contenu

```
app/src/main/
  AndroidManifest.xml
  java/com/demo/scanwedge/MainActivity.kt   WebView + BroadcastReceiver + pont
  java/com/demo/scanwedge/WebAppBridge.kt    pont JS → natif (@JavascriptInterface)
  assets/index.html, app.js                  appli web (étape 1)
  res/...                                     icône, libellés
.github/workflows/build.yml                  build cloud → APK debug en artefact
```

Détails techniques :
- `MainActivity` charge l'appli web déployée `https://www.pep35.cloud/mobile.html`
  (option B), enregistre un `BroadcastReceiver` sur l'action `com.demo.scanwedge.SCAN`,
  et pousse chaque scan au JS via `window.onScan({ data, type })`. L'offline est assuré
  par le service worker de la PWA. *(Pour un offline garanti dès le démarrage à froid,
  embarquer l'appli dans `assets/` et charger `file:///android_asset/...`.)*
  Nécessite la permission `INTERNET`.
- Receiver déclaré `RECEIVER_EXPORTED` (Android 13+) car le broadcast vient d'une
  autre appli (DataWedge).
- Aucune dépendance AndroidX : `Activity` + `WebView` de la plateforme.
  `minSdk 24`, `targetSdk 34`.

## Build sans Android Studio (GitHub Actions)

1. Crée un dépôt GitHub et pousse ce projet :
   ```bash
   git init && git add . && git commit -m "ScanWedge étape 1"
   git branch -M main
   git remote add origin <url-de-ton-depot>
   git push -u origin main
   ```
2. Le workflow **Build APK** se déclenche au push (ou via l'onglet *Actions →
   Run workflow*).
3. À la fin, télécharge l'artefact **`scanwedge-debug-apk`** (onglet *Actions* → le
   run → section *Artifacts*). Il contient `app-debug.apk`.

L'APK debug est signé avec la clé debug : suffisant pour le sideload de démo.

## Installer sur le TC22 (sideload)

1. Active **Sources inconnues** / autorise l'installation hors store.
2. Copie l'APK sur le terminal et ouvre-le, ou via ADB :
   ```bash
   adb install -r app-debug.apk
   ```

## Configurer DataWedge (profil Intent → Broadcast)

Dans l'appli **DataWedge** du TC22 :

1. **⋮ → Nouveau profil** (*New profile*) → `ScanWedge`.
2. **Applications associées** (*Associated apps*) → **Nouvelle application/activité**
   → paquet **`com.demo.scanwedge`**, activité **`*`**.
3. **Entrée code-barres** (*Barcode input*) : **Activé**. Décodeurs à activer :
   `EAN-13`, `Code 128`, `Code 39`, `Data Matrix` (+ **GS1 DataMatrix** et le parsing
   GS1 si tu testes des codes GS1).
4. **Sortie Intent** (*Intent output*) : **Activé**
   - **Action de l'intent** (*Intent action*) : `com.demo.scanwedge.SCAN`
   - **Catégorie** (*Intent category*) : `android.intent.category.DEFAULT`
   - **Mode de livraison** (*Intent delivery*) : **Broadcast intent**
5. **Désactive** la **Sortie en frappe clavier** (*Keystroke output*) pour ce profil.

> ⚠️ Le contrôle du scanner est **exclusif** : quand DataWedge est actif, n'utilise
> pas en parallèle une autre API de scan.

## Tester

1. Lance **ScanWedge** sur le TC22 (la page « test du pont » s'affiche).
2. Appuie sur la gâchette et scanne un code-barres.
3. Le scan doit apparaître à l'écran (donnée brute + symbologie, ex.
   `LABEL-TYPE-EAN13`). Côté logs : `adb logcat -s ScanWedge/web`.

Si le scan s'affiche → le pont est validé, on peut passer à l'étape 2.
