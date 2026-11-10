# 保留插件工作进程入口、序列化协议和依赖中通过反射使用的实现
-keep class io.kaffubox.core.** { *; }
-keep class io.kaffubox.desktop.DesktopMainKt { public static void main(java.lang.String[]); }
-keep class org.mozilla.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-keep class com.drew.** { *; }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
# 保留离屏渲染入口，以便直接验证发布依赖而不创建桌面窗口
-keep class androidx.compose.ui.ImageComposeScene { *; }
-keep class androidx.compose.ui.semantics.** { *; }
-keep class io.kaffubox.app.AppController { *; }
# jsoup 的可选 RE2/J 引擎未启用，默认使用 JDK 正则实现
-dontwarn org.jsoup.helper.Re2jRegex**
# VarHandle 的签名多态调用由 JVM 解析
-dontwarn org.mozilla.javascript.SlotMapOwner$ThreadedAccess
