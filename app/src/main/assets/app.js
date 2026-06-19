/*
 * Étape 1 — réception des scans poussés par le wrapper natif.
 * Le natif appelle window.onScan({ data, type }) à chaque lecture DataWedge.
 */
(function () {
  var n = 0;

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>]/g, function (c) {
      return { '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c];
    });
  }

  window.onScan = function (scan) {
    n++;
    document.getElementById('count').textContent = n;

    var list = document.getElementById('list');
    var empty = list.querySelector('.empty');
    if (empty) empty.remove();

    var div = document.createElement('div');
    div.className = 'scan';
    div.innerHTML =
      '<div class="n">Scan #' + n + '</div>' +
      '<code>' + esc(scan.data) + '</code>' +
      '<span class="type">' + esc(scan.type || '—') + '</span>';
    list.prepend(div);

    // Vérifie aussi le pont JS → natif (visible dans logcat : tag ScanWedge/web).
    if (window.Android && Android.log) {
      Android.log('scan reçu: ' + scan.data + ' [' + scan.type + ']');
    }
  };
})();
