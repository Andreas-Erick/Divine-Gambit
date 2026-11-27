package com.andreaserick.multiversechess.ui

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.IntSize
import com.andreaserick.multiversechess.engine.BOARD_SIZE
import com.andreaserick.multiversechess.engine.BoardId
import com.andreaserick.multiversechess.engine.GameSession
import com.andreaserick.multiversechess.engine.GameState
import com.andreaserick.multiversechess.engine.Pos
import kotlin.math.floor
import kotlin.math.min

/**
 * World-space layout: timeline `l` is row `l` (negative rows above), half-move `t` is column `t`
 * (or, in [Columns.history], one column per full turn). Positions take the [Columns] in use.
 */
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

    fun boardTopLeft(l: Int, t: Int, columns: Columns = Columns.STANDARD) = Offset(columns.x(t), l * ROW)

    fun boardRect(l: Int, t: Int, columns: Columns = Columns.STANDARD) = Rect(boardTopLeft(l, t, columns), Size(BOARD, BOARD))

    /** White is at the bottom of every board, so rank 8 is the top row. */
    fun squareTopLeft(p: Pos, columns: Columns = Columns.STANDARD) =
        boardTopLeft(p.l, p.t, columns) + Offset(p.x * SQ, (BOARD_SIZE - 1 - p.y) * SQ)

    fun squareCenter(p: Pos, columns: Columns = Columns.STANDARD) = squareTopLeft(p, columns) + Offset(SQ / 2, SQ / 2)

    fun hitTest(world: Offset, columns: Columns = Columns.STANDARD): Pos? {
        val t = columns.halfMoveAt(world.x)
        val l = floor(world.y / ROW).toInt()
        val local = world - boardTopLeft(l, t, columns)
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

/**
 * Which column each half-move is drawn in: normally one per half-move, or in History view only the
 * half-moves of one side, one column per full turn, so the other side's boards leave no gaps.
 */
internal class Columns private constructor(private val parity: Int?) {
    /** Left edge of half-move [t]'s column. In History view, a hidden half-move shares the column before it. */
    fun x(t: Int): Float = (if (parity == null) t else Math.floorDiv(t - parity, 2)) * Layout.COL

    /** The half-move whose column contains world x-coordinate [x]. */
    fun halfMoveAt(x: Float): Int {
        val column = floor(x / Layout.COL).toInt()
        return if (parity == null) column else parity + 2 * column
    }

    /** Whether half-move [t] has a column of its own. */
    fun hasColumn(t: Int): Boolean = parity == null || Math.floorMod(t, 2) == parity

    companion object {
        val STANDARD = Columns(null)

        /** One column per full turn, for the half-moves with the same side to move as [t]. */
        fun history(t: Int) = Columns(Math.floorMod(t, 2))
    }
}

/** Pan and zoom of the multiverse view. Screen position = world * scale + offset. */
@Stable
class CameraState {
    var offset by mutableStateOf(Offset.Zero)
    var scale by mutableFloatStateOf(1f)
    private var size by mutableStateOf(IntSize.Zero)

    /**
     * The view's size in pixels. When it changes once the camera is placed (a tablet turned, or the
     * app resized beside another), whatever was in the middle of the view stays in the middle.
     */
    var viewSize: IntSize
        get() = size
        set(value) {
            val old = size
            if (initialized && old.width > 0 && old.height > 0 && value != old) {
                offset += Offset((value.width - old.width) / 2f, (value.height - old.height) / 2f)
            }
            size = value
        }

    /** Parts of the view covered by overlaid controls, in pixels; focusing centers between them. */
    var coveredTop by mutableFloatStateOf(0f)
    var coveredBottom by mutableFloatStateOf(0f)

    /** Everything drawn, in world units, so the camera can always zoom out far enough to see it all. */
    var worldBounds = Rect.Zero

    /** When true, camera focus/frame transitions jump directly instead of gliding. */
    var reducedMotion = false

    private val hasSize get() = viewSize.width > 0 && viewSize.height > 0
    /** Whether the camera has been placed yet (the first focus jumps there instead of gliding). */
    var initialized = false
        private set

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

    /** Gliding or sliding on its own, as opposed to still or held by a finger. */
    private var moving = false

    /** The last touch landed while the view was moving: it stops the view, and does nothing else. */
    private var touchStoppedMotion = false

    /** A finger has come down; note whether it caught the view moving. */
    fun touched() {
        touchStoppedMotion = moving
    }

    /** Whether the tap now ending only stopped the view (so it shouldn't also pick a square); asks once. */
    fun tapStoppedMotion(): Boolean = touchStoppedMotion.also { touchStoppedMotion = false }

    /** Stops any glide or fling, because the player has taken hold of the view. */
    suspend fun stop() = mutex.mutate(MutatePriority.UserInput) {}

    /** Keeps the view sliding after a pan is released, slowing to a stop. */
    suspend fun fling(velocity: Offset, decay: DecayAnimationSpec<Offset>) = mutex.mutate {
        moving = true
        try {
            AnimationState(Offset.VectorConverter, offset, velocity).animateDecay(decay) { offset = value }
        } finally {
            moving = false
        }
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
     * shrink squares below [minSquare] pixels, it shows [fallback] instead. Squares are never made
     * bigger than [maxSquare] pixels, so a big tablet still shows what's around the area.
     */
    suspend fun focus(area: Rect, resetZoom: Boolean = false, minSquare: Float = 0f, fallback: Rect = area, maxSquare: Float = Float.POSITIVE_INFINITY) {
        if (!hasSize) return
        val shown = if (fitScale(area, 0.62f) * Layout.SQ < minSquare) fallback else area
        // A single board fills most of the width (clear of the tool buttons at the side), so its
        // squares are big enough to aim at; several
        // leave room around them, with neighbouring boards in sight.
        val single = shown.width <= Layout.BOARD + 1f
        val fit = fitScale(shown, if (single) 0.74f else 0.62f).coerceAtMost(maxSquare / Layout.SQ).coerceIn(minScale, MAX_SCALE)
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
        if (!initialized || reducedMotion) {
            initialized = true
            scale = targetScale
            offset = targetOffset
            return
        }
        mutex.mutate {
            val startOffset = offset
            val startScale = scale
            moving = true
            try {
                animate(0f, 1f, animationSpec = tween(520, easing = FastOutSlowInEasing)) { f, _ ->
                    scale = startScale + (targetScale - startScale) * f
                    offset = startOffset + (targetOffset - startOffset) * f
                }
            } finally {
                moving = false
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

/**
 * Views that keep one slice of the multiverse around a board and hide the rest, so a piece's moves
 * can be seen without the opponent's boards in the way.
 */
enum class SliceView {
    /** The board's timeline through time, with only the boards of the same side to move. */
    HISTORY,

    /** The board's moment across every timeline. */
    PARALLEL;

    fun shows(anchor: BoardId, l: Int, t: Int): Boolean = when (this) {
        HISTORY -> l == anchor.l && Math.floorMod(t - anchor.t, 2) == 0
        PARALLEL -> t == anchor.t
    }

    /** History view closes up the gaps the hidden half-moves leave. */
    internal fun columns(anchor: BoardId): Columns = if (this == HISTORY) Columns.history(anchor.t) else Columns.STANDARD
}

/** The board a slice view is built around: the selected piece's, or the first one to play. */
internal fun GameSession.sliceAnchor(): BoardId =
    selected?.boardId ?: state.focusBoard().let { (l, t) -> BoardId(l, t) }

/** The boards [view] keeps around [anchor], in world units. */
internal fun GameState.sliceArea(view: SliceView, anchor: BoardId): Rect =
    timelines.values.flatMap { timeline -> (timeline.start..timeline.end).map { timeline.index to it } }
        .filter { (l, t) -> view.shows(anchor, l, t) }
        .map { (l, t) -> Layout.boardRect(l, t, view.columns(anchor)) }
        .reduce(::union)

/** Every board of [l], from its first to its latest. */
internal fun GameState.timelineArea(l: Int): Rect {
    val timeline = timelines.getValue(l)
    return union(Layout.boardRect(l, timeline.start), Layout.boardRect(l, timeline.end))
}

/** The whole multiverse, with room for labels and the arrows into the future. */
internal fun GameState.overviewArea(): Rect =
    timelines.keys.map { timelineArea(it) }.reduce(::union).inflate(Layout.SQ * 2)
