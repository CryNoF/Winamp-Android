package cl.winampyt

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import java.lang.ref.WeakReference

class MainActivity : Activity() {

    companion object {
        var instance: MainActivity? = null
    }

    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this

        web = WebView(this)
        setContentView(web)
        web.setBackgroundColor(0xFF111118.toInt())
        with(web.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        CookieManager.getInstance().setAcceptCookie(true)
        web.webChromeClient = WebChromeClient()
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val u = request.url
                if (u.host == "127.0.0.1") return false
                try { startActivity(Intent(Intent.ACTION_VIEW, u)) } catch (_: Exception) {}
                return true
            }
        }
        Bridge.web = WeakReference(web)

        // El servidor tiene que estar listo antes de cargar la página.
        val server = ServerHolder.start(this)
        startForegroundService(Intent(this, PlayerService::class.java))
        web.loadUrl("http://127.0.0.1:${server.listeningPort}/?k=${ServerHolder.token}")

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    // Atrás = mandar la app al fondo; la música sigue sonando. Para cerrar: botón "Cerrar" de la notificación.
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        moveTaskToBack(true)
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        if (isFinishing) {
            stopService(Intent(this, PlayerService::class.java))
            ServerHolder.stop()
            Bridge.web = null
            web.destroy()
        }
        super.onDestroy()
    }
}
