# Keep WebView JavaScript interface if added in future
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep app classes
-keep class com.klasse.app.** { *; }
