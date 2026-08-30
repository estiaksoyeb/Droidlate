package com.droidlate.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.view.WindowCompat
import com.droidlate.app.ui.navigation.AppNavHost
import com.droidlate.app.ui.theme.DroidlateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            DroidlateTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    AppNavHost()
                }
            }
        }
    }
}

