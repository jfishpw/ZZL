package com.zzl.guardian

import androidx.compose.runtime.Composable
import com.zzl.guardian.parent.ParentHome

/**
 * 控制端入口。被控端在 src/child 源集中提供同名函数，Gradle flavor 会自动选用。
 */
@Composable
fun AppRoot() {
    ParentHome()
}
