# 默认 ProGuard 规则
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep public class * extends android.app.Service
-keep public class * extends android.app.Activity
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}