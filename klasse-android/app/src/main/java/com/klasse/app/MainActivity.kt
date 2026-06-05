package com.klasse.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.view.View
import android.webkit.*
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout

    companion object {
        const val HOME_URL = "https://klasse.netlify.app"

        val ALLOWED_HOSTS = setOf(
            "klasse.netlify.app",
            "netlify.app",
            "supabase.co",
            "supabase.com",
            "accounts.google.com",
            "github.com"
        )
    }

    // Android 13+ notification permission — requested so the OS delivers
    // the website's own Web Notifications when the app is foregrounded.
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user decision handled; WebView Notification API still works either way */ }

    // Holds the pending WebView permission request so we can grant/deny it
    // after the OS permission result comes back.
    private var pendingWebPermission: PermissionRequest? = null

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        setContentView(R.layout.activity_main)

        progressBar  = findViewById(R.id.progressBar)
        swipeRefresh = findViewById(R.id.swipeRefresh)
        webView      = findViewById(R.id.webView)

        requestOsNotificationPermission()
        setupWebView()
        setupSwipeRefresh()
        setupBackPress()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    private fun requestOsNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.settings.apply {
            javaScriptEnabled                = true
            domStorageEnabled                = true
            databaseEnabled                  = true
            loadWithOverviewMode             = true
            useWideViewPort                  = true
            setSupportZoom(true)
            builtInZoomControls              = true
            displayZoomControls              = false
            cacheMode                        = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess                  = false
            allowContentAccess               = false
            // Spoof a Chrome Mobile UA so the site doesn't detect the WebView
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri  = request.url ?: return false
                val host = uri.host    ?: return true

                if (ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
                    return false
                }

                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                return true
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress   = 0
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility    = View.GONE
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) view.loadUrl("file:///android_asset/offline.html")
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                handler.cancel()
            }
        }

        webView.webChromeClient = object : WebChromeClient() {

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) progressBar.visibility = View.GONE
            }

            // Let the website call Notification.requestPermission() — forward it to the OS
            override fun onPermissionRequest(request: PermissionRequest) {
                val resources = request.resources
                if (resources.contains(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(arrayOf(PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    return
                }
                if (resources.contains("android.webkit.resource.NOTIFICATIONS") ||
                    resources.any { it.contains("notification", ignoreCase = true) }
                ) {
                    val osGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(
                            this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                        ) == PackageManager.PERMISSION_GRANTED

                    if (osGranted) {
                        request.grant(resources)
                    } else {
                        pendingWebPermission = request
                        notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    return
                }
                request.deny()
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPermission === request) pendingWebPermission = null
            }

            override fun onCreateWindow(
                view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val newWebView = WebView(this@MainActivity)
                newWebView.webViewClient = WebViewClient()
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }
        }
    }

    private fun setupSwipeRefresh() {
        swipeRefresh.setOnRefreshListener { webView.reload() }
        swipeRefresh.setColorSchemeResources(R.color.colorPrimary)
    }

    private fun setupBackPress() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())

        // Grant any pending web notification permission if OS was approved
        pendingWebPermission?.let { req ->
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) req.grant(req.resources) else req.deny()
            pendingWebPermission = null
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
