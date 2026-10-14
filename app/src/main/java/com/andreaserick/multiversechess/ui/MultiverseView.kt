package com.andreaserick.multiversechess.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
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

    /** The timeline whose row (boards, ribbon, label or the gaps around them) contains [world]. */
    fun timelineAt(world: Offset, state: GameState): Int? {
        val l = floor((world.y + GAP_Y / 2) / ROW).toInt()
        val timeline = state.timelines[l] ?: return null
        return l.takeIf { world.x in timeline.start * COL - GAP_X..timeline.end * COL + BOARD + GAP_X }
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

    /** Everything drawn, in world units, so the camera can always zoom out far enough to see it all. */
    var worldBounds = Rect.Zero

    private val hasSize get() = viewSize.width > 0 && viewSize.height > 0
    private var initialized = false

    /** Animations take this so a new one, or a finger on the screen, cancels the one running. */
    private val mutex = MutatorMutex()

    private val usableHeight get() = viewSize.height - coveredTop - coveredBottom

    private val minScale: Float
        get() = if (!hasSize || worldBounds.isEmpty) MIN_SCALE else min(MIN_SCALE, fitScale(worldBounds, 1f) * 0.6f)

    fun toWorld(screen: Offset): Offset = (screen - offset) / scale

    fun toScreen(world: Offset): Offset = world * scale + offset

    fun transform(centroid: Offset, pan: Offset, zoom: Float) {
        val newScale = (scale * zoom).coerceIn(minScale, MAX_SCALE)
        offset = centroid - (centroid - offset) * (newScale / scale) + pan
        scale = newScale
    }

    /** Stops any glide or fling, because the player has taken hold of the view. */
    suspend fun stop() = mutex.mutate(MutatePriority.UserInput) {}

    /** Keeps the view sliding after a pan is released, slowing to a stop. */
    suspend fun fling(velocity: Offset, decay: DecayAnimationSpec<Offset>) = mutex.mutate {
        AnimationState(Offset.VectorConverter, offset, velocity).animateDecay(decay) { offset = value }
    }

    /** The scale at which [area] fills [fill] of the view's width or most of its uncovered height. */
    private fun fitScale(area: Rect, fill: Float) = min(
        viewSize.width * fill / (area.width + Layout.SQ),
        usableHeight * 0.9f / (area.height + Layout.SQ * 2),
    )

    /**
     * Glides to center [area] (in world units) in the uncovered part of the view. The first time,
     * with [resetZoom], or when the area wouldn't fit, it also zooms so the area fills the view
     * comfortably, leaving neighbouring boards and ribbons in sight. If showing all of [area] would
     * shrink squares below [minSquare] pixels, it shows [fallback] instead.
     */
    suspend fun focus(area: Rect, resetZoom: Boolean = false, minSquare: Float = 0f, fallback: Rect = area) {
        if (!hasSize) return
        val shown = if (fitScale(area, 0.62f) * Layout.SQ < minSquare) fallback else area
        val fit = fitScale(shown, 0.62f).coerceIn(minScale, MAX_SCALE)
        moveTo(shown.center, if (resetZoom || !initialized || fit < scale) fit else scale)
    }

    /**
     * Glides and zooms so [area] fills the view. If that would shrink boards below [minBoardWidth]
     * (a fraction of the view's width), it zooms only that far and shows the area's latest end.
     */
    suspend fun frame(area: Rect, minBoardWidth: Float = 0f) {
        if (!hasSize) return
        val targetScale = fitScale(area, 0.92f)
            .coerceAtLeast(viewSize.width * minBoardWidth / Layout.BOARD)
            .coerceIn(minScale, MAX_SCALE)
        val halfWidth = viewSize.width / 2f / targetScale
        moveTo(Offset(maxOf(area.center.x, area.right + Layout.SQ - halfWidth), area.center.y), targetScale)
    }

    private suspend fun moveTo(center: Offset, targetScale: Float) {
        val targetOffset = Offset(viewSize.width / 2f, coveredTop + usableHeight / 2f) - center * targetScale
        if (!initialized) {
            initialized = true
            scale = targetScale
            offset = targetOffset
            return
        }
        mutex.mutate {
            val startOffset = offset
            val startScale = scale
            animate(0f, 1f, animationSpec = tween(520, easing = FastOutSlowInEasing)) { f, _ ->
                scale = startScale + (targetScale - startScale) * f
                offset = startOffset + (targetOffset - startOffset) * f
            }
        }
    }

    private companion object {
        const val MIN_SCALE = 0.08f
        const val MAX_SCALE = 6f
    }
}

private fun union(a: Rect, b: Rect) = Rect(min(a.left, b.left), min(a.top, b.top), maxOf(a.right, b.right), maxOf(a.bottom, b.bottom))

