package com.andreaserick.multiversechess.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import com.andreaserick.multiversechess.engine.BOARD_SIZE
import com.andreaserick.multiversechess.engine.Board
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
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/** World-space layout: timeline `l` is row `l` (negative rows above), half-move `t` is column `t`. */
internal object Layout {
    const val SQ = 40f
    const val BOARD = SQ * BOARD_SIZE
    const val GAP_X = SQ * 3
    const val GAP_Y = SQ * 3.6f
    const val COL = BOARD + GAP_X
    const val ROW = BOARD + GAP_Y

    /** Height of the ribbon that carries a timeline from board to board. */
    const val RIBBON = BOARD * 0.42f

    /** Offset of the flat shadow under boards and ribbons. */
    val SLAB = Offset(SQ * 0.26f, SQ * 0.32f)

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

    /** Parts of the view covered by overlaid controls, in pixels; focusing centers between them. */
    var coveredTop by mutableFloatStateOf(0f)
    var coveredBottom by mutableFloatStateOf(0f)

    private val hasSize get() = viewSize.width > 0 && viewSize.height > 0
    private var initialized = false

    fun toWorld(screen: Offset): Offset = (screen - offset) / scale

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(MIN_SCALE, MAX_SCALE)
        offset = centroid - (centroid - offset) * (newScale / scale) + pan
        scale = newScale
    }

    /**
     * Glides to center [area] (in world units) in the uncovered part of the view. The first time,
     * with [resetZoom], or when the area wouldn't fit, it also zooms so the area fills the view
     * comfortably, leaving neighbouring boards and ribbons in sight.
     */
    suspend fun focus(area: Rect, resetZoom: Boolean = false) {
        if (!hasSize) return
        val usableHeight = viewSize.height - coveredTop - coveredBottom
        val fit = min(
            viewSize.width * 0.62f / (area.width + Layout.SQ),
            usableHeight * 0.9f / (area.height + Layout.SQ * 2),
        ).coerceIn(MIN_SCALE, MAX_SCALE)
        val targetScale = if (resetZoom || !initialized || fit < scale) fit else scale
        val center = Offset(viewSize.width / 2f, coveredTop + usableHeight / 2f)
        val targetOffset = center - area.center * targetScale
        if (!initialized) {
            initialized = true
            scale = targetScale
            offset = targetOffset
            return
        }
        val startOffset = offset
        val startScale = scale
        animate(0f, 1f, animationSpec = tween(520, easing = FastOutSlowInEasing)) { f, _ ->
            scale = startScale + (targetScale - startScale) * f
            offset = startOffset + (targetOffset - startOffset) * f
        }
    }

    private companion object {
        const val MIN_SCALE = 0.08f
        const val MAX_SCALE = 6f
    }
}

/** The board to show for this position: the first present board the player must move on. */
internal fun GameState.focusBoard(): Pair<Int, Int> {
    val l = mandatoryTimelines.firstOrNull() ?: playableTimelines().firstOrNull()?.index ?: 0
    return l to timelines.getValue(l).end
}

/** The area the camera should frame: every board the player must move on (or may, if none). */
internal fun GameState.focusArea(): Rect {
    val ls = mandatoryTimelines.ifEmpty { playableTimelines().map { it.index } }.ifEmpty { listOf(0) }
    return ls.map { Layout.boardRect(it, timelines.getValue(it).end) }
        .reduce { a, b -> Rect(min(a.left, b.left), min(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom)) }
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
    val glyphs = remember(textMeasurer, density) { measurePieceGlyphs(textMeasurer, density) }
    val labels = remember(textMeasurer, density) { LabelStyles(density) }

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
        drawRect(Palette.floor)
        val visible = Rect(camera.toWorld(Offset.Zero), camera.toWorld(Offset(size.width, size.height)))
        withTransform({
            translate(camera.offset.x, camera.offset.y)
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
        }) {
            drawMultiverse(session, targets, threats, glyphs, labels, textMeasurer, visible)
        }
    }
}

