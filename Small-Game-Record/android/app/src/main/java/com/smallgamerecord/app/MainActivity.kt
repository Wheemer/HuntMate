package com.smallgamerecord.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import java.io.OutputStreamWriter

class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var pendingCsv: String? = null
    private val createCsvRequest = 7001

    @Suppress("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(32, 51, 38)
        window.navigationBarColor = Color.rgb(16, 24, 18)

        webView = WebView(this)
        webView.setBackgroundColor(Color.rgb(16, 24, 18))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = false
        webView.settings.allowFileAccessFromFileURLs = false
        webView.settings.allowUniversalAccessFromFileURLs = false
        webView.addJavascriptInterface(CsvBridge(), "SmallGameRecordBridge")
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri: Uri = request?.url ?: return true
                return uri.scheme != "file" || uri.path?.startsWith("/android_asset/") != true
            }
        }
        webView.webChromeClient = WebChromeClient()
        setContentView(webView)

        if (savedInstanceState == null) {
            webView.loadUrl("file:///android_asset/index.html")
        } else {
            webView.restoreState(savedInstanceState)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        webView.saveState(outState)
        super.onSaveInstanceState(outState)
    }

    @Deprecated("Deprecated in Android API; retained for broad Android compatibility")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    private inner class CsvBridge {
        @JavascriptInterface
        fun saveCsv(filename: String, contents: String) {
            runOnUiThread {
                pendingCsv = contents
                val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "text/csv"
                    putExtra(Intent.EXTRA_TITLE, filename.ifBlank { "small-game-records.csv" })
                }
                try {
                    startActivityForResult(intent, createCsvRequest)
                } catch (_: Exception) {
                    pendingCsv = null
                    Toast.makeText(this@MainActivity, "Could not open the file saver.", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @Deprecated("Deprecated by Android API; retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != createCsvRequest) return
        val csv = pendingCsv
        pendingCsv = null
        if (resultCode != RESULT_OK || data?.data == null || csv == null) return
        try {
            contentResolver.openOutputStream(data.data!!)?.use { stream ->
                OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(csv) }
            } ?: throw IllegalStateException("Could not open selected file")
            Toast.makeText(this, "CSV backup saved.", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Could not save the CSV backup.", Toast.LENGTH_LONG).show()
        }
    }
}
