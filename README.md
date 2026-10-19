# RobotScan — wrapper WebView + Intent DataWedge (Zebra)

Wrapper Android **générique** qui héberge n'importe quelle appli web dans une
**WebView** et lui pousse les scans du terminal Zebra via les **Intents DataWedge**
(mode Broadcast).

- Nom affiché de l'app : **RobotScan** · paquet : `com.vba.robotscan`
  (c'est lui qu'on associe dans le profil DataWedge).
- **URL modifiable à chaud** : **3 doigts sur l'écran** → code d'accès → menu
  administrateur (URL, recharger, vider le cache, reconfigurer DataWedge, à propos).
  Le même APK sert donc pour n'importe quel site, sans rebuild.
- **DataWedge se configure tout seul** au premier lancement (profil `RobotScan`).
- **Bouton Retour bloqué** (pas de retour arrière ni de sortie) ; la page peut l'utiliser
  via le hook `window.onBackButton`.

> 📖 **Intégrer sa propre app web** : voir [`docs/INTEGRATION.md`](docs/INTEGRATION.md)
> (contrat `window.onScan`, décodage GS1, config DataWedge, build, rebrand, offline).

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
  java/com/vba/robotscan/MainActivity.kt    WebView + BroadcastReceiver + pont
  java/com/vba/robotscan/WebAppBridge.kt     pont JS → natif (@JavascriptInterface)
  assets/index.html, app.js                  appli web (étape 1)
  res/...                                     icône, libellés
.github/workflows/build.yml                  build cloud → APK debug en artefact
```

Détails techniques :
- `MainActivity` charge l'URL **saisie et mémorisée** (aucune URL en dur). Au premier
  lancement sans URL configurée → page d'accueil + dialogue de saisie. Ensuite, 3 doigts
  sur l'écran + code pour le menu administrateur. Nécessite la permission `INTERNET`.
- **Robustesse** : URL validée (`URLUtil`), page de repli « Réessayer » en cas d'échec
  réseau (`onReceivedError`), et **file d'attente des scans** tant que la page n'est pas
  chargée (`pageReady` / `pendingScans`, vidée dans `onPageFinished`) → aucun scan perdu.
- Enregistre un `BroadcastReceiver` sur l'action `com.vba.robotscan.SCAN` et pousse
  chaque scan au JS via `window.onScan({ data, type })`. L'offline dépend du site chargé
  (service worker de la PWA). *(Pour un offline garanti à froid, embarquer le site dans
  `assets/` et charger `file:///android_asset/...`.)*
- Receiver déclaré `RECEIVER_EXPORTED` (Android 13+) car le broadcast vient d'une
  autre appli (DataWedge).
- **Appareil photo** pour les pages web : un `WebChromeClient` répond aux champs
  `<input type="file" accept="image/*">` (`onShowFileChooser`). Avec l'attribut
  `capture`, l'appareil photo s'ouvre directement ; sans, le choix entre l'appareil
  photo et les images du terminal. L'appareil photo écrit la photo dans le cache de
  l'appli par `PhotoProvider` (content://com.vba.robotscan.photos/…, non exporté,
  photos de plus d'un jour effacées), puis elle est rendue à la page. Pas de
  permission `CAMERA` : c'est l'appareil photo du terminal qui prend la photo
  (déclarer `CAMERA` obligerait à la demander, sans quoi l'appareil photo refuse).
- Aucune dépendance AndroidX : `Activity` + `WebView` de la plateforme.
  `minSdk 24`, `targetSdk 34`.

## Build sans Android Studio (GitHub Actions)

1. Crée un dépôt GitHub et pousse ce projet :
   ```bash
   git init && git add . && git commit -m "RobotScan"
   git branch -M main
   git remote add origin <url-de-ton-depot>
   git push -u origin main
   ```
2. Le workflow **Build APK** se déclenche au push (ou via l'onglet *Actions →
   Run workflow*).
3. À la fin, télécharge l'artefact **`robotscan-apk`** (onglet *Actions* → le run →
   section *Artifacts*), ou récupère-le sur la **Release `latest`** :
   `…/releases/latest/download/app-release.apk`.

Tant que la signature release n'est pas configurée (ci-dessous), le workflow produit
un **APK debug** de repli ; une fois les secrets créés, il produit un **APK release
signé** (`app-release.apk`).

## Signature release (recommandé)

Pour un APK signé avec **ta** clé (mises à jour qui s'installent par-dessus, non
debuggable) :

1. Onglet **Actions → « Signing setup » → Run workflow**. Il génère un keystore et
   publie un artefact **`robotscan-signing`**.
2. Télécharge cet artefact → il contient `keystore.base64` et `secrets.txt`.
3. Crée 4 secrets dans **Settings → Secrets and variables → Actions** (valeurs dans
   `secrets.txt`) :
   `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
4. Relance **Build APK** → APK release signé. **Supprime l'artefact `robotscan-signing`**
   (il contient la clé). Le keystore n'est jamais committé (`.gitignore`).

> Garde le keystore/les secrets en lieu sûr : c'est l'identité de signature de l'app.
> La perdre = ne plus pouvoir publier de mise à jour installable par-dessus.

## Installer sur le terminal (sideload)

1. Active **Sources inconnues** / autorise l'installation hors store.
2. Copie l'APK sur le terminal et ouvre-le, ou via ADB :
   ```bash
   adb install -r app-release.apk
   ```

## Configurer DataWedge (profil Intent → Broadcast)

**Automatique** : au premier lancement, RobotScan crée/règle lui-même le profil
`RobotScan` via l'API DataWedge (`SET_CONFIG`). En cas de doute : menu administrateur →
« Reconfigurer DataWedge ». La procédure manuelle ci-dessous ne sert qu'en secours.

Dans l'appli **DataWedge** du terminal :

1. **⋮ → Nouveau profil** (*New profile*) → `RobotScan`.
2. **Applications associées** (*Associated apps*) → **Nouvelle application/activité**
   → paquet **`com.vba.robotscan`**, activité **`*`**.
3. **Entrée code-barres** (*Barcode input*) : **Activé**. Décodeurs à activer :
   `EAN-13`, `Code 128`, `Code 39`, `Data Matrix` (+ **GS1 DataMatrix** et le parsing
   GS1 si tu testes des codes GS1).
4. **Sortie Intent** (*Intent output*) : **Activé**
   - **Action de l'intent** (*Intent action*) : `com.vba.robotscan.SCAN`
   - **Catégorie** (*Intent category*) : `android.intent.category.DEFAULT`
   - **Mode de livraison** (*Intent delivery*) : **Broadcast intent**
5. **Désactive** la **Sortie en frappe clavier** (*Keystroke output*) pour ce profil.

> ⚠️ Le contrôle du scanner est **exclusif** : quand DataWedge est actif, n'utilise
> pas en parallèle une autre API de scan.

## Tester

1. Lance **RobotScan** sur le TC22 (nom affiché de l'app ; le paquet reste
   `com.vba.robotscan`).
2. Appuie sur la gâchette et scanne un code-barres.
3. Le scan doit apparaître à l'écran (donnée brute + symbologie, ex.
   `LABEL-TYPE-EAN13`). Côté logs : `adb logcat -s RobotScan/web`.

Si le scan s'affiche → le pont est validé, on peut passer à l'étape 2.