/** Text styles for labels drawn in world space (sizes are in world units). */
internal class LabelStyles(density: Density) {
    val caption = with(density) {
        TextStyle(color = Palette.inkSoft, fontSize = (Layout.SQ * 0.42f).toSp(), fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold)
    }
    val timeline = with(density) {
        TextStyle(color = Palette.ink, fontSize = (Layout.SQ * 0.75f).toSp(), fontFamily = SerifFamily, fontWeight = FontWeight.Bold)
    }
}

/**
 * Solid and hollow chess symbols for the standard pieces. White pieces are the solid symbol
 * filled white with the hollow one drawn on top in ink; black pieces are the solid symbol in ink
 * with a pale halo. Fairy pieces have no symbol and are drawn as lettered discs.
 */
private val GLYPHS = mapOf(
    PieceType.KING to ("♚︎" to "♔︎"),
    PieceType.QUEEN to ("♛︎" to "♕︎"),
    PieceType.ROOK to ("♜︎" to "♖︎"),
    PieceType.BISHOP to ("♝︎" to "♗︎"),
    PieceType.KNIGHT to ("♞︎" to "♘︎"),
    PieceType.PAWN to ("♟︎" to "♙︎"),
)

/** [body] is the solid symbol (or a fairy piece's letter); [outline] the hollow symbol, if any. */
internal class PieceGlyph(val body: TextLayoutResult, val outline: TextLayoutResult?)

internal fun measurePieceGlyphs(textMeasurer: TextMeasurer, density: Density): Map<PieceType, PieceGlyph> {
    val glyphStyle = TextStyle(fontSize = with(density) { (Layout.SQ * 0.82f).toSp() })
    val letterStyle = TextStyle(
        fontSize = with(density) { (Layout.SQ * 0.46f).toSp() },
        fontWeight = FontWeight.Bold,
        fontFamily = SerifFamily,
    )
    return PieceType.entries.associateWith { type ->
        val symbols = GLYPHS[type]
        if (symbols == null) {
            PieceGlyph(textMeasurer.measure(type.letter.toString(), letterStyle), outline = null)
        } else {
            PieceGlyph(textMeasurer.measure(symbols.first, glyphStyle), textMeasurer.measure(symbols.second, glyphStyle))
        }
    }
}

