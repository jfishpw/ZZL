package com.zzl.guardian

import androidx.compose.runtime.Composable
import com.zzl.guardian.child.ChildHome

/**
 * 被控端入口。控制端在 src/parent 源集中提供同名函数，Gradle flavor 会自动选用。
 */
@Composable
fun AppRoot() {
    ChildHome()
}
