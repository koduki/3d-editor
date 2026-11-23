# Keep JavascriptInterface bridge methods exposed to WebView
-keepclassmembers class dev.koduki.kucode.BootHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}
