# =============================================================================
# 掌中灵 — R8 / ProGuard 规则
# =============================================================================
#
# 【重要】release 构建默认 **不开启** 混淆，本文件是「一旦开启即可直接使用」的
# 完整规则集，已通过 `-PenableMinify=true` 实跑构建验证。
#
# 为什么默认关闭：见 app/build.gradle.kts 中 enableMinify 处的说明。
# 简要理由 —— 本 App 以侧载方式安装、不进应用商店，混淆带来的收益
# （提高反编译门槛）远小于它的风险（序列化/反射在运行时静默失效），
# 而这类失效无法靠编译发现、只能在真机上暴露。
#
# 如需开启：
#   node scripts/gradle-run.cjs "<jdk17>" :app:assembleChildRelease -PenableMinify=true
#
# =============================================================================


# ---------------------------------------------------------------- 通用
# kotlinx.serialization 依赖运行时可见注解；InnerClasses 供反射读取嵌套类
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, EnclosingMethod

# 保留行号与源文件名：崩溃栈才能定位到具体行。
# 不保留的话线上崩溃只有类名和方法名，排查成本极高，而体积收益不到 1%。
-keepattributes SourceFile, LineNumberTable
-renamesourcefileattribute SourceFile

# 不要优化掉日志调用（Log.i / Log.w 里带 TAG 与状态，是现场唯一线索）
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
}


# ---------------------------------------------------------------- 1. kotlinx.serialization
# 库自带 consumer 规则，这里显式再写一遍作为保险。
# 值得庆幸的是 kotlinx.serialization 的序列化器是编译期生成的，
# JSON 字段名以字符串常量形式硬编码在 serializer 中（而非反射读取属性名），
# 因此**混淆属性名不会破坏 JSON 映射** —— 这是它优于 Gson 的关键点。
-keepclassmembers class **$$serializer {
    *** descriptor;
}
-keepclasseswithmembers class com.zzl.guardian.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers class com.zzl.guardian.** {
    *** Companion;
}
-keepclasseswithmembers class com.zzl.guardian.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**


# ---------------------------------------------------------------- 2. Retrofit / OkHttp
# Retrofit 通过反射读取接口方法上的注解来组装请求，
# 接口方法签名一旦被改，运行时才会抛 "Unable to create call adapter"。
-keep,allowobfuscation interface com.zzl.guardian.data.api.** {
    *;
}
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn retrofit2.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**


# ---------------------------------------------------------------- 3. Hilt / Dagger
# Hilt 生成的组件与注入器由框架按类型查找，必须保留。
# 特别注意 GuardEntryPoint：无障碍服务与前台服务由系统实例化，
# 只能经 EntryPointAccessors 取依赖，这是本项目唯一的 Hilt 反射入口。
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-keep class * extends dagger.hilt.android.internal.managers.ComponentSupplier { *; }
-keep @dagger.hilt.EntryPoint interface * { *; }
-keepclassmembers,allowobfuscation class * {
    @javax.inject.* <fields>;
    @javax.inject.* <init>(...);
}
-dontwarn dagger.hilt.**


# ---------------------------------------------------------------- 4. Room
# Room 实体与 DAO 的字段访问由编译期生成的 _Impl 完成，不依赖反射，
# 但 schema 的 identityHash 与类名相关：混淆映射变化会导致 hash 变化。
# 本项目 Room 库只存可重建的缓存（真相在服务端），
# 升级时走 fallbackToDestructiveMigration 重建即可，故这里只做基本保留。
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao interface * { *; }
-dontwarn androidx.room.paging.**


# ---------------------------------------------------------------- 5. WorkManager（★ 本项目最关键的一条）
# WorkManager 把工作请求持久化到自己的数据库，只存 **Worker 类的全限定名字符串**，
# 恢复时用 Class.forName 反射实例化，并查找 (Context, WorkerParameters) 构造函数。
#
# 被混淆的后果是**静默失效**：类名变了或构造函数签名变了，
# WorkManager 只会记一条 warning 日志，任务从此不再执行 ——
# 而本项目拿它做保活兜底（GuardKeepaliveWorker），
# 失效意味着"厂商 ROM 杀掉进程后，设备再也回不来"，
# 且家长在控制端看不出任何异常（设备只是"离线"）。
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class com.zzl.guardian.child.keepalive.GuardKeepaliveWorker { *; }
-keep class androidx.work.** { *; }
-dontwarn androidx.work.**


# ---------------------------------------------------------------- 6. Android 组件
# manifest 中声明的组件由 AGP 依据 aapt_rules.txt 自动保留，
# 这里补充的是"系统按约定回调"的方法，被裁掉会导致回调静默不触发。
-keepclassmembers class * extends android.content.BroadcastReceiver {
    public void onReceive(android.content.Context, android.content.Intent);
}
-keepclassmembers class * extends android.content.ContentProvider {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclassmembers class * extends android.app.Service {
    public <init>(android.content.Context, android.util.AttributeSet);
    public void onStartCommand(android.content.Intent, int, int);
    public android.os.IBinder onBind(android.content.Intent);
}
-keepclassmembers class * extends android.accessibilityservice.AccessibilityService {
    public void onAccessibilityEvent(android.view.accessibility.AccessibilityEvent);
    public void onInterrupt();
}
-keepclassmembers class * extends android.app.admin.DeviceAdminReceiver {
    public void onDisabled(android.content.Context, android.content.Intent);
    public void onEnabled(android.content.Context, android.content.Intent);
}


# ---------------------------------------------------------------- 7. 枚举与 Parcelable
# 枚举的 values() / valueOf() 在某些 ROM 与反射路径下会被调用（如整数转枚举）
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}


# ---------------------------------------------------------------- 8. Kotlin
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}


# ---------------------------------------------------------------- 9. 其它
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**
-dontwarn java.lang.instrument.**
