package com.whmdg.mczj.tools

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.whmdg.mczj.tools.ui.theme.工具箱Theme
import com.whmdg.mczj.tools.ui.MainAppContainer

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            工具箱Theme {
                MainAppContainer()
            }
        }
    }
}