/** The board to show for this position: the first present board the player must move on. */
internal fun GameState.focusBoard(): Pair<Int, Int> {
    val l = mandatoryTimelines.firstOrNull() ?: playableTimelines().firstOrNull()?.index ?: 0
    return l to timelines.getValue(l).end
}

/** The area the camera should frame: every board the player must move on (or may, if none). */
internal fun GameState.focusArea(): Rect {
    val ls = mandatoryTimelines.ifEmpty { playableTimelines().map { it.index } }.ifEmpty { listOf(0) }
    return ls.map { Layout.boardRect(it, timelines.getValue(it).end) }.reduce(::union)
}

/** Every board of [l], from its first to its latest. */
internal fun GameState.timelineArea(l: Int): Rect {
    val timeline = timelines.getValue(l)
    return union(Layout.boardRect(l, timeline.start), Layout.boardRect(l, timeline.end))
}

/** The whole multiverse, with room for labels and the arrows into the future. */
internal fun GameState.overviewArea(): Rect =
    timelines.keys.map { timelineArea(it) }.reduce(::union).inflate(Layout.SQ * 2)

@Composable
fun MultiverseView(
    session: GameSession,
    camera: CameraState,
    onTap: (Pos) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSession by rememberUpdatedState(session)
    val currentOnTap by rememberUpdatedState(onTap)
    val targets = remember(session) { session.targets }
    val threats = remember(session) { session.state.threats() }
    val glyphs = remember(textMeasurer, density) { measurePieceGlyphs(textMeasurer, density) }
    val labels = remember(textMeasurer, density) { LabelStyles(density) }
    val rail = remember { TimelineRail() }
    camera.worldBounds = remember(session.state) { session.state.overviewArea() }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.viewSize = it }
            .pointerInput(camera) { detectCameraGestures(camera) }
            .pointerInput(camera) {
                detectTapGestures { tap ->
                    val state = currentSession.state
                    rail.hit(tap)?.let { l ->
                        scope.launch { camera.frame(state.timelineArea(l), TIMELINE_MIN_BOARD) }
                        return@detectTapGestures
                    }
                    val world = camera.toWorld(tap)
                    val pos = Layout.hitTest(world)
                    if (pos != null && state.boardAt(pos.l, pos.t) != null) {
                        // Squares too small to aim at: zoom in on the board instead of picking one.
                        if (Layout.SQ * camera.scale < MIN_TAPPABLE_SQUARE.toPx()) {
                            scope.launch { camera.frame(Layout.boardRect(pos.l, pos.t)) }
                        } else {
                            currentOnTap(pos)
                        }
                        return@detectTapGestures
                    }
                    Layout.timelineAt(world, state)?.let { l ->
                        scope.launch { camera.frame(state.timelineArea(l), TIMELINE_MIN_BOARD) }
                    }
                }
            },
    ) {
        drawRect(Palette.floor)
        val visible = Rect(camera.toWorld(Offset.Zero), camera.toWorld(Offset(size.width, size.height)))
        withTransform({
            translate(camera.offset.x, camera.offset.y)
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
        }) {
            drawMultiverse(session, targets, threats, glyphs, labels, textMeasurer, visible, detailed = Layout.SQ * camera.scale >= 10f)
        }
        drawTimelineRail(session.state, threats.mapTo(HashSet()) { it.from.l }, camera, rail, textMeasurer, labels.rail)
    }
}

/** Squares smaller than this on screen are too small to tap reliably. */
private val MIN_TAPPABLE_SQUARE = 14.dp

/** The present is shown no smaller than this, so it can be played without zooming. */
internal val COMFORTABLE_SQUARE = 20.dp

/** When framing a long timeline, boards stay at least this fraction of the view's width. */
private const val TIMELINE_MIN_BOARD = 0.24f

/**
 * Pinch to zoom and drag to pan, like detectTransformGestures, plus a fling when a one-finger
 * pan is released. Touching the view stops any glide or fling in progress.
 */
private suspend fun PointerInputScope.detectCameraGestures(camera: CameraState) {
    val decay = splineBasedDecay<Offset>(this)
    coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            launch { camera.stop() }
            val tracker = VelocityTracker()
            var pastSlop = false
            var slopPan = Offset.Zero
            var slopZoom = 1f
            do {
                val event = awaitPointerEvent()
                if (event.changes.any { it.isConsumed }) break
                val zoom = event.calculateZoom()
                val pan = event.calculatePan()
                if (!pastSlop) {
                    slopZoom *= zoom
                    slopPan += pan
                    val zoomMotion = abs(1 - slopZoom) * event.calculateCentroidSize(useCurrent = false)
                    pastSlop = zoomMotion > viewConfiguration.touchSlop || slopPan.getDistance() > viewConfiguration.touchSlop
                }
                if (pastSlop) {
                    // With no finger down before this event (such as the final lift) there's no centroid.
                    val centroid = event.calculateCentroid(useCurrent = false)
                    if (centroid.isSpecified && (zoom != 1f || pan != Offset.Zero)) camera.transform(centroid, pan, zoom)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                // Only one-finger movement flings; a finger lifting out of a pinch shouldn't.
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size == 1) {
                    tracker.addPosition(pressed[0].uptimeMillis, pressed[0].position)
                } else if (pressed.size > 1) {
                    tracker.resetTracking()
                }
            } while (event.changes.any { it.pressed })
            if (pastSlop) {
                val velocity = tracker.calculateVelocity()
                if (hypot(velocity.x, velocity.y) > 300f) launch { camera.fling(Offset(velocity.x, velocity.y), decay) }
            }
        }
    }
}

