package com.koyo.screenwarden

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File

class WebsitePreviewActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var address: TextView
    private lateinit var source: File

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        source = intent.getStringExtra(EXTRA_PATH)?.let(::File)
            ?: run { finish(); return }
        setContentView(R.layout.activity_website_preview)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.website_preview_root)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(0, bars.top, 0, bars.bottom)
            insets
        }

        webView = findViewById(R.id.website_webview)
        address = findViewById(R.id.website_address)
        address.text = "Tiyo Preview  /  ${source.name}"
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = WebViewClient()
        webView.webChromeClient = WebChromeClient()

        findViewById<TextView>(R.id.website_close).setOnClickListener { finish() }
        findViewById<TextView>(R.id.website_reload).setOnClickListener { load() }
        load()
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) webView.onResume()
    }

    override fun onPause() {
        if (::webView.isInitialized) webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            webView.loadUrl("about:blank")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun load() {
        if (!source.isFile) {
            address.text = "文件已经不存在"
            return
        }
        webView.visibility = View.VISIBLE
        webView.loadUrl(Uri.fromFile(source).toString())
    }

    companion object {
        private const val EXTRA_PATH = "website_path"

        fun intent(context: Context, file: File): Intent =
            Intent(context, WebsitePreviewActivity::class.java)
                .putExtra(EXTRA_PATH, file.absolutePath)
    }
}
