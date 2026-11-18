package com.geoloteria.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

/** Buscador oficial de Loterías y Apuestas del Estado. */
const val BUSCAR_DECIMO = "https://www.loteriasyapuestas.es/es/buscar-decimo"

/**
 * Abre el buscador oficial dentro de la app y rellena el número automáticamente:
 * busca el campo del número y el botón "Buscar" por su texto y los pulsa.
 * Si la web cambia y no los encuentra, el número ya está copiado para pegarlo a mano.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun SellerWeb(number: String, modifier: Modifier = Modifier) {
    var loading by remember { mutableStateOf(true) }
    Column(modifier) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            "Buscando el $number en la web oficial… Si no se rellena solo, pégalo en «Número buscado» (ya está copiado).",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String?) {
                            loading = false
                            view.evaluateJavascript(autoFillScript(number), null)
                        }
                    }
                    loadUrl(BUSCAR_DECIMO)
                }
            },
        )
    }
}

/**
 * Script que espera a que cargue el formulario (hasta 20 s), escribe el número y pulsa "Buscar".
 * Se ejecuta una sola vez por página.
 */
private fun autoFillScript(number: String): String = """
(function(num){
  if (window.__geoAuto) return; window.__geoAuto = true;
  var tries = 0;
  function setVal(el, v){
    var d = Object.getOwnPropertyDescriptor(Object.getPrototypeOf(el), 'value');
    if (d && d.set) { d.set.call(el, v); } else { el.value = v; }
    ['input','change','keyup','blur'].forEach(function(t){ el.dispatchEvent(new Event(t, {bubbles:true})); });
  }
  function visible(el){ return !!(el.offsetWidth || el.offsetHeight || el.getClientRects().length); }
  function findInput(){
    var all = [].slice.call(document.querySelectorAll('input'));
    var c = all.filter(function(i){
      var t = (i.type || '').toLowerCase();
      if (['hidden','checkbox','radio','submit','button','password','email','search'].indexOf(t) >= 0) return false;
      var s = ((i.name||'')+' '+(i.id||'')+' '+(i.placeholder||'')+' '+(i.getAttribute('aria-label')||'')+' '+(i.className||'')).toLowerCase();
      return visible(i) && (s.indexOf('mero') >= 0 || s.indexOf('cimo') >= 0 || i.maxLength == 5);
    });
    return c[0] || null;
  }
  function findButton(){
    var b = [].slice.call(document.querySelectorAll('button, input[type=submit], input[type=button], a'));
    return b.filter(function(x){
      var t = ((x.innerText || x.value || '') + '').trim().toLowerCase();
      return visible(x) && (t === 'buscar' || t.indexOf('buscar') === 0);
    })[0] || null;
  }
  function step(){
    tries++;
    var inp = findInput();
    if (inp) {
      setVal(inp, num);
      var btn = findButton();
      if (btn) { setTimeout(function(){ btn.click(); }, 500); return; }
    }
    if (tries < 40) setTimeout(step, 500);
  }
  step();
})('$number');
""".trimIndent()
