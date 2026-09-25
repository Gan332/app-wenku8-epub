package com.wenku8.epubstudio.auth

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.wenku8.epubstudio.core.Wenku8SessionStore
import com.wenku8.epubstudio.core.Wenku8Urls

class LoginActivity : Activity() {
    private lateinit var sessionStore: Wenku8SessionStore
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessionStore = Wenku8SessionStore(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(246, 247, 249))
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(24, 18, 16, 8)
        }
        header.addView(TextView(this).apply {
            text = "登录轻小说文库"
            textSize = 20f
            setTextColor(Color.rgb(30, 35, 42))
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "关闭"
            setOnClickListener { finish() }
        })
        root.addView(header)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Wenku8EPUBStudio-Android/0.3.0"
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    CookieManager.getInstance().flush()
                    sessionStore.saveWebViewSession()
                    if (sessionStore.hasSession() && !url.contains("login.php", ignoreCase = true)) {
                        setResult(RESULT_OK, Intent().putExtra("logged_in", true))
                        finish()
                    }
                }
            }
            webChromeClient = WebChromeClient()
        }
        root.addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        if (savedInstanceState == null) webView.loadUrl(Wenku8Urls.LOGIN)
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }
}
