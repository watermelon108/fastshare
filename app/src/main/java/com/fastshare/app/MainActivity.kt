package com.fastshare.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Hub.start(applicationContext)
        ContextCompat.startForegroundService(this, Intent(this, FastShareService::class.java))
        setContent {
            FastShareTheme {
                FastShareApp()
            }
        }
    }
}
