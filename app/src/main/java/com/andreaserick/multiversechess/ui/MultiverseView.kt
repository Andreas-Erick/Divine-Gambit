package com.andreaserick.multiversechess.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import com.andreaserick.multiversechess.engine.BOARD_SIZE
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.GameState
import com.andreaserick.multiversechess.engine.Move
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.PieceType
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Side
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/** World-space layout: timeline `l` is row `l` (negative rows above), half-move `t` is column `t`. */
internal object Layout {
    const val SQ = 40f
    const val BOARD = SQ * BOARD_SIZE
    const val GAP_X = SQ * 3
    const val GAP_Y = SQ * 2.5f
    const val COL = BOARD + GAP_X
    const val ROW = BOARD + GAP_Y

    fun boardTopLeft(l: Int, t: Int) = Offset(t * COL, l * ROW)

    fun boardRect(l: Int, t: Int) = Rect(boardTopLeft(l, t), Size(BOARD, BOARD))

    fun boardCenter(l: Int, t: Int) = boardTopLeft(l, t) + Offset(BOARD / 2, BOARD / 2)

    /** White is at the bottom of every board, so rank 8 is the top row. */
    fun squareTopLeft(p: Pos) = boardTopLeft(p.l, p.t) + Offset(p.x * SQ, (BOARD_SIZE - 1 - p.y) * SQ)

    fun squareCenter(p: Pos) = squareTopLeft(p) + Offset(SQ / 2, SQ / 2)

    fun hitTest(world: Offset): Pos? {
        val t = floor(world.x / COL).toInt()
        val l = floor(world.y / ROW).toInt()
        val local = world - boardTopLeft(l, t)
        val x = floor(local.x / SQ).toInt()
        val row = floor(local.y / SQ).toInt()
        if (x !in 0 until BOARD_SIZE || row !in 0 until BOARD_SIZE) return null
        return Pos(l, t, x, BOARD_SIZE - 1 - row)
    }
}

/** Pan and zoom of the multiverse view. Screen position = world * scale + offset. */
@Stable
class CameraState {
    var offset by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)
    var viewSize by mutableStateOf(IntSize.Zero)

    private val hasSize get() = viewSize.width > 0 && viewSize.height > 0
    private var initialized = false

    fun toWorld(screen: Offset): Offset = (screen - offset) / scale

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = centroid - (centroid - offset) * (newScale / scale) + pan
        scale = newScale
    }

    /** Centers board ([l], [t]). The first time, also zooms so one board nearly fills the view. */
    fun focus(l: Int, t: Int, resetZoom: Boolean = false) {
        if (!hasSize) return
        if (resetZoom || !initialized) {
            scale = (min(viewSize.width, viewSize.height) * 0.8f / Layout.BOARD).coerceIn(MIN_SCALE, MAX_SCALE)
            initialized = true
        }
        offset = Offset(viewSize.width / 2f, viewSize.height / 2f) - Layout.boardCenter(l, t) * scale
    }

    private companion object {
        const val MIN_SCALE = 0.1f
        const val MAX_SCALE = 6f
    }
}

/** The board the camera should show: the first present board the player must move on. */
internal fun GameState.focusBoard(): Pair<Int, Int> {
    val l = mandatoryTimelines.firstOrNull() ?: 0
    return l to timelines.getValue(l).end
}

@Composable
fun MultiverseView(
    session: GameSession,
    camera: CameraState,
    onTap: (Pos) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val currentSession by rememberUpdatedState(session)
    val currentOnTap by rememberUpdatedState(onTap)
    val targets = remember(session) { session.targets }
    val threats = remember(session) { session.state.threats() }
    val glyphs = remember(textMeasurer, density) {
        val fontSize = with(density) { (Layout.SQ * 0.8f).toSp() }
        PieceType.entries.associateWith { textMeasurer.measure(GLYPHS.getValue(it), TextStyle(fontSize = fontSize)) }
    }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.viewSize = it }
            .pointerInput(camera) {
                detectTransformGestures { centroid, pan, zoom, _ -> camera.transform(centroid, pan, zoom) }
            }
            .pointerInput(camera) {
                detectTapGestures { tap ->
                    val pos = Layout.hitTest(camera.toWorld(tap)) ?: return@detectTapGestures
                    if (currentSession.state.boardAt(pos.l, pos.t) != null) currentOnTap(pos)
                }
            },
    ) {
        drawRect(Palette.background)
        val visible = Rect(camera.toWorld(Offset.Zero), camera.toWorld(Offset(size.width, size.height)))
        withTransform({
            translate(camera.offset.x, camera.offset.y)
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
        }) {
            drawMultiverse(session, targets, threats, glyphs, textMeasurer, visible)
        }
    }
}

