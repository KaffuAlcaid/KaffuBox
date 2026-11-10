# 保留序列化入口、隔离脚本运行时与文档解析中的反射访问
-keep class io.kaffubox.core.** { *; }
-keep class org.mozilla.** { *; }
-keep class org.yaml.snakeyaml.** { *; }
-keep class com.drew.** { *; }
-keep class **$$serializer { *; }
-keepclassmembers class **$Companion { kotlinx.serialization.KSerializer serializer(...); }
-keepattributes Signature,InnerClasses,EnclosingMethod,*Annotation*
-dontobfuscate
-dontwarn org.jsoup.helper.Re2jRegex**
-dontwarn org.mozilla.javascript.SlotMapOwner$ThreadedAccess
# Android 脚本使用解释模式，不调用 JVM 字节码优化器
-dontwarn jdk.dynalink.**
# 文档输入仅解析安全的数据类型，不使用桌面 JavaBean 反射
-dontwarn java.beans.BeanDescriptor
-dontwarn java.beans.BeanInfo
-dontwarn java.beans.FeatureDescriptor
-dontwarn java.beans.IntrospectionException
-dontwarn java.beans.Introspector
-dontwarn java.beans.PropertyDescriptor
