package com.slidedify.core.platform

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * OAuth login inside the app for any provider: loads the service's authorize page in a WebView and catches the
 * redirect to [EXTRA_REDIRECT_URI] directly, so it doesn't depend on the browser handing the
 * custom-scheme redirect back to the app. Returns `code`/`state`/`error` as activity result.
 */
class OAuthWebLoginActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var redirectUri: String

    @SuppressLint("SetJavaScriptEnabled") // Spotify's login page needs JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val authorizeUrl = intent.getStringExtra(EXTRA_AUTHORIZE_URL)
        redirectUri = intent.getStringExtra(EXTRA_REDIRECT_URI).orEmpty()
        if (authorizeUrl == null || redirectUri.isEmpty()) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    handle(request.url)
            }
        }
        setContentView(webView)
        if (savedInstanceState == null) webView.loadUrl(authorizeUrl) else webView.restoreState(savedInstanceState)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        if (::webView.isInitialized) webView.destroy()
        super.onDestroy()
    }

    /** True when [url] is our redirect: the login is over, return its parameters. */
    private fun handle(url: Uri): Boolean {
        if (!url.toString().startsWith(redirectUri)) return false
        val result = Intent()
            .putExtra(RESULT_CODE, url.getQueryParameter("code"))
            .putExtra(RESULT_STATE, url.getQueryParameter("state"))
            .putExtra(RESULT_ERROR, url.getQueryParameter("error"))
        setResult(RESULT_OK, result)
        finish()
        return true
    }

    companion object {
        const val EXTRA_AUTHORIZE_URL = "authorize_url"
        const val EXTRA_REDIRECT_URI = "redirect_uri"
        const val RESULT_CODE = "code"
        const val RESULT_STATE = "state"
        const val RESULT_ERROR = "error"
    }
}
