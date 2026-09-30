# Preserve useful production crash diagnostics while still allowing shrinking and obfuscation.
-keepattributes SourceFile,LineNumberTable,RuntimeVisibleAnnotations,AnnotationDefault
-renamesourcefileattribute SourceFile

# Cronet conditionally integrates with a Private Compute extension that is not present
# in every public Android SDK. Its implementation guards access at runtime.
-dontwarn android.app.privatecompute.PccSandboxManager

# Methods exposed to the private BotGuard WebView are resolved by name from JavaScript.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
