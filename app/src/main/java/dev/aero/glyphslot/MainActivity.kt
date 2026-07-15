package dev.aero.glyphslot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.SlotEngine
import dev.aero.glyphslot.render.MatrixRenderer
import kotlinx.coroutines.delay

/**
 * Préview Compose 25×25 : même moteur + même renderer que le toy,
 * pour itérer sans retourner le téléphone.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PreviewScreen() }
    }
}

private const val STATUS_IDLE = "Appui long sur le bouton Glyph pour lancer"

@Composable
private fun PreviewScreen() {
    val engine = remember { SlotEngine() }
    val renderer = remember { MatrixRenderer() }
    var frame by remember { mutableStateOf(IntArray(Reels.SIZE * Reels.SIZE)) }
    var status by remember { mutableStateOf(STATUS_IDLE) }
    var busy by remember { mutableStateOf(false) }

    fun now() = System.nanoTime() / 1e9

    LaunchedEffect(Unit) {
        while (true) {
            val snap = engine.update(now())
            engine.drainEvents().forEach { event ->
                when (event) {
                    is SlotEngine.Event.ReelStopped ->
                        if (event.index < 2) status = "Rouleau ${event.index + 1} arrêté…"

                    is SlotEngine.Event.SpinFinished -> {
                        renderer.onResult(event.result)
                        status = when (event.result) {
                            SlotEngine.ResultType.JACKPOT -> "JACKPOT 777 — gros effet de victoire"
                            SlotEngine.ResultType.WIN -> "3 symboles identiques — effet de victoire"
                            SlotEngine.ResultType.LOSE -> "Pas de combinaison. Relance !"
                        }
                    }

                    SlotEngine.Event.ResultEnded -> {
                        busy = false
                        status = STATUS_IDLE
                    }
                }
            }
            frame = renderer.render(snap).copyOf()
            delay(33)
        }
    }

    fun launch(force: SlotEngine.ResultType?) {
        if (engine.spin(now(), force)) {
            busy = true
            status = "Lancement — les rouleaux défilent…"
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0B))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "GLYPH SLOT",
            color = Color(0xFFE8E8E4),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            letterSpacing = 6.sp,
        )
        Spacer(Modifier.height(24.dp))

        MatrixPreview(frame)

        Spacer(Modifier.height(20.dp))
        Text(
            text = status,
            color = if ("JACKPOT" in status) Color(0xFFD71921) else Color(0xFFB8B8BE),
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.height(20.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DemoButton("Tirage", enabled = !busy) { launch(null) }
            DemoButton("Forcer ×3", enabled = !busy) { launch(SlotEngine.ResultType.WIN) }
            DemoButton("Forcer 777", enabled = !busy) { launch(SlotEngine.ResultType.JACKPOT) }
        }
    }
}

@Composable
private fun MatrixPreview(frame: IntArray) {
    Canvas(
        modifier = Modifier
            .size(320.dp)
            .background(Color(0xFF0B0B0D), CircleShape)
    ) {
        val cell = size.width / Reels.SIZE
        val led = cell * 0.66f
        val inset = (cell - led) / 2
        for (y in 0 until Reels.SIZE) {
            for (x in 0 until Reels.SIZE) {
                val dx = x - Reels.CENTER
                val dy = y - Reels.CENTER
                if (dx * dx + dy * dy > Reels.RADIUS * Reels.RADIUS) continue
                val topLeft = Offset(x * cell + inset, y * cell + inset)
                drawRect(
                    color = Color.White.copy(alpha = 0.05f),
                    topLeft = topLeft,
                    size = Size(led, led),
                )
                val v = frame[y * Reels.SIZE + x]
                if (v > 5) {
                    drawRect(
                        color = Color(0xFFF8F8F4).copy(alpha = v / 255f),
                        topLeft = topLeft,
                        size = Size(led, led),
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = Color(0xFF1E1E22),
            contentColor = Color(0xFF9C9CA3),
            disabledContainerColor = Color(0xFF141416),
            disabledContentColor = Color(0xFF55555C),
        ),
    ) {
        Text(label, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    }
}