private val GLYPHS = mapOf(
    PieceType.KING to "♚︎",
    PieceType.QUEEN to "♛︎",
    PieceType.ROOK to "♜︎",
    PieceType.BISHOP to "♝︎",
    PieceType.KNIGHT to "♞︎",
    PieceType.PAWN to "♟︎",
)

private fun DrawScope.drawMultiverse(
    session: GameSession,
    targets: List<Move>,
    threats: List<Move>,
    glyphs: Map<PieceType, TextLayoutResult>,
    textMeasurer: TextMeasurer,
    visible: Rect,
) {
    val state = session.state
    val side = state.sideToMove
    val mandatory = state.mandatoryTimelines.toSet()
    val labelStyle = TextStyle(color = Palette.label, fontSize = (Layout.SQ * 0.4f).toSp())
    val timelineLabelStyle = TextStyle(
        color = Palette.label,
        fontSize = (Layout.SQ * 0.7f).toSp(),
        fontWeight = FontWeight.Bold,
    )

    // The present: a column band behind the boards.
    val presentX = state.present * Layout.COL - Layout.GAP_X / 4
    drawRect(
        color = (if (Side.toMoveAt(state.present) == Side.WHITE) Color.White else Palette.gold).copy(alpha = 0.07f),
        topLeft = Offset(presentX, visible.top),
        size = Size(Layout.BOARD + Layout.GAP_X / 2, visible.height),
    )

    // Connectors between consecutive boards and from each branch point.
    val lineWidth = Layout.SQ * 0.15f
    for (timeline in state.timelines.values) {
        val midY = timeline.index * Layout.ROW + Layout.BOARD / 2
        for (t in timeline.start until timeline.end) {
            drawLine(
                Palette.connector,
                Offset(t * Layout.COL + Layout.BOARD, midY),
                Offset((t + 1) * Layout.COL, midY),
                strokeWidth = lineWidth,
            )
        }
        timeline.parent?.let { parent ->
            val from = Offset((timeline.start - 1) * Layout.COL + Layout.BOARD, parent * Layout.ROW + Layout.BOARD / 2)
            val to = Offset(timeline.start * Layout.COL, midY)
            val path = Path().apply {
                moveTo(from.x, from.y)
                cubicTo(from.x + Layout.GAP_X / 2, from.y, to.x - Layout.GAP_X / 2, to.y, to.x, to.y)
            }
            drawPath(path, Palette.connector, style = Stroke(width = lineWidth))
        }
        val label = textMeasurer.measure("L${Notation.timelineLabel(timeline.index)}", timelineLabelStyle)
        drawText(
            label,
            topLeft = Offset(
                timeline.start * Layout.COL - label.size.width - Layout.SQ * 0.6f,
                midY - label.size.height / 2f,
            ),
        )
    }

    // Squares highlighted because of the last or current turn's ordinary moves.
    val recentSquares = (session.lastTurn + state.turnMoves)
        .filter { it.isSpatial }
        .flatMap { listOf(it.from.copy(t = it.from.t + 1), it.to.copy(t = it.to.t + 1)) }
        .toSet()

    for (timeline in state.timelines.values) {
        val active = state.isActive(timeline.index)
        for (t in timeline.start..timeline.end) {
            val rect = Layout.boardRect(timeline.index, t)
            if (!rect.inflate(Layout.SQ).overlaps(visible)) continue
            val board = timeline.boardAt(t)!!
            val frame = Layout.SQ * 0.3f
            val frameColor = if (Side.toMoveAt(t) == Side.WHITE) Palette.whiteBoardFrame else Palette.blackBoardFrame
            drawRect(frameColor, rect.topLeft - Offset(frame, frame), Size(Layout.BOARD + 2 * frame, Layout.BOARD + 2 * frame))

            val playable = t == timeline.end && Side.toMoveAt(t) == side && session.outcome == Outcome.Ongoing
            if (playable) {
                val must = timeline.index in mandatory
                val outline = frame + Layout.SQ * 0.15f
                drawRect(
                    Palette.gold.copy(alpha = if (must) 1f else 0.45f),
                    rect.topLeft - Offset(outline, outline),
                    Size(Layout.BOARD + 2 * outline, Layout.BOARD + 2 * outline),
                    style = Stroke(width = Layout.SQ * if (must) 0.2f else 0.1f),
                )
            }

            for (x in 0 until BOARD_SIZE) for (y in 0 until BOARD_SIZE) {
                val pos = Pos(timeline.index, t, x, y)
                val topLeft = Layout.squareTopLeft(pos)
                val squareSize = Size(Layout.SQ, Layout.SQ)
                drawRect(if ((x + y) % 2 == 0) Palette.darkSquare else Palette.lightSquare, topLeft, squareSize)
                if (pos in recentSquares) drawRect(Palette.lastMove, topLeft, squareSize)
                if (pos == session.selected) drawRect(Palette.selection, topLeft, squareSize)
            }
            for (threat in threats) {
                if (threat.to.l == timeline.index && threat.to.t == t) {
                    drawRect(Palette.threat.copy(alpha = 0.6f), Layout.squareTopLeft(threat.to), Size(Layout.SQ, Layout.SQ))
                }
            }
            for ((square, piece) in board.pieces()) {
                drawPiece(glyphs.getValue(piece.type), piece.side, Layout.squareCenter(Pos(timeline.index, t, square.x, square.y)))
            }

            val caption = textMeasurer.measure(Notation.boardLabel(timeline.index, t), labelStyle)
            drawText(caption, topLeft = Offset(rect.left, rect.top - frame - caption.size.height - Layout.SQ * 0.1f))

            if (!active) drawRect(Palette.background.copy(alpha = 0.55f), rect.topLeft, rect.size)
        }
    }

    // Possible destinations for the selected piece.
    for (move in targets) {
        val center = Layout.squareCenter(move.to)
        if (state.pieceAt(move.to) != null) {
            drawCircle(Palette.target, radius = Layout.SQ * 0.45f, center = center, style = Stroke(width = Layout.SQ * 0.08f))
        } else {
            drawCircle(Palette.target, radius = Layout.SQ * 0.17f, center = center)
        }
    }

    // Arrows for moves between boards, and for threats against the side to move's kings.
    for (move in session.lastTurn + state.turnMoves) {
        if (!move.isSpatial) drawArrow(Layout.squareCenter(move.from), Layout.squareCenter(move.to), Palette.travelArrow)
    }
    for (threat in threats) {
        drawArrow(Layout.squareCenter(threat.from), Layout.squareCenter(threat.to), Palette.threat)
    }
}

private fun DrawScope.drawPiece(glyph: TextLayoutResult, side: Side, center: Offset) {
    val topLeft = center - Offset(glyph.size.width / 2f, glyph.size.height / 2f)
    val (fill, outline) = if (side == Side.WHITE) Color.White to Color(0xFF1A1A1A) else Color(0xFF1A1A1A) to Color(0xFFD8D4E4)
    drawText(glyph, color = fill, topLeft = topLeft)
    drawText(glyph, color = outline, topLeft = topLeft, drawStyle = Stroke(width = Layout.SQ * 0.04f))
}

private fun DrawScope.drawArrow(from: Offset, to: Offset, color: Color) {
    val width = Layout.SQ * 0.12f
    drawLine(color, from, to, strokeWidth = width, cap = StrokeCap.Round)
    val angle = atan2(to.y - from.y, to.x - from.x)
    val head = Layout.SQ * 0.45f
    for (side in floatArrayOf(-1f, 1f)) {
        val a = angle + Math.PI.toFloat() + side * 0.45f
        drawLine(color, to, to + Offset(cos(a) * head, sin(a) * head), strokeWidth = width, cap = StrokeCap.Round)
    }
}
