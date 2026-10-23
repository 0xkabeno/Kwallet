# Keep the JavaScript bridge methods
-keepclassmembers class app.kwallet.SignActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class app.kwallet.HarkNative {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# v3.99.14: window.KwScanN (QR air-gap scanner bridge)
-keepclassmembers class app.kwallet.KwScanBridge {
    @android.webkit.JavascriptInterface <methods>;
}
