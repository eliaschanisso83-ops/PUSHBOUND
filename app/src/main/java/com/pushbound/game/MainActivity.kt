package com.pushbound.game

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.pushbound.game.ui.PushboundApp
import com.pushbound.game.ui.PushboundTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            PushboundTheme {
                // The root owns navigation: Main Menu → Campaign → World →
                // Level → Game, plus the daily-puzzle entry.
                PushboundApp()
            }
        }
    }
}
