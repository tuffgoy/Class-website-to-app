package com.klasse.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
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
import android.widget.ImageButton
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnShare: ImageButton

    private var notifSeq = 0

    companion object {
        const val HOME_URL = "https://klasse.netlify.app"
        const val NOTIF_CHANNEL_ID = "klasse_web"
        const val NOTIF_CHANNEL_NAME = "Klasse Benachrichtigungen"

        val ALLOWED_HOSTS = setOf(
            "klasse.netlify.app",
            "netlify.app",
            "supabase.co",
            "supabase.com",
            "accounts.google.com",
            "github.com"
        )
    }

    // Android 13+ notification permission
    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user decision recorded; web Notification API still works */ }

    private var pendingWebPermission: PermissionRequest? = null

    // JavaScript bridge: intercepts window.Notification calls from the website
    inner class KlasseBridge {
        @JavascriptInterface
        fun showNotification(title: String, body: String) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(
                    this@MainActivity, Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) return

            val intent = Intent(this@MainActivity, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pi = PendingIntent.getActivity(
                this@MainActivity, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this@MainActivity, NOTIF_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title.ifBlank { "Klasse" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(notifSeq++, notification)
        }

        @JavascriptInterface
        fun isAndroidApp(): Boolean = true
    }

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
        btnBack      = findViewById(R.id.btnBack)
        btnHome      = findViewById(R.id.btnHome)
        btnRefresh   = findViewById(R.id.btnRefresh)
        btnShare     = findViewById(R.id.btnShare)

        createNotificationChannel()
        requestOsNotificationPermission()
        setupWebView()
        setupSwipeRefresh()
        setupBackPress()
        setupBottomBar()

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(HOME_URL)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIF_CHANNEL_ID,
                NOTIF_CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Benachrichtigungen von klasse.netlify.app" }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
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

    // JS injected on every page load:
    // 1. Overrides window.Notification so native Android shows the toast
    // 2. Prevents programmatic autofocus on filter inputs (avoids keyboard pop-up)
    private val INJECTED_JS = """
(function() {
  // ── Notification bridge ──────────────────────────────────────────────────
  // Replace the browser Notification API with a native Android call
  if (window.KlasseBridge) {
    window._KlasseNativeNotif = true;

    function NativeNotification(title, opts) {
      try {
        KlasseBridge.showNotification(
          String(title || ''),
          String((opts && opts.body) || '')
        );
      } catch(e) {}
    }
    NativeNotification.requestPermission = function() {
      return Promise.resolve('granted');
    };
    Object.defineProperty(NativeNotification, 'permission', {
      get: function() { return 'granted'; },
      configurable: true
    });
    NativeNotification.prototype = {};

    try { window.Notification = NativeNotification; } catch(e) {}
    try {
      Object.defineProperty(window, 'Notification', {
        value: NativeNotification, writable: true, configurable: true
      });
    } catch(e) {}
  }

  // ── Autofocus prevention ─────────────────────────────────────────────────
  // Prevent filter/search inputs from stealing keyboard focus automatically.
  // Focus is only allowed if triggered within 600 ms of a user touch.
  var _lastTouch = 0;
  document.addEventListener('touchend', function() {
    _lastTouch = Date.now();
  }, true);
  document.addEventListener('focusin', function(e) {
    var el = e.target;
    if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) return;
    var ph = (el.placeholder || '').toLowerCase();
    // Target filter inputs — change keywords if the site changes placeholder text
    if (ph.indexOf('filtern') !== -1 || ph.indexOf('suchen') !== -1 || ph.indexOf('search') !== -1) {
      if (Date.now() - _lastTouch > 600) {
        el.blur();
      }
    }
  }, true);
})();
""".trimIndent()

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
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        }

        // Register JavaScript bridge — accessible as KlasseBridge in JS
        webView.addJavascriptInterface(KlasseBridge(), "KlasseBridge")

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
                updateBottomBarState()
            }

            override fun onPageFinished(view: WebView, url: String) {
                progressBar.visibility    = View.GONE
                swipeRefresh.isRefreshing = false
                CookieManager.getInstance().flush()
                updateBottomBarState()

                // Inject JS bridge and autofocus fix on every page
                view.evaluateJavascript(INJECTED_JS, null)
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

            // Grant web notification permission automatically (native bridge handles delivery)
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

    private fun setupBottomBar() {
        btnBack.setOnClickListener {
            if (webView.canGoBack()) webView.goBack()
        }
        btnHome.setOnClickListener {
            webView.loadUrl(HOME_URL)
        }
        btnRefresh.setOnClickListener {
            webView.reload()
        }
        btnShare.setOnClickListener {
            val url = webView.url ?: HOME_URL
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, url)
                putExtra(Intent.EXTRA_SUBJECT, webView.title ?: "Klasse")
            }
            startActivity(Intent.createChooser(intent, "Teilen via"))
        }
    }

    private fun updateBottomBarState() {
        btnBack.alpha = if (webView.canGoBack()) 1.0f else 0.35f
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
        updateBottomBarState()

        // Grant any pending web notification permission if OS approved while paused
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
