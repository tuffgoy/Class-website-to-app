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
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

class MainActivity : AppCompatActivity() {

    private lateinit var webView: android.webkit.WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var btnBack: ImageButton
    private lateinit var btnHome: ImageButton
    private lateinit var btnRefresh: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var bottomBar: LinearLayout

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

    private val notificationPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* user decision recorded; web Notification API still works */ }

    private val audioPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        pendingAudioPermission?.let { req ->
            if (granted) req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else req.deny()
            pendingAudioPermission = null
        }
    }

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uris: Array<android.net.Uri>? = if (result.resultCode == android.app.Activity.RESULT_OK) {
            val data = result.data
            when {
                data?.clipData != null -> {
                    val clip = data.clipData!!
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                }
                data?.data != null -> arrayOf(data.data!!)
                cameraUri != null  -> arrayOf(cameraUri!!)
                else               -> null
            }
        } else {
            null
        }
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
        cameraUri = null
    }

    private var pendingWebPermission: android.webkit.PermissionRequest? = null
    private var pendingAudioPermission: android.webkit.PermissionRequest? = null
    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null
    private var cameraUri: android.net.Uri? = null

    inner class KlasseBridge {
        @android.webkit.JavascriptInterface
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
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title.ifBlank { "Klasse" })
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(pi)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            nm.notify(notifSeq++, notification)
        }

        @android.webkit.JavascriptInterface
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
        bottomBar    = findViewById(R.id.bottomBar)

        createNotificationChannel()
        requestOsNotificationPermission()
        setupWebView()
        setupSwipeRefresh()
        setupBackPress()
        setupBottomBar()

        ViewCompat.setOnApplyWindowInsetsListener(bottomBar) { v, insets ->
            val navInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navInsets.bottom)
            insets
        }

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

    private val INJECTED_JS = """
(function() {
  if (window.KlasseBridge) {
    window._KlasseNativeNotif = true;

    // Bridge service-worker SHOW_NOTIFICATION messages to the native layer
    if (!window._KlasseSWBridged && 'serviceWorker' in navigator) {
      window._KlasseSWBridged = true;
      navigator.serviceWorker.addEventListener('message', function(event) {
        if (event.data && event.data.type === 'SHOW_NOTIFICATION') {
          try {
            KlasseBridge.showNotification(
              String(event.data.title || 'Klasse'),
              String(event.data.body || '')
            );
          } catch(e) {}
        }
      });
    }

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

  var _lastTouch = 0;
  document.addEventListener('touchend', function() {
    _lastTouch = Date.now();
  }, true);
  document.addEventListener('focusin', function(e) {
    var el = e.target;
    if (!el || (el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA')) return;
    var ph = (el.placeholder || '').toLowerCase();
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
            cacheMode                        = android.webkit.WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess                  = false
            allowContentAccess               = false
            userAgentString =
                "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 KlasseApp/1.0"
        }

        webView.addJavascriptInterface(KlasseBridge(), "KlasseBridge")

        android.webkit.CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }

        webView.webViewClient = object : android.webkit.WebViewClient() {

            override fun shouldOverrideUrlLoading(view: android.webkit.WebView, request: android.webkit.WebResourceRequest): Boolean {
                val uri  = request.url ?: return false
                val host = uri.host    ?: return true

                if (ALLOWED_HOSTS.any { allowed -> host == allowed || host.endsWith(".$allowed") }) {
                    return false
                }

                val intent = Intent(Intent.ACTION_VIEW, uri)
                if (intent.resolveActivity(packageManager) != null) startActivity(intent)
                return true
            }

            override fun onPageStarted(view: android.webkit.WebView, url: String, favicon: Bitmap?) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress   = 0
                updateBottomBarState()
            }

            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                progressBar.visibility    = View.GONE
                swipeRefresh.isRefreshing = false
                android.webkit.CookieManager.getInstance().flush()
                updateBottomBarState()

                view.evaluateJavascript(INJECTED_JS, null)
            }

            override fun onReceivedError(view: android.webkit.WebView, request: android.webkit.WebResourceRequest, error: android.webkit.WebResourceError) {
                if (request.isForMainFrame) view.loadUrl("file:///android_asset/offline.html")
            }

            @SuppressLint("WebViewClientOnReceivedSslError")
            override fun onReceivedSslError(view: android.webkit.WebView, handler: android.webkit.SslErrorHandler, error: android.net.http.SslError) {
                handler.cancel()
            }
        }

        webView.webChromeClient = object : android.webkit.WebChromeClient() {

            override fun onProgressChanged(view: android.webkit.WebView, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress == 100) progressBar.visibility = View.GONE
            }

            override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                val resources = request.resources
                if (resources.contains(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID)) {
                    request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID))
                    return
                }
                if (resources.contains(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE)) {
                    val audioGranted = ContextCompat.checkSelfPermission(
                        this@MainActivity, Manifest.permission.RECORD_AUDIO
                    ) == PackageManager.PERMISSION_GRANTED
                    if (audioGranted) {
                        request.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    } else {
                        pendingAudioPermission = request
                        audioPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
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

            override fun onPermissionRequestCanceled(request: android.webkit.PermissionRequest) {
                if (pendingWebPermission === request) pendingWebPermission = null
            }

            override fun onCreateWindow(
                view: android.webkit.WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message
            ): Boolean {
                val newWebView = android.webkit.WebView(this@MainActivity)
                newWebView.webViewClient = android.webkit.WebViewClient()
                val transport = resultMsg.obj as android.webkit.WebView.WebViewTransport
                transport.webView = newWebView
                resultMsg.sendToTarget()
                return true
            }

            override fun onShowFileChooser(
                view: android.webkit.WebView,
                callback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                params: android.webkit.WebChromeClient.FileChooserParams
            ): Boolean {
                // Cancel any previous pending callback
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback

                val accepts = params.acceptTypes
                    ?.filter { it.isNotBlank() }
                    ?: emptyList()
                val isImageRelated = accepts.isEmpty() || accepts.any {
                    it.startsWith("image") || it == "*/*"
                }
                val mimeType = accepts.firstOrNull()?.trim()?.ifBlank { null } ?: "image/*"

                // Gallery / file picker intent
                val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = mimeType
                    addCategory(Intent.CATEGORY_OPENABLE)
                    if (params.allowMultipleFiles()) putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                }

                // Optionally add camera for image uploads
                val extraIntents = mutableListOf<Intent>()
                if (isImageRelated) {
                    try {
                        val cameraFile = java.io.File.createTempFile(
                            "klasse_cam_", ".jpg", cacheDir
                        )
                        cameraUri = androidx.core.content.FileProvider.getUriForFile(
                            this@MainActivity,
                            "${applicationContext.packageName}.fileprovider",
                            cameraFile
                        )
                        val cameraIntent = Intent(
                            android.provider.MediaStore.ACTION_IMAGE_CAPTURE
                        ).apply {
                            putExtra(android.provider.MediaStore.EXTRA_OUTPUT, cameraUri)
                            addFlags(
                                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                                Intent.FLAG_GRANT_READ_URI_PERMISSION
                            )
                        }
                        extraIntents.add(cameraIntent)
                    } catch (_: Exception) { }
                }

                val chooser = Intent.createChooser(
                    galleryIntent, "Bild / Datei auswählen"
                ).apply {
                    if (extraIntents.isNotEmpty()) {
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, extraIntents.toTypedArray())
                    }
                }

                try {
                    fileChooserLauncher.launch(chooser)
                } catch (_: Exception) {
                    fileChooserCallback?.onReceiveValue(null)
                    fileChooserCallback = null
                    return false
                }
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
        val canGoBack = webView.canGoBack()
        btnBack.alpha   = if (canGoBack) 1.0f else 0.35f
        btnBack.isEnabled = canGoBack
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
        swipeRefresh.isRefreshing = false

        pendingWebPermission?.let { req ->
            val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(
                    this, Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            if (granted) req.grant(req.resources) else req.deny()
            pendingWebPermission = null
        }
        pendingAudioPermission?.let { req ->
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
            if (granted) req.grant(arrayOf(android.webkit.PermissionRequest.RESOURCE_AUDIO_CAPTURE))
            else req.deny()
            pendingAudioPermission = null
        }
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        android.webkit.CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
