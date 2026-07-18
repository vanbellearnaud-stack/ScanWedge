# RobotScan — guide d'intégration développeur

RobotScan est un **wrapper Android générique** pour terminaux de scan (Zebra & co).
Il ouvre une **WebView** sur l'URL de ton choix et pousse chaque scan matériel à ta
page web. Côté web, tu n'as **qu'une seule fonction à implémenter**.

- Paquet Android : `com.vba.robotscan` · Nom affiché : `RobotScan`
- Le scan arrive via **DataWedge en mode Intent** (bloc atomique, pas d'émulation
  clavier → pas d'entrelacement).
- Rien à installer côté web : ta page peut être hébergée n'importe où (HTTP/HTTPS).

```
Terminal Zebra ──DataWedge (Intent)──▶ RobotScan (WebView) ──window.onScan()──▶ ta page web
```

---

## 1. Le contrat JavaScript (l'essentiel)

Implémente une fonction globale **`window.onScan`**. RobotScan l'appelle à chaque
lecture :

```js
window.onScan = function (scan) {
  // scan.data : chaîne décodée du code-barres (String)
  // scan.type : symbologie (String), ex. "LABEL-TYPE-EAN13", "LABEL-TYPE-DATAMATRIX"
  console.log('scan', scan.data, scan.type);
};
```

Payload reçu :

| Champ  | Type   | Description                                                        |
|--------|--------|--------------------------------------------------------------------|
| `data` | string | Donnée décodée. Pour un GS1, contient le **FNC1 / GS (char 0x1D)**. |
| `type` | string | Symbologie DataWedge (`LABEL-TYPE-…`).                              |

Points importants :

- **Définis `window.onScan` tôt** (dans le `<head>` ou un script en début de page).
  Les scans reçus **avant** que la page soit prête sont mis en **file d'attente** et
  rejoués une fois la page chargée — mais la fonction doit exister à ce moment-là.
- `data` est transmis **verbatim** (le FNC1 des GS1 est préservé en ``).
- Le scan est **local** au terminal : il fonctionne **hors-ligne**.

### Exemple minimal complet

```html
<!DOCTYPE html>
<html lang="fr"><head><meta charset="utf-8">
<script>
  window.onScan = function (scan) {
    var li = document.createElement('li');
    li.textContent = scan.type + ' → ' + scan.data;
    document.getElementById('log').prepend(li);
  };
</script></head>
<body>
  <h1>Scans</h1>
  <ul id="log"></ul>
</body></html>
```

### Pont JS → natif (optionnel)

Un objet `Android` est exposé pour logguer côté logcat :

```js
if (window.Android && Android.log) Android.log('coucou depuis le web');
// → adb logcat -s RobotScan/web
```

---

## 2. Décoder un GS1 (DataMatrix, GS1-128…)

`data` peut être une chaîne d'éléments GS1 avec des **AI** (Application Identifiers)
et des séparateurs **FNC1/GS (ASCII 29)** pour les champs de longueur variable.

Deux formes possibles selon la config DataWedge :

- **Brut avec GS** : `\x1d0103400936801423172712311021LOT…`
- **Avec parenthèses** (si l'option GS1 est activée) : `(01)03400936801423(17)271231(10)LOT`

Décodage minimal des AI courants (GTIN 01, date 17, lot 10, série 21) :

```js
const GS = '\x1d';
const FIXED = { '01': 14, '17': 6, '11': 6, '15': 6, '00': 18, '20': 2 }; // longueurs fixes

function parseGs1(raw) {
  raw = raw.replace(/[\r\n]+$/, '').replace(/^\][A-Za-z]\d/, ''); // vire l'ident. AIM éventuel
  const out = {};
  if (raw.indexOf('(') !== -1) {                                   // format à parenthèses
    let m, re = /\((\d{2,4})\)([^(]*)/g;
    while ((m = re.exec(raw))) out[m[1]] = m[2];
    return out;
  }
  let s = raw.replace(new RegExp('^' + GS), ''), i = 0;            // format brut
  while (i < s.length) {
    const ai = s.substr(i, 2); i += 2;
    if (FIXED[ai]) { out[ai] = s.substr(i, FIXED[ai]); i += FIXED[ai]; }
    else { let e = s.indexOf(GS, i); if (e < 0) e = s.length; out[ai] = s.substring(i, e); i = e; if (s[i] === GS) i++; }
  }
  return out;
}
// parseGs1(scan.data) → { '01': '03400936801423', '17': '271231', '10': 'LOT42' }
```

> C'est une base ; pour un parseur GS1 complet (tous les AI, longueurs variables des
> AI à 3-4 chiffres), pars de celui-ci et complète la table.

---

## 3. Configurer le terminal (DataWedge)

Dans l'appli **DataWedge** du terminal, crée un profil :

1. **Applications associées** → paquet **`com.vba.robotscan`**, activité `*`.
2. **Entrée code-barres** : activée. Décodeurs voulus (EAN-13, Code 128/39, Data
   Matrix, **GS1 DataMatrix** + parsing GS1 si besoin).
3. **Sortie Intent** : activée
   - Action : **`com.vba.robotscan.SCAN`**
   - Catégorie : **`android.intent.category.DEFAULT`**
   - Mode de livraison : **Broadcast intent**
4. **Sortie en frappe clavier** : **désactivée**.

> Le contrôle du scanner est **exclusif** : n'active pas une autre voie de scan en
> parallèle.

Extras d'intent lus par RobotScan : `com.symbol.datawedge.data_string` (→ `data`) et
`com.symbol.datawedge.label_type` (→ `type`).

---

## 4. Configurer l'URL du site

- **Appui long** sur l'écran → dialogue « URL du site » (saisie **validée** puis
  **mémorisée** dans les préférences). Le même APK sert donc pour n'importe quel site,
  sans reconstruire.
