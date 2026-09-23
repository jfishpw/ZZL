import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("com.google.dagger.hilt.android")
}

/** 读取 gradle.properties 中的配置项，缺省时回退 */
val prop: (String, String) -> String = { key, fallback ->
    (project.findProperty(key) as String?)?.takeIf { it.isNotBlank() } ?: fallback
}

/**
 * 是否开启 R8 混淆与资源压缩。
 *
 * **默认关闭**，理由是风险与收益不对称：
 *  - 收益：抬高反编译门槛。但本 App 以侧载方式安装、不进应用商店，
 *    真实威胁是"孩子取消设备管理器激活"，而不是"有人逆向 APK"——
 *    混淆对前者毫无帮助。
 *  - 风险：序列化、Retrofit 接口、WorkManager 反射实例化等失效**都在运行时**，
 *    编译期一律发现不了。本项目无法在开发阶段做真机验证，
 *    不适合用唯一一次真机机会去赌一个低收益特性。
 *
 * 规则已在 `app/proguard-rules.pro` 中写全并实跑通过，需要时可一键开启：
 *   node scripts/gradle-run.cjs "<jdk17>" :app:assembleChildRelease -PenableMinify=true
 *
 * 开启后请务必在真机上验证：配对、策略下发、拦截、保活、离线密码、截屏六条链路。
 */
val enableMinify: Boolean = (project.findProperty("enableMinify") as String?)?.toBoolean() ?: false

/** 签名信息从 local.properties 读取，绝不写进版本库 */
val localProps = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val releaseStoreFile: String? = localProps.getProperty("RELEASE_STORE_FILE")

/**
 * 编译期服务器默认值的读取顺序：**local.properties（本机私有）→ gradle.properties**。
 *
 * 这样做的原因：`gradle.properties` 会随代码库一起分享，所以里面只放 RFC 5737 示例地址；
 * 真实服务器地址写进 `local.properties`（已在 .gitignore 中），开发者自己打的包照旧指向真实地址。
 * 运行期仍可在 App 的「服务器设置」里覆盖，二者互不影响。
 */
val serverProp: (String, String) -> String = { key, fallback ->
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: prop(key, fallback)
}

android {
    namespace = "com.zzl.guardian"
    // 本机 Android SDK 已安装的最高平台为 android-34。
    // 若要用 35，先在 SDK Manager 中安装 "Android 15 (API 35)" 再改这里，一行即可。
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        vectorDrawables { useSupportLibrary = true }

        // 服务器地址：编译期默认值（最低优先级，可在 App 内运行期覆盖）
        // 取值顺序：local.properties（本机私有，不入库）→ gradle.properties（已脱敏为示例地址）
        buildConfigField("String", "DEFAULT_SERVER_HOST", "\"${serverProp("SERVER_HOST", "203.0.113.10")}\"")
        buildConfigField("int", "DEFAULT_SERVER_PORT", serverProp("SERVER_PORT", "8111"))
        buildConfigField("String", "DEFAULT_SERVER_SCHEME", "\"${serverProp("SERVER_SCHEME", "http")}\"")
    }

    /**
     * 双角色：同一套代码库，通过 productFlavor 产出两个独立 App。
     *   parentDebug / parentRelease  → 控制端（家长手机）
     *   childDebug  / childRelease   → 被控端（儿童平板）
     * 角色专属代码分别放在 src/parent 与 src/child 源集。
     */
    flavorDimensions += "role"
    productFlavors {
        create("parent") {
            dimension = "role"
            applicationId = "com.zzl.guardian.parent"
            versionNameSuffix = "-parent"
            buildConfigField("String", "APP_ROLE", "\"parent\"")
            resValue("string", "app_name", "掌中灵·控制端")
        }
        create("child") {
            dimension = "role"
            applicationId = "com.zzl.guardian.child"
            versionNameSuffix = "-child"
            buildConfigField("String", "APP_ROLE", "\"child\"")
            resValue("string", "app_name", "掌中灵")
        }
    }

    signingConfigs {
        // 只在 local.properties 中配置了 keystore 时才创建签名配置，
        // 避免未签名环境下 assembleRelease 因空配置报错
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFile)
                storePassword = localProps.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProps.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProps.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // 开关见文件顶部 enableMinify 的说明（默认关闭）
            isMinifyEnabled = enableMinify
            isShrinkResources = enableMinify
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseStoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    /**
     * JVM 单元测试的编码设置。
     *
     * ⚠️ **这组参数并没有解决下面描述的问题**，保留它只是因为对测试环境本身是合理的
     * （让 fork 出来的测试 JVM 也用 UTF-8，避免断言里的中文比较出现意外）。
     *
     * --- 真正的原因与结论（实测，2026-09）---
     *
     * 本项目路径含中文（`D:\pwg\监管软件`）。在这个路径下跑单元测试，会出现：
     *   - 测试类**已经编译出来了**（`build/tmp/kotlin-classes/childDebugUnitTest/` 下能看到 .class）
     *   - Gradle 也正确列出了 6 个测试类名
     *   - 但 fork 出来的测试 worker 执行 `Class.forName` 时抛 `ClassNotFoundException`
     *
     * 也就是说：问题出在**传给测试 worker 的 classpath**，中文路径在其中被错误解码，
     * 而不是测试代码或编译产物本身。给测试 JVM 加 `-Dfile.encoding` / `-Dsun.jnu.encoding`
     * 都无效，因为故障发生在 classpath 解析阶段，早于这些属性生效。
     *
     * **结论：单元测试必须在 ASCII 路径下运行。** 本项目已有的 ASCII 目录联接
     * `D:\pwg\zzl-build`（指向 android/）正是为此存在：
     *
     *     GRADLE_CWD="D:/pwg/zzl-build" node scripts/gradle-run.cjs "<jdk>" :app:testChildDebugUnitTest
     *
     * 注意这与 `assembleRelease` 的要求**正好相反** —— 打包必须用真实路径
     * （命令沙箱不解析目录联接，会拒绝联接路径下的写入）。两个任务各用各的路径，
     * 详见 README「非 ASCII 路径限制」与「构建报拒绝访问的两个根因」。
     */
    testOptions {
        unitTests.all {
            it.jvmArgs(
                "-Dfile.encoding=UTF-8",
                "-Dsun.jnu.encoding=UTF-8",
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 生命周期 / ViewModel
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Hilt
    implementation("com.google.dagger:hilt-android:2.52")
    ksp("com.google.dagger:hilt-compiler:2.52")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // DataStore（服务器配置 + 令牌持久化）
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Room（被控端本地库：策略缓存、使用会话、拦截日志）
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 网络
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.jakewharton.retrofit:retrofit2-kotlinx-serialization-converter:1.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // 保活兜底（M5）：前台服务被系统清掉后，靠周期任务重新拉起
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.hilt:hilt-work:1.2.0")
    ksp("androidx.hilt:hilt-compiler:1.2.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(composeBom)
}
