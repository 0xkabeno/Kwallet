# Keep the JavaScript bridge methods
-keepclassmembers class app.hwallet.HarkNative {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class app.hwallet.HwActivity$HwBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
