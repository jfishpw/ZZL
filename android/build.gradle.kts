// 版本矩阵（相互兼容，升级时请整组一起升）
//   AGP 8.7.2  ←→  Kotlin 2.0.21  ←→  KSP 2.0.21-1.0.28  ←→  Hilt 2.52
//   Compose 编译器自 Kotlin 2.0 起由 kotlin.plugin.compose 提供，版本与 Kotlin 对齐
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
    id("com.google.dagger.hilt.android") version "2.52" apply false
}
