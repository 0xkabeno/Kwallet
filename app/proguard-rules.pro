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

# v3.99.50: BouncyCastle (TextAuth engine: Ed25519 + secp256k1); only the referenced classes stay
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**
