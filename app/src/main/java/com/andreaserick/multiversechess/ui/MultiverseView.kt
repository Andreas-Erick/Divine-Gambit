package com.andreaserick.multiversechess.ui

import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.andreaserick.multiversechess.engine.BOARD_SIZE
import com.andreaserick.multiversechess.engine.Board
import com.andreaserick.multiversechess.engine.BoardId
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.GameState
import com.andreaserick.multiversechess.engine.Move
import com.andreaserick.multiversechess.engine.Notation
import com.andreaserick.multiversechess.engine.Outcome
import com.andreaserick.multiversechess.engine.PieceType
import com.andreaserick.multiversechess.engine.Pos
import com.andreaserick.multiversechess.engine.Side
import com.andreaserick.multiversechess.engine.Timeline
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Precomputed per-position highlights and timeline sets, so pan and zoom frames allocate nothing:
 * for each board with highlights, one [SquareMark] per square (file × 8 + rank), 0 for none.
 */
private class HighlightSets(
    val turnMoves: List<Move>,
    val targetSquares: Set<Pos>,
    val checkedTimelines: Set<Int>,
    val marks: Map<BoardId, IntArray>,
    val mandatory: Set<Int>,
    val timelinesInOrder: List<Timeline>,
)

/** What a square is highlighted for, most important first. */
private object SquareMark {
    const val NONE = 0
    const val SELECTED = 1
    const val TARGET = 2
    const val TRAVEL = 3
    const val RETURNED = 4
    const val RECENT = 5
}

private fun highlightSets(session: GameSession, targets: List<Move>, threats: List<Move>): HighlightSets {
    val moves = session.lastTurn + session.state.turnMoves
    val marks = HashMap<BoardId, IntArray>()
    fun mark(pos: Pos?, kind: Int) {
        if (pos == null) return
        val squares = marks.getOrPut(pos.boardId) { IntArray(BOARD_SIZE * BOARD_SIZE) }
        val i = pos.x * BOARD_SIZE + pos.y
        if (squares[i] == SquareMark.NONE || kind < squares[i]) squares[i] = kind
    }
    for (move in moves) {
        if (move.isSpatial) {
            mark(move.from.copy(t = move.from.t + 1), SquareMark.RECENT)
            mark(move.to.copy(t = move.to.t + 1), SquareMark.RECENT)
        } else {
            mark(move.from, SquareMark.TRAVEL)
            mark(move.to, SquareMark.TRAVEL)
        }
    }
    mark(session.returned, SquareMark.RETURNED)
    for (target in targets) mark(target.to, SquareMark.TARGET)
    mark(session.selected, SquareMark.SELECTED)
    return HighlightSets(
        turnMoves = moves,
        targetSquares = targets.mapTo(HashSet()) { it.to },
        checkedTimelines = threats.mapTo(HashSet()) { it.from.l },
        marks = marks,
        mandatory = session.state.mandatoryTimelines.toSet(),
        timelinesInOrder = session.state.timelines.values.sortedBy { it.index },
    )
}

/**
 * Text measured once and kept, since measuring is far too slow to do for every label on every
 * frame. Keyed by [key]; made again only when the styles change (their colours follow the palette).
 */
private class TextCache(private val measurer: TextMeasurer) {
    private val kept = HashMap<Long, TextLayoutResult>()

    fun get(key: Long, text: String, style: TextStyle): TextLayoutResult = kept.getOrPut(key) { measurer.measure(text, style) }

    companion object {
        fun timeline(l: Int, active: Boolean) = (1L shl 40) or ((l + 100_000L) shl 1) or (if (active) 1L else 0L)
        fun turn(t: Int) = (2L shl 40) or t.toLong()
        fun rail(l: Int, active: Boolean, checked: Boolean) = (3L shl 40) or ((l + 100_000L) shl 2) or (if (active) 1L else 0L) or (if (checked) 2L else 0L)
        const val CHECK = 4L shl 40
    }
}

