package com.zzl.guardian

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.zzl.guardian.ui.theme.ZzlTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * 唯一的 Activity。具体展示控制端还是被控端界面，
 * 由 flavor 源集中的 AppRoot() 决定（src/parent 或 src/child）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ZzlTheme {
                AppRoot()
            }
        }
    }
}
