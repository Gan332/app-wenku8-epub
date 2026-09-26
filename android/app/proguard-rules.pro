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
-keep class com.example.hyperreader.reader.ReaderActivity { *; }
-keep class com.example.hyperreader.reader.OnlineReaderActivity { *; }
-keep class com.example.hyperreader.service.ExportNotificationService { *; }

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