@Composable
fun MultiverseView(
    session: GameSession,
    camera: CameraState,
    onTap: (Pos) -> Unit,
    modifier: Modifier = Modifier,
    slice: SliceView? = null,
    /** Drawn behind the boards instead of the plain floor, across the whole view. */
    backdrop: (DrawScope.() -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val currentSession by rememberUpdatedState(session)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentSlice by rememberUpdatedState(slice)
    camera.reducedMotion = LocalReducedMotion.current
    val targets = remember(session) { session.targets }
    val threats = remember(session) { session.state.shownThreats() }
    val highlights = remember(session, targets, threats) { highlightSets(session, targets, threats) }
    val glyphs = remember(textMeasurer, density) { measurePieceGlyphs(textMeasurer, density) }
    // Label colours follow the palette into the Inferno and back.
    val hot = Palette.heat > 0.5f
    val labels = remember(textMeasurer, density, hot) { LabelStyles(density) }
    val texts = remember(textMeasurer, labels) { TextCache(textMeasurer) }
    val spoken = remember(session) { describeForScreenReaders(session) }
    val rail = remember { TimelineRail() }
    val scratchA = remember { Path() }
    val scratchB = remember { Path() }
    camera.worldBounds = remember(session.state) { session.state.overviewArea() }

    Canvas(
        modifier
            .clipToBounds()
            .onSizeChanged { camera.viewSize = it }
            // The board for screen readers: what's on it, and actions to pick a piece and move it.
            .semantics {
                contentDescription = spoken.description
                customActions = spoken.actions.map { (label, pos) -> CustomAccessibilityAction(label) { currentOnTap(pos); true } }
            }
            .pointerInput(camera) { detectCameraGestures(camera) }
            .pointerInput(camera) {
                detectTapGestures { tap ->
                    // Catching the view as it glides stops it; that tap isn't a move as well.
                    if (camera.tapStoppedMotion()) return@detectTapGestures
                    val state = currentSession.state
                    rail.hit(tap, minHeight = 48.dp.toPx(), slack = 8.dp.toPx())?.let { l ->
                        scope.launch { camera.frame(state.timelineArea(l), TIMELINE_MIN_BOARD) }
                        return@detectTapGestures
                    }
                    val world = camera.toWorld(tap)
                    val anchor = currentSession.sliceAnchor()
                    val columns = currentSlice?.columns(anchor) ?: Columns.STANDARD
                    val pos = Layout.hitTest(world, columns)
                    // Boards hidden by a slice view can't be played on.
                    val hidden = pos != null && currentSlice?.shows(anchor, pos.l, pos.t) == false
                    if (hidden) return@detectTapGestures
                    if (pos != null && state.boardAt(pos.l, pos.t) != null) {
                        // Squares too small to aim at: zoom in on the board instead of picking one.
                        if (Layout.SQ * camera.scale < MIN_TAPPABLE_SQUARE.toPx()) {
                            scope.launch { camera.frame(Layout.boardRect(pos.l, pos.t, columns)) }
                        } else {
                            currentOnTap(pos)
                        }
                        return@detectTapGestures
                    }
                    // Around a slice view's boards there's nothing else to frame.
                    if (currentSlice != null) return@detectTapGestures
                    Layout.timelineAt(world, state)?.let { l ->
                        scope.launch { camera.frame(state.timelineArea(l), TIMELINE_MIN_BOARD) }
                    }
                }
            },
    ) {
        if (backdrop != null) backdrop() else drawRect(Palette.floor)
        val visible = Rect(camera.toWorld(Offset.Zero), camera.toWorld(Offset(size.width, size.height)))
        withTransform({
            translate(camera.offset.x, camera.offset.y)
            scale(camera.scale, camera.scale, pivot = Offset.Zero)
        }) {
            drawMultiverse(session, highlights, threats, glyphs, labels, texts, visible, camera.scale, slice, scratchA, scratchB)
        }
        drawTimelineRail(
            session.state, highlights, camera, rail, texts, labels.rail, slice, session.sliceAnchor(),
        )
    }
}

/** Squares smaller than this on screen are too small to tap reliably: a tap zooms in instead. */
private val MIN_TAPPABLE_SQUARE = 24.dp

/** The present is shown no smaller than this, so it can be played without zooming. */
internal val COMFORTABLE_SQUARE = 28.dp

/** What the board says to a screen reader, and the actions it offers: each a label and the square it taps. */
private class Spoken(val description: String, val actions: List<Pair<String, Pos>>)

private fun describeForScreenReaders(session: GameSession): Spoken {
    val state = session.state
    val side = state.sideToMove
    fun at(pos: Pos) = "${Notation.boardLabel(pos.l, pos.t)} ${pos.square}"
    val description = buildString {
        append("Multiverse: ${state.timelines.size} ${if (state.timelines.size == 1) "timeline" else "timelines"}, present turn ${Notation.turnNumber(state.present)}. ")
        if (session.outcome == Outcome.Ongoing) append("${side.displayName} to move") else append("The game is over")
        val left = state.mandatoryTimelines.size
        if (session.outcome == Outcome.Ongoing && left > 0) append(", $left ${if (left == 1) "board" else "boards"} to play")
        append('.')
        session.selected?.let { pos -> state.pieceAt(pos)?.let { append(" Selected: ${it.type.displayName.lowercase()} on ${at(pos)}.") } }
        if (state.threats().isNotEmpty() && session.outcome == Outcome.Ongoing) append(" Check.")
    }
    if ((session.outcome != Outcome.Ongoing && !session.isExploring) || session.isOpponentsTurn) return Spoken(description, emptyList())
    val actions = ArrayList<Pair<String, Pos>>()
    val selected = session.selected
    if (selected != null) {
        for (move in session.targets) {
            val taken = state.pieceAt(move.to)?.let { " taking the ${it.type.displayName.lowercase()}" }.orEmpty()
            actions += "Move to ${at(move.to)}$taken" to move.to
        }
        actions += "Deselect" to selected
    } else {
        for (timeline in state.playableTimelines(side)) {
            val t = timeline.end
            timeline.boardAt(t)?.forEachPiece { x, y, piece ->
                val pos = Pos(timeline.index, t, x, y)
                if (piece.side == side && state.legalMovesFrom(pos).isNotEmpty()) actions += "Select ${piece.type.displayName.lowercase()} on ${at(pos)}" to pos
            }
        }
    }
    return Spoken(description, actions)
}

/** Turn captions are drawn at least this tall on screen, as far as they can grow. */
private val MIN_CAPTION = 14.dp

/** When framing a long timeline, boards stay at least this fraction of the view's width. */
internal const val TIMELINE_MIN_BOARD = 0.24f

/**
 * Pinch to zoom and drag to pan, like detectTransformGestures, plus a fling when a one-finger
 * pan is released. Touching the view stops any glide or fling in progress.
 */
private suspend fun PointerInputScope.detectCameraGestures(camera: CameraState) {
    val decay = splineBasedDecay<Offset>(this)
    coroutineScope {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            camera.touched()
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

    /**
     * The chip nearest [screen], within [slack] of it sideways and at least [minHeight] tall for
     * aiming; where two chips' reach overlaps, the nearer wins.
     */
    fun hit(screen: Offset, minHeight: Float, slack: Float): Int? {
        var best: Int? = null
        var bestDistance = Float.MAX_VALUE
        for ((l, chip) in chips) {
            val reach = maxOf(chip.height, minHeight) / 2
            val distance = abs(chip.center.y - screen.y)
            if (screen.x in chip.left - slack..chip.right + slack && distance <= reach && distance < bestDistance) {
                best = l
                bestDistance = distance
            }
        }
        return best
    }
}

/**
 * Chips naming timelines whose own label is out of sight (scrolled off to the left, or too small
 * to read), pinned to the left edge in screen space. Tapping one frames that timeline. A gold ring
 * means the timeline must be played, a red one that it's in [checked].
 */
private fun DrawScope.drawTimelineRail(
    state: GameState,
    highlights: HighlightSets,
    camera: CameraState,
    rail: TimelineRail,
    texts: TextCache,
    style: TextStyle,
    slice: SliceView?,
    anchor: BoardId,
) {
    rail.chips.clear()
    val checked = highlights.checkedTimelines
    val columns = slice?.columns(anchor) ?: Columns.STANDARD
    val margin = 10.dp.toPx()
    val padX = 10.dp.toPx()
    val padY = 5.dp.toPx()
    val labelReadable = Layout.SQ * 0.75f * camera.scale >= 11.dp.toPx()
    val mandatory = highlights.mandatory
    var lastBottom = Float.NEGATIVE_INFINITY
    for (timeline in highlights.timelinesInOrder) {
        val l = timeline.index
        // History view keeps a single timeline.
        if (slice == SliceView.HISTORY && l != anchor.l) continue
        // Branches are named above their first board, the first timeline to the left of it.
        val labelLeft = columns.x(timeline.start) - if (timeline.parent == null) Layout.SQ * 3 else 0f
        if (labelReadable && camera.toScreen(Offset(labelLeft, 0f)).x >= 0f) continue
        val active = state.isActive(l)
        val inCheck = l in checked
        // A timeline in check says so in words as well as with its red ring.
        val text = texts.get(TextCache.rail(l, active, inCheck), (if (inCheck) "! " else "") + "L${Notation.timelineLabel(l)}", style.copy(color = if (active) Palette.ink else Palette.inkSoft))
        val chipSize = Size(text.size.width + padX * 2, text.size.height + padY * 2)
        // Sits in the gap above its row, where the world-space labels of branches go.
        val top = camera.toScreen(Offset(0f, l * Layout.ROW - Layout.GAP_Y / 2)).y - chipSize.height / 2
        if (top < camera.coveredTop - 40f || top + chipSize.height > size.height - camera.coveredBottom + 40f) continue
        if (top < lastBottom + 8.dp.toPx()) continue
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
    /** The system's text size, up to half again as large, since these labels share the board's space. */
    private val textScale = density.fontScale.coerceIn(1f, 1.5f)
    val caption = with(density) {
        TextStyle(color = Palette.inkSoft, fontSize = (Layout.SQ * 0.42f * textScale).toSp(), fontFamily = SerifFamily, fontWeight = FontWeight.SemiBold)
    }
    val timeline = with(density) {
        TextStyle(color = Palette.ink, fontSize = (Layout.SQ * 0.75f * textScale).toSp(), fontFamily = SerifFamily, fontWeight = FontWeight.Bold)
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
    highlights: HighlightSets,
    threats: List<Move>,
    glyphs: Map<PieceType, PieceGlyph>,
    labels: LabelStyles,
    texts: TextCache,
    visible: Rect,
    /** Screen pixels per world unit. */
    scale: Float,
    slice: SliceView?,
    scratchA: Path,
    scratchB: Path,
) {
    val state = session.state
    val anchor = session.sliceAnchor()
    fun shown(l: Int, t: Int) = slice?.shows(anchor, l, t) ?: true
    val columns = slice?.columns(anchor) ?: Columns.STANDARD
    val history = slice == SliceView.HISTORY
    val side = state.sideToMove
    val mandatory = highlights.mandatory
    val squarePx = Layout.SQ * scale
    // Glyphs once squares are big enough to read them; below a few pixels, a board is one tile.
    val detailed = squarePx >= 10f
    val tiny = squarePx < 4f
    // Lines at least a pixel wide on screen, however far out the view is zoomed.
    val hairline = 1f / scale
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

    // The present: a pale column with ruled edges (unless History view has hidden its half-move).
    if (columns.hasColumn(state.present)) {
        val presentLeft = columns.x(state.present) - Layout.GAP_X * 0.32f
        val presentWidth = Layout.BOARD + Layout.GAP_X * 0.64f
        drawRect(Palette.present, Offset(presentLeft, visible.top), Size(presentWidth, visible.height))
        for (x in floatArrayOf(presentLeft, presentLeft + presentWidth)) {
            drawLine(Palette.presentEdge, Offset(x, visible.top), Offset(x, visible.bottom), strokeWidth = maxOf(Layout.SQ * 0.12f, hairline))
        }
    }

    // Timeline ribbons: shadows first, then the ribbons, so boards sit on top of both. History view
    // keeps only its own timeline's ribbon, straight; Parallel view fades them all.
    for (pass in 0..1) {
        for (timeline in state.timelines.values) {
            if (history && timeline.index != anchor.l) continue
            val faded = slice == SliceView.PARALLEL
            val color = (if (pass == 0) Palette.slab else if (state.isActive(timeline.index)) Palette.ribbon else Palette.ribbonInactive)
                .let { if (faded) it.copy(alpha = 0.15f) else it }
            val shift = if (pass == 0) Layout.SLAB else Offset.Zero
            drawTimelineRibbon(timeline, columns, color, shift, arrow = ongoing, branch = !history, scratchA)
        }
    }

    // A teal arrow on the present, beyond the edge where the side to move's next timeline would
    // appear, when that timeline would be active (and so could move the present).
    val nextTimeline = if (side == Side.WHITE) state.whiteTimelines + 1 else -(state.blackTimelines + 1)
    if (ongoing && !history && !state.atTimelineLimit(side) && state.isActive(nextTimeline)) {
        val x = columns.x(state.present) + Layout.BOARD / 2
        if (side == Side.WHITE) {
            drawBranchArrow(Offset(x, state.timelines.keys.max() * Layout.ROW + Layout.BOARD + Layout.SQ * 1.6f), 1f, scratchA)
        } else {
            drawBranchArrow(Offset(x, state.timelines.keys.min() * Layout.ROW - Layout.SQ * 2.6f), -1f, scratchA)
        }
    }

    val turnMoves = highlights.turnMoves
    val targetSquares = highlights.targetSquares
    val margin = Layout.SQ * 2

    for (timeline in state.timelines.values) {
        // Rows out of sight are skipped whole, and within a row only the columns in sight are drawn.
        val rowTop = timeline.index * Layout.ROW
        if (rowTop + Layout.BOARD < visible.top - margin || rowTop > visible.bottom + margin) continue
        val active = state.isActive(timeline.index)
        val label = texts.get(TextCache.timeline(timeline.index, active), "L${Notation.timelineLabel(timeline.index)}", labels.timeline.copy(
            color = if (active) Palette.ink else Palette.inkSoft,
        ))
        val firstT = maxOf(timeline.start, columns.halfMoveAt(visible.left - margin) - 1)
        val lastT = minOf(timeline.end, columns.halfMoveAt(visible.right + margin) + 1)
        for (t in firstT..lastT) {
            if (!shown(timeline.index, t)) continue
            val rect = Layout.boardRect(timeline.index, t, columns)
            val board = timeline.boardAt(t)!!
            val playable = canMove && t == timeline.end && Side.toMoveAt(t) == side
            drawBoardCard(rect, Side.toMoveAt(t), playable, must = timeline.index in mandatory)

            val marks = highlights.marks[BoardId(timeline.index, t)]
            drawBoard(board, rect.topLeft, glyphs, detailed, tiny) { x, y ->
                when (marks?.get(x * BOARD_SIZE + y) ?: SquareMark.NONE) {
                    SquareMark.SELECTED -> Palette.selection
                    SquareMark.TARGET -> Palette.target
                    SquareMark.TRAVEL -> Palette.travel
                    SquareMark.RETURNED -> Palette.returned
                    SquareMark.RECENT -> Palette.lastMove
                    else -> null
                }
            }
            // Shapes as well as colours, so the marks can be told apart without colour (and in the
            // Inferno, where they all burn orange): a ring for the selection, a pip in the corner
            // for a piece just back from the Inferno, a cross on a threatened king.
            session.selected?.takeIf { it.l == timeline.index && it.t == t && !tiny }?.let { pos ->
                val inset = Layout.SQ * 0.06f
                drawRect(
                    Palette.ink,
                    Layout.squareTopLeft(pos, columns) + Offset(inset, inset),
                    Size(Layout.SQ - inset * 2, Layout.SQ - inset * 2),
                    style = Stroke(maxOf(Layout.SQ * 0.08f, hairline * 1.5f)),
                )
            }
            session.returned?.takeIf { it.l == timeline.index && it.t == t && !tiny }?.let { pos ->
                val corner = Layout.squareTopLeft(pos, columns) + Offset(Layout.SQ * 0.8f, Layout.SQ * 0.2f)
                drawCircle(Palette.slab, Layout.SQ * 0.14f, corner)
                drawCircle(Palette.gold, Layout.SQ * 0.1f, corner)
            }
            for (pos in targetSquares) {
                if (pos.l != timeline.index || pos.t != t) continue
                val center = Layout.squareCenter(pos, columns)
                if (board[pos.x, pos.y] == null) {
                    drawCircle(Palette.targetDot, Layout.SQ * 0.15f, center)
                } else {
                    drawCircle(Palette.targetDot, Layout.SQ * 0.44f, center, style = Stroke(Layout.SQ * 0.07f))
                }
            }
            for (threat in threats) {
                if (threat.to.l == timeline.index && threat.to.t == t) {
                    val square = Layout.squareTopLeft(threat.to, columns)
                    drawRect(Palette.threat.copy(alpha = 0.45f), square, Size(Layout.SQ, Layout.SQ))
                    if (!tiny) {
                        val a = Layout.SQ * 0.14f
                        val b = Layout.SQ - a
                        val stroke = maxOf(Layout.SQ * 0.07f, hairline)
                        drawLine(Palette.threat, square + Offset(a, a), square + Offset(b, b), stroke, StrokeCap.Round)
                        drawLine(Palette.threat, square + Offset(b, a), square + Offset(a, b), stroke, StrokeCap.Round)
                    }
                }
            }

            // Turn captions, kept readable on screen: when zoomed far out they're drawn larger
            // (up to a point) rather than shrinking to specks, and left out once boards are tiles.
            if (detailed) {
                val caption = texts.get(TextCache.turn(t), "T${Notation.turnNumber(t)}", labels.caption)
                val captionBottom = rect.top - if (playable) Layout.SQ * 0.95f else Layout.SQ * 0.45f
                var captionLeft = rect.left
                if (timeline.parent != null && t == timeline.start) {
                    // A branch is named above its first board, with that board's turn beside the name.
                    drawText(label, topLeft = Offset(rect.left, captionBottom - label.size.height))
                    captionLeft += label.size.width + Layout.SQ * 0.35f
                }
                val grow = (MIN_CAPTION.toPx() / (caption.size.height * scale)).coerceIn(1f, 2.2f)
                withTransform({ scale(grow, grow, pivot = Offset(captionLeft, captionBottom)) }) {
                    drawText(caption, topLeft = Offset(captionLeft, captionBottom - caption.size.height))
                }
            }

            // Fade inactive timelines, and while a piece is selected, the boards it can't land on
            // (the opponent's, half a turn out of step).
            if (!active || (session.selected != null && Side.toMoveAt(t) != side)) {
                drawRect(Palette.floor.copy(alpha = 0.6f), rect.topLeft, rect.size)
            }
        }
        if (timeline.parent == null && (!history || timeline.index == anchor.l)) {
            val labelX = columns.x(timeline.start) - label.size.width - Layout.SQ * 0.9f
            val midY = timeline.index * Layout.ROW + Layout.BOARD / 2
            drawText(label, topLeft = Offset(labelX, midY - label.size.height / 2f))
        }
    }

    // Arrows for moves between boards, and for threats against the side to move's kings.
    for (move in turnMoves) {
        if (!shown(move.from.l, move.from.t) || !shown(move.to.l, move.to.t)) continue
        if (!move.isSpatial) {
            drawCurvedArrow(Layout.squareCenter(move.from, columns), Layout.squareCenter(move.to, columns), Palette.travelArrow, scratchA, scratchB)
        }
    }
    for (threat in threats) {
        if (slice != null && (!shown(threat.from.l, threat.from.t) || !shown(threat.to.l, threat.to.t))) continue
        drawCurvedArrow(Layout.squareCenter(threat.from, columns), Layout.squareCenter(threat.to, columns), Palette.threat, scratchA, scratchB)
    }
    // A check indicator on each timeline whose advancement lets a king be captured.
    for (l in highlights.checkedTimelines) {
        val end = state.timelines.getValue(l).end
        if (!shown(l, end)) continue
        val rect = Layout.boardRect(l, end, columns)
        val center = Offset(rect.right + Layout.SQ * 0.75f, rect.top - Layout.SQ * 0.75f)
        drawCircle(Palette.slab, Layout.SQ * 0.8f, center + Layout.SLAB * 0.6f)
        drawCircle(Palette.threat, Layout.SQ * 0.8f, center)
        val mark = texts.get(TextCache.CHECK, "!", labels.timeline.copy(color = Palette.onThreat))
        drawText(mark, topLeft = center - Offset(mark.size.width / 2f, mark.size.height / 2f))
    }
}

/** A block arrow from [base], pointing down ([direction] 1) or up (-1). */
private fun DrawScope.drawBranchArrow(base: Offset, direction: Float, path: Path) {
    val shaft = Layout.SQ * 0.65f
    val head = Layout.SQ * 1.5f
    val neck = base.y + direction * Layout.SQ * 1.3f
    val tip = base.y + direction * Layout.SQ * 3f
    path.reset()
    path.moveTo(base.x - shaft, base.y)
    path.lineTo(base.x - shaft, neck)
    path.lineTo(base.x - head, neck)
    path.lineTo(base.x, tip)
    path.lineTo(base.x + head, neck)
    path.lineTo(base.x + shaft, neck)
    path.lineTo(base.x + shaft, base.y)
    path.close()
    withTransform({ translate(Layout.SLAB.x, Layout.SLAB.y) }) { drawPath(path, Palette.slab) }
    drawPath(path, Palette.ribbon)
}

/**
 * A timeline's ribbon: a thick band through its boards, a curve from the board it branched from
 * (with [branch]), and an arrow pointing into the future past its latest board.
 */
private fun DrawScope.drawTimelineRibbon(
    timeline: Timeline,
    columns: Columns,
    color: Color,
    shift: Offset,
    arrow: Boolean,
    branch: Boolean,
    scratch: Path,
) {
    val l = timeline.index
    val start = timeline.start
    val parent = timeline.parent?.takeIf { branch }
    // In History view the band starts at the first board that has a column.
    val first = if (columns.hasColumn(start)) start else start + 1
    if (first > timeline.end) return
    val h = Layout.RIBBON
    val midY = l * Layout.ROW + Layout.BOARD / 2 + shift.y
    val left = columns.x(first) + shift.x
    val right = columns.x(timeline.end) + Layout.BOARD + shift.x
    drawRect(color, Offset(left, midY - h / 2), Size(right - left, h))

    if (arrow) {
        val shaft = Layout.GAP_X * 0.35f
        val headLength = Layout.GAP_X * 0.55f
        drawRect(color, Offset(right, midY - h / 2), Size(shaft, h))
        scratch.reset()
        scratch.moveTo(right + shaft, midY - h * 0.95f)
        scratch.lineTo(right + shaft + headLength, midY)
        scratch.lineTo(right + shaft, midY + h * 0.95f)
        scratch.close()
        drawPath(scratch, color)
    }

    if (parent != null) {
        val from = Offset(columns.x(start - 1) + Layout.BOARD * 0.5f, parent * Layout.ROW + Layout.BOARD / 2) + shift
        val to = Offset(columns.x(start) + Layout.BOARD * 0.5f, midY)
        scratch.reset()
        scratch.moveTo(from.x, from.y)
        scratch.cubicTo(from.x + Layout.COL * 0.55f, from.y, to.x - Layout.COL * 0.55f, to.y, to.x, to.y)
        drawPath(scratch, color, style = Stroke(width = h, cap = StrokeCap.Butt))
    }
}

/**
 * The frame around a board. Its colour says whose move the board is (pale White, near-black Black).
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
    /** Squares only a few pixels across: the board is drawn as one tile, with its pieces as dots. */
    tiny: Boolean = false,
    highlight: (x: Int, y: Int) -> Color? = { _, _ -> null },
) {
    val squareSize = Size(Layout.SQ, Layout.SQ)
    if (tiny) {
        drawRect(lerp(Palette.lightSquare, Palette.darkSquare, 0.5f), topLeft, Size(Layout.BOARD, Layout.BOARD))
    } else {
        for (x in 0 until BOARD_SIZE) for (y in 0 until BOARD_SIZE) {
            val square = topLeft + Offset(x * Layout.SQ, (BOARD_SIZE - 1 - y) * Layout.SQ)
            drawRect(if ((x + y) % 2 == 0) Palette.darkSquare else Palette.lightSquare, square, squareSize)
            highlight(x, y)?.let { drawRect(it, square, squareSize) }
        }
    }
    board.forEachPiece { x, y, piece ->
        val center = topLeft + Offset((x + 0.5f) * Layout.SQ, (BOARD_SIZE - y - 0.5f) * Layout.SQ)
        if (detailed) {
            drawPiece(glyphs.getValue(piece.type), piece.side, center)
        } else {
            // Too small to read a glyph: a disc in the piece's colour keeps the position legible.
            drawCircle(if (piece.side == Side.WHITE) Color.White else Palette.pieceDark, Layout.SQ * 0.32f, center)
        }
    }
}

private fun DrawScope.drawPiece(glyph: PieceGlyph, side: Side, center: Offset) {
    fun TextLayoutResult.topLeft() = center - Offset(size.width / 2f, size.height / 2f)
    val (fill, outline) = if (side == Side.WHITE) Color.White to Palette.pieceDark else Palette.pieceDark to Color(0xFFF4EFE4)
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
        // The hollow symbols' inner lines are too heavy on a dark piece, so outline it with a halo:
        // the solid symbol stroked in the pale colour, then filled dark on top.
        drawText(glyph.body, color = outline, topLeft = glyph.body.topLeft(), drawStyle = Stroke(width = Layout.SQ * 0.07f, join = StrokeJoin.Round))
        drawText(glyph.body, color = fill, topLeft = glyph.body.topLeft())
    }
}

/** A gently curved arrow with a pale outline, so it reads over boards and the floor alike. */
private fun DrawScope.drawCurvedArrow(from: Offset, to: Offset, color: Color, shaft: Path, head: Path) {
    val length = hypot(to.x - from.x, to.y - from.y)
    if (length < 1f) return
    val normal = Offset(-(to.y - from.y) / length, (to.x - from.x) / length)
    val control = (from + to) / 2f + normal * (length * 0.12f)
    val headLength = Layout.SQ * 0.75f
    // Stop the shaft where the head begins, along the curve's final direction.
    val angle = atan2(to.y - control.y, to.x - control.x)
    val direction = Offset(cos(angle), sin(angle))
    val shaftEnd = to - direction * (headLength * 0.8f)
    shaft.reset()
    shaft.moveTo(from.x, from.y)
    shaft.quadraticTo(control.x, control.y, shaftEnd.x, shaftEnd.y)
    val side = Offset(-direction.y, direction.x) * (headLength * 0.55f)
    val base = to - direction * headLength
    head.reset()
    head.moveTo(to.x, to.y)
    head.lineTo(base.x + side.x, base.y + side.y)
    head.lineTo(base.x - side.x, base.y - side.y)
    head.close()
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
