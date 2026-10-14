package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * "Reading the boards" in How to play: what the frames, cards, rings and square colours around the
 * boards mean, each beside a small drawing of it in the game's colours.
 */
@Composable
fun BoardLegend() {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Along each timeline, boards take turns: one where White moves, then one where Black " +
                "moves. A board's frame shows whose it is, and your pieces can only land on boards " +
                "with your own frame colour.",
            style = MaterialTheme.typography.bodyMedium,
        )
        LegendRow("Your turn, must play", "Raised and ringed in gold: move here before you can submit.") {
            MiniBoard(Palette.whiteBoardFrame, ring = Palette.gold, ringWidth = 3f)
        }
        LegendRow("Your turn, may play", "Raised with a thin ring: you can move here this turn, but don't have to.") {
            MiniBoard(Palette.whiteBoardFrame, ring = Palette.presentEdge, ringWidth = 1.5f)
        }
        LegendRow("White's board", "Pale frame: White moves on it. Flat boards are the past, or wait for the other player.") {
            MiniBoard(Palette.whiteBoardFrame)
        }
        LegendRow("Black's board", "Near-black frame: Black moves on it.") {
            MiniBoard(Palette.blackBoardFrame)
        }
        LegendRow(
            "Faded board",
            "While a piece is selected, a board it can't land on: one of your opponent's. Otherwise, a " +
                "board on an inactive timeline, which doesn't hold back the present.",
        ) {
            MiniBoard(Palette.whiteBoardFrame, faded = true)
        }
        LegendRow(
            "Where you can move",
            "Teal: the selected piece, and the squares it can go to, with a dot (or a ring for a " +
                "capture). They can be on other boards too, in the past or on other timelines.",
        ) {
            Squares {
                square(0, Palette.lightSquare, Palette.selection)
                square(1, Palette.darkSquare, Palette.target)
                drawCircle(Palette.targetDot, size.width * 0.07f, center(1))
            }
        }
        LegendRow("Last moves", "Yellow: where the last turn's moves started and ended on a board.") {
            Squares {
                square(0, Palette.lightSquare, Palette.lastMove)
                square(1, Palette.darkSquare, Palette.lastMove)
            }
        }
        LegendRow("Moves between boards", "Lavender squares and arrow: a move through time or across timelines.") {
            Squares {
                square(0, Palette.lightSquare, Palette.travel)
                square(1, Palette.darkSquare, Palette.travel)
                arrow(Palette.travelArrow)
            }
        }
        LegendRow(
            "King in danger",
            "Red: a king that could be captured, and the attacker's arrow. A red ! marks the timeline " +
                "the attack comes from. You can't submit a turn that leaves one.",
        ) {
            Squares {
                square(0, Palette.darkSquare, null)
                square(1, Palette.lightSquare, Palette.threat.copy(alpha = 0.45f))
                arrow(Palette.threat)
            }
        }
        LegendRow("Timeline labels", "On the left edge: gold where you still have to move, red where a king is in danger.") {
            Box(
                Modifier
                    .background(Palette.card, RoundedCornerShape(50))
                    .border(2.dp, Palette.gold, RoundedCornerShape(50))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            ) {
                Text("L+1", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun LegendRow(name: String, text: String, sample: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(64.dp), contentAlignment = Alignment.Center) { sample() }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.labelLarge)
            Text(text, style = MaterialTheme.typography.bodySmall, color = Palette.inkSoft)
        }
    }
}

/** A tiny 4×4 board in [frame], raised on a card with a [ring] if it has one, or [faded]. */
@Composable
private fun MiniBoard(frame: Color, ring: Color? = null, ringWidth: Float = 0f, faded: Boolean = false) {
    Canvas(Modifier.size(52.dp)) {
        val unit = size.width / 52f
        var boardInset = 10f * unit
        if (ring != null) {
            val radius = CornerRadius(8f * unit)
            drawRoundRect(Palette.slab, Offset(3f * unit, 4f * unit), Size(size.width - 4f * unit, size.height - 4f * unit), radius)
            drawRoundRect(Palette.card, Offset.Zero, Size(size.width - 4f * unit, size.height - 4f * unit), radius)
            drawRoundRect(
                ring,
                Offset(2f * unit, 2f * unit),
                Size(size.width - 8f * unit, size.height - 8f * unit),
                CornerRadius(7f * unit),
                style = Stroke(ringWidth * unit),
            )
            boardInset = 9f * unit
        }
        val side = size.width - 4f * unit - 2 * boardInset
        val topLeft = Offset(boardInset, boardInset)
        if (ring == null) drawRect(Palette.slab, topLeft + Offset(2f * unit, 3f * unit), Size(side, side))
        drawRect(frame, topLeft, Size(side, side))
        val inner = side - 6f * unit
        val square = inner / 4
        for (x in 0 until 4) for (y in 0 until 4) {
            drawRect(
                if ((x + y) % 2 == 0) Palette.lightSquare else Palette.darkSquare,
                topLeft + Offset(3f * unit + x * square, 3f * unit + y * square),
                Size(square, square),
            )
        }
        if (faded) drawRect(Palette.floor.copy(alpha = 0.6f), topLeft, Size(side, side))
    }
}

/** Two squares side by side, for square highlights. */
private class SquaresScope(private val scope: DrawScope) : DrawScope by scope {
    private val square get() = size.width / 2

    fun center(index: Int) = Offset(square * (index + 0.5f), square / 2)

    /** Square [index] in its board [colour], with a [highlight] over it. */
    fun square(index: Int, colour: Color, highlight: Color?) {
        drawRect(colour, Offset(square * index, 0f), Size(square, square))
        highlight?.let { drawRect(it, Offset(square * index, 0f), Size(square, square)) }
    }

    /** An arrow from the first square to the second, with a light outline as on the boards. */
    fun arrow(colour: Color) {
        val head = center(1) + Offset(square * 0.12f, 0f)
        val strokes = listOf(
            center(0) to head,
            head to head - Offset(square * 0.28f, square * 0.22f),
            head to head - Offset(square * 0.28f, -square * 0.22f),
        )
        for ((width, paint) in listOf(square * 0.26f to Color.White.copy(alpha = 0.9f), square * 0.13f to colour)) {
            for ((from, to) in strokes) drawLine(paint, from, to, strokeWidth = width, cap = StrokeCap.Round)
        }
    }
}

@Composable
private fun Squares(draw: SquaresScope.() -> Unit) {
    Canvas(Modifier.size(width = 56.dp, height = 28.dp)) { SquaresScope(this).draw() }
}