private fun DrawScope.drawMultiverse(
    session: GameSession,
    targets: List<Move>,
    threats: List<Move>,
    glyphs: Map<PieceType, PieceGlyph>,
    labels: LabelStyles,
    textMeasurer: TextMeasurer,
    visible: Rect,
) {
    val state = session.state
    val side = state.sideToMove
    val mandatory = state.mandatoryTimelines.toSet()
    val ongoing = session.outcome == Outcome.Ongoing

    // Alternate a faint band behind every other timeline row to make rows easy to follow.
    val firstRow = floor(visible.top / Layout.ROW).toInt() - 1
    val lastRow = floor(visible.bottom / Layout.ROW).toInt() + 1
    for (l in firstRow..lastRow) {
        if (Math.floorMod(l, 2) == 1) {
            drawRect(
                Palette.floorBand,
                Offset(visible.left, l * Layout.ROW - Layout.GAP_Y / 2),
                Size(visible.width, Layout.ROW),
            )
        }
    }

    // The present: a pale column with ruled edges.
    val presentLeft = state.present * Layout.COL - Layout.GAP_X * 0.32f
    val presentWidth = Layout.BOARD + Layout.GAP_X * 0.64f
    drawRect(Palette.present, Offset(presentLeft, visible.top), Size(presentWidth, visible.height))
    for (x in floatArrayOf(presentLeft, presentLeft + presentWidth)) {
        drawLine(Palette.presentEdge, Offset(x, visible.top), Offset(x, visible.bottom), strokeWidth = Layout.SQ * 0.12f)
    }

    // Timeline ribbons: shadows first, then the ribbons, so boards sit on top of both.
    for (pass in 0..1) {
        for (timeline in state.timelines.values) {
            val color = if (pass == 0) Palette.slab else if (state.isActive(timeline.index)) Palette.ribbon else Palette.ribbonInactive
            val shift = if (pass == 0) Layout.SLAB else Offset.Zero
            drawTimelineRibbon(timeline.index, timeline.start, timeline.end, timeline.parent, color, shift, arrow = ongoing)
        }
    }

    // Squares highlighted because of the last or current turn.
    val turnMoves = session.lastTurn + state.turnMoves
    val recentSquares = turnMoves.filter { it.isSpatial }
        .flatMap { listOf(it.from.copy(t = it.from.t + 1), it.to.copy(t = it.to.t + 1)) }
        .toSet()
    val travelSquares = turnMoves.filter { !it.isSpatial }.flatMap { listOf(it.from, it.to) }.toSet()
    val targetSquares = targets.map { it.to }.toSet()

    for (timeline in state.timelines.values) {
        val active = state.isActive(timeline.index)
        for (t in timeline.start..timeline.end) {
            val rect = Layout.boardRect(timeline.index, t)
            if (!rect.inflate(Layout.SQ * 2).overlaps(visible)) continue
            val board = timeline.boardAt(t)!!
            val playable = ongoing && t == timeline.end && Side.toMoveAt(t) == side
            drawBoardCard(rect, Side.toMoveAt(t), playable, must = timeline.index in mandatory)

            drawBoard(board, rect.topLeft, glyphs) { x, y ->
                val pos = Pos(timeline.index, t, x, y)
                when {
                    pos == session.selected -> Palette.selection
                    pos in targetSquares -> Palette.target
                    pos in travelSquares -> Palette.travel
                    pos in recentSquares -> Palette.lastMove
                    else -> null
                }
            }
            for (pos in targetSquares) {
                if (pos.l != timeline.index || pos.t != t) continue
                val center = Layout.squareCenter(pos)
                if (board[pos.x, pos.y] == null) {
                    drawCircle(Palette.targetDot, Layout.SQ * 0.15f, center)
                } else {
                    drawCircle(Palette.targetDot, Layout.SQ * 0.44f, center, style = Stroke(Layout.SQ * 0.07f))
                }
            }
            for (threat in threats) {
                if (threat.to.l == timeline.index && threat.to.t == t) {
                    drawRect(Palette.threat.copy(alpha = 0.45f), Layout.squareTopLeft(threat.to), Size(Layout.SQ, Layout.SQ))
                }
            }

            val caption = textMeasurer.measure("T${Notation.turnNumber(t)}", labels.caption)
            val captionGap = if (playable) Layout.SQ * 0.95f else Layout.SQ * 0.45f
            drawText(caption, topLeft = Offset(rect.left, rect.top - captionGap - caption.size.height))

            if (!active) drawRect(Palette.floor.copy(alpha = 0.6f), rect.topLeft, rect.size)
        }
        val label = textMeasurer.measure("L${Notation.timelineLabel(timeline.index)}", labels.timeline.copy(
            color = if (active) Palette.ink else Palette.inkSoft,
        ))
        val labelX = timeline.start * Layout.COL - label.size.width - Layout.SQ * 0.9f
        val midY = timeline.index * Layout.ROW + Layout.BOARD / 2
        if (timeline.parent == null) drawText(label, topLeft = Offset(labelX, midY - label.size.height / 2f))
        else drawText(label, topLeft = Offset(timeline.start * Layout.COL, midY - Layout.BOARD / 2 - Layout.SQ * 1.9f))
    }

    // Arrows for moves between boards, and for threats against the side to move's kings.
    for (move in turnMoves) {
        if (!move.isSpatial) drawCurvedArrow(Layout.squareCenter(move.from), Layout.squareCenter(move.to), Palette.travelArrow)
    }
    for (threat in threats) {
        drawCurvedArrow(Layout.squareCenter(threat.from), Layout.squareCenter(threat.to), Palette.threat)
    }
}

/**
 * A timeline's ribbon: a thick band through its boards, a curve from the board it branched from,
 * and an arrow pointing into the future past its latest board.
 */