/** Where the timeline chips along the left edge were last drawn, for tapping. */
private class TimelineRail {
    val chips = mutableListOf<Pair<Int, Rect>>()

    fun hit(screen: Offset): Int? = chips.firstOrNull { it.second.inflate(8f).contains(screen) }?.first
}

/**
 * Chips naming timelines whose own label is out of sight (scrolled off to the left, or too small
 * to read), pinned to the left edge in screen space. Tapping one frames that timeline. A gold ring
 * means the timeline must be played, a red one that it's in [checked].
 */
private fun DrawScope.drawTimelineRail(
    state: GameState,
    checked: Set<Int>,
    camera: CameraState,
    rail: TimelineRail,
    textMeasurer: TextMeasurer,
    style: TextStyle,
) {
    rail.chips.clear()
    val margin = 10.dp.toPx()
    val padX = 10.dp.toPx()
    val padY = 5.dp.toPx()
    val labelReadable = Layout.SQ * 0.75f * camera.scale >= 11.dp.toPx()
    val mandatory = state.mandatoryTimelines.toSet()
    var lastBottom = Float.NEGATIVE_INFINITY
    for (timeline in state.timelines.values.sortedBy { it.index }) {
        val l = timeline.index
        // Branches are named above their first board, the first timeline to the left of it.
        val labelLeft = timeline.start * Layout.COL - if (timeline.parent == null) Layout.SQ * 3 else 0f
        if (labelReadable && camera.toScreen(Offset(labelLeft, 0f)).x >= 0f) continue
        val active = state.isActive(l)
        val text = textMeasurer.measure("L${Notation.timelineLabel(l)}", style.copy(color = if (active) Palette.ink else Palette.inkSoft))
        val chipSize = Size(text.size.width + padX * 2, text.size.height + padY * 2)
        // Sits in the gap above its row, where the world-space labels of branches go.
        val top = camera.toScreen(Offset(0f, l * Layout.ROW - Layout.GAP_Y / 2)).y - chipSize.height / 2
        if (top < camera.coveredTop - 40f || top + chipSize.height > size.height - camera.coveredBottom + 40f) continue
        if (top < lastBottom + 4f) continue
        lastBottom = top + chipSize.height
        val chip = Rect(Offset(margin, top), chipSize)
        val radius = CornerRadius(chipSize.height / 2)
        val ring = when (l) {
            in checked -> Palette.threat
            in mandatory -> Palette.gold
            else -> null
        }
        drawRoundRect(Palette.slab, chip.topLeft + Offset(2.dp.toPx(), 3.dp.toPx()), chipSize, radius)
        drawRoundRect(Palette.card, chip.topLeft, chipSize, radius)
        drawRoundRect(
            ring ?: Palette.presentEdge,
            chip.topLeft,
            chipSize,
            radius,
            style = Stroke(width = (if (ring != null) 2.5f else 1f).dp.toPx()),
        )
        drawText(text, topLeft = Offset(margin + padX, top + padY))
        rail.chips += l to chip
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

    /** For the timeline chips pinned to the screen's edge, so in screen units. */
    val rail = TextStyle(fontSize = 13.sp, fontFamily = SerifFamily, fontWeight = FontWeight.Bold)
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
    detailed: Boolean,
) {
    val state = session.state
    val side = state.sideToMove
    val mandatory = state.mandatoryTimelines.toSet()
    val ongoing = session.outcome == Outcome.Ongoing
    val canMove = ongoing || session.isExploring

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

    // A purple arrow on the present, beyond the edge where the side to move's next timeline would
    // appear, when that timeline would be active (and so could move the present).
    val nextTimeline = if (side == Side.WHITE) state.whiteTimelines + 1 else -(state.blackTimelines + 1)
    if (ongoing && state.isActive(nextTimeline)) {
        val x = state.present * Layout.COL + Layout.BOARD / 2
        if (side == Side.WHITE) {
            drawBranchArrow(Offset(x, state.timelines.keys.max() * Layout.ROW + Layout.BOARD + Layout.SQ * 1.6f), 1f)
        } else {
            drawBranchArrow(Offset(x, state.timelines.keys.min() * Layout.ROW - Layout.SQ * 2.6f), -1f)
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
        val label = textMeasurer.measure("L${Notation.timelineLabel(timeline.index)}", labels.timeline.copy(
            color = if (active) Palette.ink else Palette.inkSoft,
        ))
        for (t in timeline.start..timeline.end) {
            val rect = Layout.boardRect(timeline.index, t)
            if (!rect.inflate(Layout.SQ * 2).overlaps(visible)) continue
            val board = timeline.boardAt(t)!!
            val playable = canMove && t == timeline.end && Side.toMoveAt(t) == side
            drawBoardCard(rect, Side.toMoveAt(t), playable, must = timeline.index in mandatory)

            drawBoard(board, rect.topLeft, glyphs, detailed) { x, y ->
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
            val captionBottom = rect.top - if (playable) Layout.SQ * 0.95f else Layout.SQ * 0.45f
            var captionLeft = rect.left
            if (timeline.parent != null && t == timeline.start) {
                // A branch is named above its first board, with that board's turn beside the name.
                drawText(label, topLeft = Offset(rect.left, captionBottom - label.size.height))
                captionLeft += label.size.width + Layout.SQ * 0.35f
            }
            drawText(caption, topLeft = Offset(captionLeft, captionBottom - caption.size.height))

            // Fade inactive timelines, and while a piece is selected, the boards it can't land on
            // (the opponent's, half a turn out of step).
            if (!active || (session.selected != null && Side.toMoveAt(t) != side)) {
                drawRect(Palette.floor.copy(alpha = 0.6f), rect.topLeft, rect.size)
            }
        }
        if (timeline.parent == null) {
            val labelX = timeline.start * Layout.COL - label.size.width - Layout.SQ * 0.9f
            val midY = timeline.index * Layout.ROW + Layout.BOARD / 2
            drawText(label, topLeft = Offset(labelX, midY - label.size.height / 2f))
        }
    }

    // Arrows for moves between boards, and for threats against the side to move's kings.
    for (move in turnMoves) {
        if (!move.isSpatial) drawCurvedArrow(Layout.squareCenter(move.from), Layout.squareCenter(move.to), Palette.travelArrow)
    }
    for (threat in threats) {
        drawCurvedArrow(Layout.squareCenter(threat.from), Layout.squareCenter(threat.to), Palette.threat)
    }
    // A check indicator on each timeline whose advancement lets a king be captured.
    for (l in threats.mapTo(HashSet()) { it.from.l }) {
        val rect = Layout.boardRect(l, state.timelines.getValue(l).end)
        val center = Offset(rect.right + Layout.SQ * 0.75f, rect.top - Layout.SQ * 0.75f)
        drawCircle(Palette.slab, Layout.SQ * 0.8f, center + Layout.SLAB * 0.6f)
        drawCircle(Palette.threat, Layout.SQ * 0.8f, center)
        val mark = textMeasurer.measure("!", labels.timeline.copy(color = Color.White))
        drawText(mark, topLeft = center - Offset(mark.size.width / 2f, mark.size.height / 2f))
    }
}

/** A block arrow from [base], pointing down ([direction] 1) or up (-1). */
private fun DrawScope.drawBranchArrow(base: Offset, direction: Float) {
    val shaft = Layout.SQ * 0.65f
    val head = Layout.SQ * 1.5f
    val neck = base.y + direction * Layout.SQ * 1.3f
    val tip = base.y + direction * Layout.SQ * 3f
    val path = Path().apply {
        moveTo(base.x - shaft, base.y)
        lineTo(base.x - shaft, neck)
        lineTo(base.x - head, neck)
        lineTo(base.x, tip)
        lineTo(base.x + head, neck)
        lineTo(base.x + shaft, neck)
        lineTo(base.x + shaft, base.y)
        close()
    }
    withTransform({ translate(Layout.SLAB.x, Layout.SLAB.y) }) { drawPath(path, Palette.slab) }
    drawPath(path, Palette.ribbon)
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
 * Squares and pieces of [board] with its top-left corner at [topLeft], in world units. Unless
 * [detailed], pieces are plain discs. [highlight] gives an optional overlay colour for each square
 * (file [x], rank [y]).
 */
internal fun DrawScope.drawBoard(
    board: Board,
    topLeft: Offset,
    glyphs: Map<PieceType, PieceGlyph>,
    detailed: Boolean = true,
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
        if (detailed) {
            drawPiece(glyphs.getValue(piece.type), piece.side, center)
        } else {
            // Too small to read a glyph: a disc in the piece's colour keeps the position legible.
            drawCircle(if (piece.side == Side.WHITE) Color.White else Palette.ink, Layout.SQ * 0.32f, center)
        }
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
