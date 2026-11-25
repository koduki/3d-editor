# Retain runtime annotations (required for @JavascriptInterface) and readable crash stack traces
-keepattributes *Annotation*,SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Keep JavascriptInterface bridge class public and retain exact method names & annotations
-keep public class dev.koduki.kucode.BootHostBridge {
    @android.webkit.JavascriptInterface public <methods>;
}
