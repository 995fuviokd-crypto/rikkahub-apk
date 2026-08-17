# RikkaHub R8 / ProGuard 规则
#
# 策略：
# - debug 与 release 均启用 R8：收缩 (Shrink) + 优化 (Optimize) + 混淆 (Obfuscate)
# - R8 默认开启全部优化，包括接口合并/简化 (interface merging & hoisting)、
#   类/方法内联、未使用代码消除，可避免多重继承导致的方法数膨胀
# - androidx / compose / okhttp / retrofit / ktor / coil / firebase 等官方库自带
#   consumer rules，由 AGP 自动应用，无需在此重复 keep
# - 此处只补充：项目自身、无 consumer rules 的三方库、以及反射访问点

# ---------------------------------------------------------------------------
# 应用自身（保留类名便于异常堆栈定位；成员交由 R8 收缩与混淆）
# ---------------------------------------------------------------------------
-keep public class me.rerere.rikkahub.**

# ---------------------------------------------------------------------------
# Android 组件入口（Manifest 按类名引用）
# ---------------------------------------------------------------------------
-keep public class * extends android.app.Activity
-keep public class * extends android.app.Service
-keep public class * extends android.app.Application
-keep public class * extends android.content.BroadcastReceiver
-keep public class * extends android.content.ContentProvider
-keep public class * extends androidx.room.RoomDatabase

# WebView 注入接口
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# kotlinx.serialization（编译器生成 serializer，按字符串类名查找）
# ---------------------------------------------------------------------------
-keepattributes *Annotation*, InnerClasses
-keep,includedescriptorclasses class **$$serializer { *; }
-keepclassmembers class * {
    **$serializer INSTANCE;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * implements kotlinx.serialization.KSerializer {
    public static final kotlinx.serialization.KSerializer INSTANCE;
}
-dontwarn kotlinx.serialization.**

# ---------------------------------------------------------------------------
# Room（KSP 生成 _Impl，按类名查找）
# ---------------------------------------------------------------------------
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao class *
-keep @androidx.room.Database class *
-keepclassmembers class * {
    @androidx.room.* <methods>;
}
-dontwarn androidx.room.**

# ---------------------------------------------------------------------------
# Kotlin 反射（kotlin-reflect 为显式依赖，保留全部 API）
# ---------------------------------------------------------------------------
-keep class kotlin.reflect.** { *; }
-keep class kotlin.jvm.internal.** { *; }
-dontwarn kotlin.reflect.**

# ---------------------------------------------------------------------------
# Retrofit（动态代理接口 + 协程挂起函数签名）
# ---------------------------------------------------------------------------
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontwarn retrofit2.**

# ---------------------------------------------------------------------------
# MCP SDK（动态注册 tool / 传输层反射）
# ---------------------------------------------------------------------------
-keep class io.modelcontextprotocol.** { *; }
-dontwarn io.modelcontextprotocol.**

# ---------------------------------------------------------------------------
# jlatexmath（字体/解析按名称反射）
# ---------------------------------------------------------------------------
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# ---------------------------------------------------------------------------
# jmDNS / SQLite / SLF4J（无官方 consumer rules 或反射敏感）
# ---------------------------------------------------------------------------
-keep class javax.jmdns.** { *; }
-dontwarn javax.jmdns.**
-keep class org.sqlite.** { *; }
-dontwarn org.sqlite.**
-keep class org.slf4j.** { *; }
-dontwarn org.slf4j.**

# ---------------------------------------------------------------------------
# 杂项（可能无 consumer rules 的 Compose 第三方库，仅保类名）
# ---------------------------------------------------------------------------
-keep class com.jvziyaoyao.scale.** { *; }
-keep class io.github.dokar3.** { *; }
-keep class com.github.rikkahub.** { *; }

# ---------------------------------------------------------------------------
# 资源收缩提示：部分库通过资源 id 反射访问
# ---------------------------------------------------------------------------
-keepclassmembers class **.R$* {
    public static <fields>;
}

# 需要源文件名/行号以支持堆栈追踪映射
-keepattributes SourceFile, LineNumberTable
