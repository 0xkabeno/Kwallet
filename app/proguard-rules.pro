# Keep the JavaScript bridge methods
-keepclassmembers class app.kwallet.SignActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class app.kwallet.HarkNative {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
