# Keep the JavaScript bridge methods
-keepclassmembers class app.hwallet.HarkNative {
    @android.webkit.JavascriptInterface <methods>;
}
-keepclassmembers class app.hwallet.HwActivity$HwBridge {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface

# v1.6 (A2): R8 is on for release. The bridge above keeps its @JavascriptInterface methods; everything else,
# including the generated key holders (HwSa, HwSb) and HwKeys, is shrunk and renamed.
# No logging in release builds (nothing that could ever carry a provider key reaches logcat).
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
    public static int w(...);
    public static int e(...);
}
# OkHttp ships its own rules; silence optional-platform warnings
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