private fun DrawScope.drawTimelineRibbon(
    l: Int,
    start: Int,
    end: Int,
    parent: Int?,
    color: Color,
    shift: Offset,
    arrow: Boolean,
) {
    val h = Layout.RIBBON
    val midY = l * Layout.ROW + Layout.BOARD / 2 + shift.y
    val left = start * Layout.COL + shift.x
    val right = end * Layout.COL + Layout.BOARD + shift.x
    drawRect(color, Offset(left, midY - h / 2), Size(right - left, h))

    if (arrow) {
        val shaft = Layout.GAP_X * 0.35f
        val headLength = Layout.GAP_X * 0.55f
        drawRect(color, Offset(right, midY - h / 2), Size(shaft, h))
        val head = Path().apply {
            moveTo(right + shaft, midY - h * 0.95f)
            lineTo(right + shaft + headLength, midY)
            lineTo(right + shaft, midY + h * 0.95f)
            close()
        }
        drawPath(head, color)
    }

    if (parent != null) {
        val from = Offset((start - 1) * Layout.COL + Layout.BOARD * 0.5f, parent * Layout.ROW + Layout.BOARD / 2) + shift
        val to = Offset(start * Layout.COL + Layout.BOARD * 0.5f, midY)
        val path = Path().apply {
            moveTo(from.x, from.y)
            cubicTo(from.x + Layout.COL * 0.55f, from.y, to.x - Layout.COL * 0.55f, to.y, to.x, to.y)
        }
        drawPath(path, color, style = Stroke(width = h, cap = StrokeCap.Butt))
    }
}

/**
 * The frame around a board. Its colour says whose move the board is (ivory White, ink Black).
 * Boards that can be played now sit on a raised card, ringed in gold if they must be played.
 */
private fun DrawScope.drawBoardCard(rect: Rect, toMove: Side, playable: Boolean, must: Boolean) {
    val frame = Layout.SQ * 0.32f
    val frameColor = if (toMove == Side.WHITE) Palette.whiteBoardFrame else Palette.blackBoardFrame
    if (playable) {
        val pad = Layout.SQ * 0.75f
        val card = rect.inflate(pad)
        val radius = CornerRadius(Layout.SQ * 0.7f)
        drawRoundRect(Palette.slab, card.topLeft + Layout.SLAB, card.size, radius)
        drawRoundRect(Palette.card, card.topLeft, card.size, radius)
        val ring = if (must) Palette.gold else Palette.presentEdge
        drawRoundRect(
            ring,
            card.topLeft + Offset(Layout.SQ * 0.12f, Layout.SQ * 0.12f),
            Size(card.width - Layout.SQ * 0.24f, card.height - Layout.SQ * 0.24f),
            CornerRadius(Layout.SQ * 0.6f),
            style = Stroke(width = Layout.SQ * if (must) 0.22f else 0.1f),
        )
        drawRect(frameColor, rect.topLeft - Offset(frame, frame), Size(rect.width + 2 * frame, rect.height + 2 * frame))
    } else {
        val outer = rect.inflate(frame)
        drawRect(Palette.slab, outer.topLeft + Layout.SLAB, outer.size)
        drawRect(frameColor, outer.topLeft, outer.size)
    }
}

/**
 * Squares and pieces of [board] with its top-left corner at [topLeft], in world units. [highlight]
 * gives an optional overlay colour for each square (file [x], rank [y]).
 */
internal fun DrawScope.drawBoard(
    board: Board,
    topLeft: Offset,
    glyphs: Map<PieceType, PieceGlyph>,
    highlight: (x: Int, y: Int) -> Color? = { _, _ -> null },
) {
    val squareSize = Size(Layout.SQ, Layout.SQ)
    for (x in 0 until BOARD_SIZE) for (y in 0 until BOARD_SIZE) {
        val square = topLeft + Offset(x * Layout.SQ, (BOARD_SIZE - 1 - y) * Layout.SQ)
        drawRect(if ((x + y) % 2 == 0) Palette.darkSquare else Palette.lightSquare, square, squareSize)
        highlight(x, y)?.let { drawRect(it, square, squareSize) }
    }
    for ((square, piece) in board.pieces()) {
        val center = topLeft + Offset((square.x + 0.5f) * Layout.SQ, (BOARD_SIZE - square.y - 0.5f) * Layout.SQ)
        drawPiece(glyphs.getValue(piece.type), piece.side, center)
    }
}

