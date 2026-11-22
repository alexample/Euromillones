package com.geoloteria.app

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/** Artículo de Wikipedia abierto dentro de la app (null = ninguno). */
object WikiNav {
    var url by mutableStateOf<String?>(null)

    /** Versión de escritorio completa, para abrirla en el navegador. */
    fun desktop(url: String): String {
        val title = url.substringAfter("/wiki/")
        return "https://es.wikipedia.org/w/index.php?title=$title&mobileaction=toggle_view_desktop"
    }
}

/**
 * La Wikipedia para móvil pliega las secciones y solo enseña la introducción.
 * Aquí se abre el artículo y se despliegan todas las secciones automáticamente.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WikiPage(url: String, modifier: Modifier = Modifier) {
    var loading by remember { mutableStateOf(true) }
    Column(modifier) {
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                WebView(ctx).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, u: String?) {
                            loading = false
                            view.evaluateJavascript(EXPAND_JS, null)
                        }
                    }
                    loadUrl(url)
                }
            },
            update = { w -> if (w.url != url && w.originalUrl != url) w.loadUrl(url) },
        )
    }
}

private const val EXPAND_JS = """
(function(){
  function ex(){
    document.querySelectorAll('.collapsible-heading').forEach(function(h){
      h.classList.add('open-block'); h.setAttribute('aria-expanded','true');
    });
    document.querySelectorAll('.collapsible-block, section').forEach(function(b){
      b.classList.add('open-block'); b.removeAttribute('hidden'); b.style.display='block';
    });
    document.querySelectorAll('details').forEach(function(d){ d.open = true; });
  }
  ex(); setTimeout(ex, 600); setTimeout(ex, 1500); setTimeout(ex, 3000);
})();
"""
