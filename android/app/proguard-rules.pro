# Keep JavascriptInterface bridge methods exposed to WebView
-keepclassmembers class dev.koduki.xreditor.BootHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}