private fun DrawScope.drawPiece(glyph: PieceGlyph, side: Side, center: Offset) {
    fun TextLayoutResult.topLeft() = center - Offset(size.width / 2f, size.height / 2f)
    val (fill, outline) = if (side == Side.WHITE) Color.White to Palette.ink else Palette.ink to Color(0xFFF4EFE4)
    if (glyph.outline == null) {
        val radius = Layout.SQ * 0.36f
        drawCircle(fill, radius, center)
        drawCircle(outline, radius, center, style = Stroke(width = Layout.SQ * 0.05f))
        drawText(glyph.body, color = outline, topLeft = glyph.body.topLeft())
        return
    }
    if (side == Side.WHITE) {
        drawText(glyph.body, color = fill, topLeft = glyph.body.topLeft())
        drawText(glyph.outline, color = outline, topLeft = glyph.outline.topLeft())
    } else {
        // The hollow symbols' inner lines are too heavy on a dark piece, so outline it with a halo.
        val d = Layout.SQ * 0.035f
        for (dx in -1..1) for (dy in -1..1) {
            if (dx != 0 || dy != 0) drawText(glyph.body, color = outline, topLeft = glyph.body.topLeft() + Offset(dx * d, dy * d))
        }
        drawText(glyph.body, color = fill, topLeft = glyph.body.topLeft())
    }
}

/** A gently curved arrow with a pale outline, so it reads over boards and the floor alike. */
private fun DrawScope.drawCurvedArrow(from: Offset, to: Offset, color: Color) {
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length < 1f) return
    val normal = Offset(-(to.y - from.y) / length, (to.x - from.x) / length)
    val control = (from + to) / 2f + normal * (length * 0.12f)
    val headLength = Layout.SQ * 0.75f
    // Stop the shaft where the head begins, along the curve's final direction.
    val angle = atan2(to.y - control.y, to.x - control.x)
    val direction = Offset(cos(angle), sin(angle))
    val shaftEnd = to - direction * (headLength * 0.8f)
    val shaft = Path().apply {
        moveTo(from.x, from.y)
        quadraticTo(control.x, control.y, shaftEnd.x, shaftEnd.y)
    }
    val head = Path().apply {
        val side = Offset(-direction.y, direction.x) * (headLength * 0.55f)
        val base = to - direction * headLength
        moveTo(to.x, to.y)
        lineTo(base.x + side.x, base.y + side.y)
        lineTo(base.x - side.x, base.y - side.y)
        close()
    }
    val width = Layout.SQ * 0.16f
    val outline = Color.White.copy(alpha = 0.9f)
    drawPath(shaft, outline, style = Stroke(width = width + Layout.SQ * 0.12f, cap = StrokeCap.Round))
    drawPath(head, outline, style = Stroke(width = Layout.SQ * 0.12f, join = StrokeJoin.Round))
    drawPath(shaft, color, style = Stroke(width = width, cap = StrokeCap.Round))
    drawPath(head, color)
    drawCircle(color, width * 0.9f, from)
}

/** One board drawn at the size of its container, for previews such as the puzzle list. */
@Composable
internal fun BoardPreview(board: Board, modifier: Modifier = Modifier) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val glyphs = remember(textMeasurer, density) { measurePieceGlyphs(textMeasurer, density) }
    Canvas(modifier) {
        val s = size.minDimension / Layout.BOARD
        withTransform({ scale(s, s, pivot = Offset.Zero) }) {
            drawBoard(board, Offset.Zero, glyphs)
        }
    }
}
