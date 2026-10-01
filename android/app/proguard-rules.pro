# HyperReader R8 规则
# 没有这些规则，release 构建会在运行期崩（与之前 material3 的 NoSuchMethodError 同类问题）：
# 反射/生成代码被裁掉后，编译期能过、运行期链接失败。

# ---- kotlinx.serialization ----
# 生成的 serializer 通过 companion 反射实例化，@Serializable 类必须保活。
-keepattributes *Annotation*, InnerClasses, Signature, RuntimeVisible*Annotations, AnnotationDefault
-keepclassmembers class com.example.hyperreader.** {
    *** Companion;
}
-keepclasseswithmembers class com.example.hyperreader.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.hyperreader.**$$serializer { *; }
-keep class kotlinx.serialization.json.** { *; }

# ---- Activity / Service 由系统按类名实例化 ----
-keep class com.example.hyperreader.Wenku8Application { *; }
-keep class com.example.hyperreader.MainActivity { *; }
-keep class com.example.hyperreader.auth.** { *; }
-keep class com.example.hyperreader.reader.XyReaderActivity { *; }
-keep class com.example.hyperreader.reader.OnlineReaderActivity { *; }
-keep class com.example.hyperreader.service.ExportNotificationService { *; }

# ---- Rust JNI 入口（libepub_core.so 按类名+方法名查找 native 符号）----
-keep class com.example.hyperreader.reader.EpubNative { *; }

# ---- 反射读取的 DTO ----
# CoreSmokeTest 里的 transferModelTypesCannotHoldCredentialsOrCollections
# 会遍历 declaredFields，字段被裁掉会让安全断言失去意义。
-keepclassmembers class com.example.hyperreader.settings.ConfigTransfer$* { *; }
-keepclassmembers class com.example.hyperreader.model.** { *; }

# ---- 第三方 ----
# JSoup 大量使用反射读取标签/属性
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**
# OkHttp 在低版本 JDK 上引用了可选的 conscrypt/bouncycastle
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
# MiuiX 依赖 Compose，部分符号在无引用时会被误判
-dontwarn top.yukonga.miuix.kmp.**

# ---- xy-reader 阅读器引入（0.13.0）----
# 压缩包解析库大量使用反射/服务加载（commons-compress 的归档器探测、junrar 的
# UnRAR 解码器），整体保活避免被裁成运行期 NoClassDefFoundError。
-keep class com.xyreader.** { *; }
-keep class org.apache.commons.compress.** { *; }
-keep class com.github.junrar.** { *; }
-dontwarn org.apache.commons.compress.**
-dontwarn com.github.junrar.**
# junrar 的日志门面是可选的，未引入 slf4j 实现
-dontwarn org.slf4j.**
