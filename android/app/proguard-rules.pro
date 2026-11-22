# Keep JavascriptInterface bridge methods exposed to WebView
-keepclassmembers class dev.koduki.xracp.XrHostBridge {
    @android.webkit.JavascriptInterface <methods>;
}
