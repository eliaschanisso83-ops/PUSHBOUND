package com.pushbound.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pushbound.game.Levels

/**
 * Mobile-first composition: subtle level/move header, the board taking the
 * majority of the screen, and the controls anchored at the bottom within thumb
 * reach. System bars are respected via edge-to-edge insets.
 */
@Composable
fun GameScreen() {
    val scope = rememberCoroutineScope()
    val controller = remember(scope) { GameController(Levels.first, scope) }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(Modifier.fillMaxSize()) {
            LevelHeader(controller, Modifier.fillMaxWidth())
            BoardView(
                controller,
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            ControlsBar(controller)
        }
        CompletionOverlay(controller)
    }
}

@Composable
private fun LevelHeader(controller: GameController, modifier: Modifier) {
    Row(
        modifier
            .statusBarsPadding()
            .padding(horizontal = 22.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = controller.level.name.uppercase(),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.55f),
            fontSize = 13.sp,
            letterSpacing = 2.5.sp
        )
        Text(
            text = "MOVES ${controller.state.moves}",
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = 1.5.sp
        )
    }
}

/**
 * Minimal completion state. Continue simply restarts the level until a real
 * level sequence exists (milestone 3, section 10).
 */
@Composable
private fun CompletionOverlay(controller: GameController) {
    if (!controller.isComplete) return

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0x992B2622))
            .pointerInput(Unit) { detectTapGestures { } }, // keep controls under the scrim inert
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFFF1EADD),
            shadowElevation = 10.dp
        ) {
            Column(
                Modifier.padding(horizontal = 36.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Level Complete",
                    color = Color(0xFF2B2622),
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(18.dp))
                Button(
                    onClick = controller::restart,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF2B2622),
                        contentColor = Color(0xFFF3EDE2)
                    )
                ) {
                    Text(text = "Continue", fontSize = 15.sp, letterSpacing = 1.sp)
                }
            }
        }
    }
}
