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
import dev.aero.glyphslot.engine.Game
import dev.aero.glyphslot.engine.Reels
import dev.aero.glyphslot.engine.Roulette
import dev.aero.glyphslot.engine.RouletteEngine
import dev.aero.glyphslot.engine.SlotEngine
import dev.aero.glyphslot.engine.Table
import dev.aero.glyphslot.render.MatrixRenderer
import dev.aero.glyphslot.render.RouletteRenderer
import dev.aero.glyphslot.toy.Shaker
import kotlinx.coroutines.delay

/**
 * Préview Compose 25×25 : mêmes moteurs + mêmes renderers que le toy,
 * pour itérer sans retourner le téléphone. La secousse marche ici aussi.
 */
class MainActivity : ComponentActivity() {

    /** Branché par l'écran : la secousse lance le jeu affiché, comme sur le toy. */
    internal var onShake: () -> Unit = {}
    private var shaker: Shaker? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        shaker = Shaker(this) { onShake() }
        setContent { PreviewScreen(this) }
    }

    override fun onResume() {
        super.onResume()
        shaker?.start()
    }

    override fun onPause() {
        shaker?.stop()
        super.onPause()
    }
}

private const val STATUS_IDLE = "Secouer le téléphone pour lancer"

@Composable
private fun PreviewScreen(activity: MainActivity) {
    val table = remember { Table() }
    val slotRenderer = remember { MatrixRenderer() }
    val rouletteRenderer = remember { RouletteRenderer() }
    var frame by remember { mutableStateOf(IntArray(Reels.SIZE * Reels.SIZE)) }
    var status by remember { mutableStateOf(STATUS_IDLE) }
    var busy by remember { mutableStateOf(false) }
    var game by remember { mutableStateOf(table.game) }

    fun now() = System.nanoTime() / 1e9

    LaunchedEffect(Unit) {
        while (true) {
            val now = now()
            frame = when (table.game) {
                Game.SLOT -> {
                    val snap = table.slot.update(now)
                    table.slot.drainEvents().forEach { event ->
                        when (event) {
                            is SlotEngine.Event.ReelStopped ->
                                if (event.index < 2) status = "Rouleau ${event.index + 1} arrêté…"

                            is SlotEngine.Event.SpinFinished -> {
                                slotRenderer.onResult(event.result)
                                status = when (event.result) {
                                    SlotEngine.ResultType.JACKPOT -> "JACKPOT 777"
                                    SlotEngine.ResultType.WIN -> "Trois symboles identiques"
                                    SlotEngine.ResultType.LOSE -> "Pas de combinaison. Relance !"
                                }
                            }

                            SlotEngine.Event.ResultEnded -> Unit
                        }
                    }
                    slotRenderer.render(snap).copyOf()
                }

                Game.ROULETTE -> {
                    val view = table.roulette.update(now)
                    table.roulette.drainEvents().forEach { event ->
                        status = when (event) {
                            is RouletteEngine.Event.Bounce -> "Rebond ${event.n}…"
                            is RouletteEngine.Event.Landed -> {
                                val color = when (event.color) {
                                    Roulette.Color.RED -> "rouge"
                                    Roulette.Color.BLACK -> "noir"
                                    Roulette.Color.ZERO -> "zéro"
                                }
                                "${event.result} $color, après ${event.hops} rebond" +
                                    if (event.hops > 1) "s" else ""
                            }
                        }
                    }
                    rouletteRenderer.render(view).copyOf()
                }
            }
            busy = !table.ready(now)
            delay(33)
        }
    }

    fun launch() {
        if (table.launch(now())) status = "Lancement…"
    }

    fun forceSlot(force: SlotEngine.ResultType) {
        if (table.slot.spin(now(), force)) status = "Lancement…"
    }

    fun forceRoulette(force: Roulette.Color) {
        if (table.roulette.launch(now(), force)) status = "Lancement…"
    }

    fun switchGame() {
        if (table.switch(now())) {
            game = table.game
            status = STATUS_IDLE
        }
    }

    activity.onShake = { launch() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0A0B))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (game == Game.SLOT) "GLYPH SLOT" else "GLYPH SLOT · ROULETTE",
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
            DemoButton("Secouer", enabled = !busy) { launch() }
            // l'appui long du toy : changer de jeu
            DemoButton(if (game == Game.SLOT) "Roulette" else "Machine à sous", enabled = !busy) {
                switchGame()
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (game == Game.SLOT) {
                DemoButton("Forcer ×3", enabled = !busy) { forceSlot(SlotEngine.ResultType.WIN) }
                DemoButton("Forcer 777", enabled = !busy) { forceSlot(SlotEngine.ResultType.JACKPOT) }
            } else {
                DemoButton("Rouge", enabled = !busy) { forceRoulette(Roulette.Color.RED) }
                DemoButton("Noir", enabled = !busy) { forceRoulette(Roulette.Color.BLACK) }
                DemoButton("Zéro", enabled = !busy) { forceRoulette(Roulette.Color.ZERO) }
            }
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