- **Premier lancement** sans URL → page d'accueil + dialogue de saisie direct.
- **Par QR code** : dialogue ouvert → **gâchette** sur un QR contenant l'URL → le champ
  se remplit tout seul (le scan est capté par le dialogue, pas transmis à la page).
  ⚠️ Nécessite le **décodeur QR Code activé** dans le profil DataWedge.
- URL sans schéma → `https://` ajouté automatiquement.

Stockage : `SharedPreferences("robotscan")`, clé `start_url`.

---

## 5. Construire l'APK

Pas besoin d'Android Studio : le build tourne sur **GitHub Actions**.

1. Pousse le projet sur GitHub.
2. Le workflow `.github/workflows/build.yml` compile et publie :
   - un **artefact** `robotscan-apk` (onglet Actions) ;
   - une **Release `latest`** → URL permanente `…/releases/latest/download/app-release.apk`
     (pratique pour un QR d'install).
3. Sideload : `adb install -r app-release.apk` (autoriser les sources inconnues).

**Signature release** : lance une fois le workflow *Signing setup* (génère un keystore),
crée les 4 secrets `KEYSTORE_BASE64` / `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`
(voir README §Signature), puis relance le build → APK release signé. Sans ces secrets,
le workflow produit un APK **debug** de repli.

Environnement : JDK 17, Gradle 8.9, AGP 8.5, Kotlin 1.9. `minSdk 24`, `targetSdk 34`,
**aucune dépendance AndroidX** (Activity + WebView de la plateforme).

---

## 6. Personnaliser (rebrand)

| Élément            | Où                                                        |
|--------------------|----------------------------------------------------------|
| Nom de l'app       | `app/src/main/res/values/strings.xml` → `app_name`       |
| Icône              | `app/src/main/res/drawable/ic_launcher.xml` (VectorDrawable) |
| Paquet             | `applicationId` dans `app/build.gradle.kts` + `namespace` (⚠️ à répercuter dans l'action d'intent DataWedge) |
| Action d'intent    | `scanAction` dans `MainActivity.kt`                       |

> Nom et icône **ne sont pas modifiables à l'exécution** (contrainte Android). Pour du
> white-label, rebuild avec les bonnes valeurs, ou crée un raccourci d'accueil
> (`requestPinShortcut`) à nom/icône libres.

---

## 7. Hors-ligne

La WebView s'appuie sur l'**Android System WebView** (Chromium) du terminal :

- **Service workers / Cache API / IndexedDB** : supportés (WebView récente). Ta page
  peut donc être une **PWA offline** (mise en cache au 1er chargement en ligne).
- **Pas de Push API** dans la WebView.
- Réglages activés par le wrapper : `javaScriptEnabled`, `domStorageEnabled`,
  `databaseEnabled`, `allowFileAccess`.
- Pour un offline **garanti dès le démarrage à froid** (sans réseau au 1er lancement),
  embarque le site dans `app/src/main/assets/` et charge `file:///android_asset/…`.

---

## 8. Robustesse (intégrée)

- **File d'attente des scans** : un scan reçu avant `onPageFinished` est bufferisé et
  rejoué → aucun scan perdu au démarrage.
- **Erreur réseau** : si l'URL est injoignable → page de repli « Réessayer »
  (appui long pour changer l'URL). Ne s'affiche pas si le service worker sert la page
  depuis le cache.
- **Validation d'URL** avant chargement.

---

## 9. Débogage

```bash
adb logcat -s RobotScan/web        # logs poussés via Android.log(...)
adb shell am broadcast -a com.vba.robotscan.SCAN \
  --es com.symbol.datawedge.data_string "3401579843411" \
  --es com.symbol.datawedge.label_type "LABEL-TYPE-EAN13"   # simuler un scan sans matériel
```

Pour inspecter la WebView elle-même : `chrome://inspect` sur un PC (USB debug), la
page apparaît sous « Remote target ».

---

## 10. Sécurité & limites

- `addJavascriptInterface` expose l'objet `Android` à **toute** page chargée : ne
  charge que des URL de confiance (idéalement fige/whiteliste le domaine en prod).
- APK **debug** (clé debug) : suffisant pour du sideload interne, pas pour un store.
- Un seul consommateur du scanner à la fois (exclusivité DataWedge).
