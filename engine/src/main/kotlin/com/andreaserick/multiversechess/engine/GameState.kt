package com.andreaserick.multiversechess.engine

/**
 * One timeline: a run of consecutive boards starting at half-move [start].
 * [parent] is the timeline it branched from (null for the original timeline).
 */
class Timeline(val index: Int, val start: Int, val boards: List<Board>, val parent: Int? = null) {
    init {
        require(boards.isNotEmpty())
    }

    val end: Int get() = start + boards.size - 1
    val last: Board get() = boards.last()

    fun boardAt(t: Int): Board? = if (t in start..end) boards[t - start] else null

    fun appended(board: Board): Timeline = Timeline(index, start, boards + board, parent)
}

/**
 * The whole multiverse plus whose turn it is and the moves already made this turn.
 *
 * Rules implemented (see README for the full write-up):
 * - A move lands on any existing board where the mover is to move. Landing on the last board of
 *   another timeline advances that timeline; landing on an earlier board creates a new timeline.
 * - White's new timelines get indices +1, +2, ... and Black's -1, -2, ...
 * - A timeline is active if its creator has made at most one more timeline than the opponent.
 * - The present is the earliest end among active timelines. On their turn a player must move on
 *   every board at the present, may move on other boards they can play, and may submit once the
 *   present has passed to the opponent and none of their kings can be captured.
 * - With a [timelineLimit], each player may create at most that many timelines; once a player has,
 *   their moves that would branch a new one are not allowed (and so don't threaten anything).
 */
class GameState(
    val timelines: Map<Int, Timeline>,
    val sideToMove: Side,
    val turnMoves: List<Move> = emptyList(),
    val timelineLimit: Int? = null,
) {
    val timelineIndices: List<Int> by lazy { timelines.keys.sorted() }

    /** Number of timelines White has created (they are numbered 1..n). */
    val whiteTimelines: Int by lazy { timelines.keys.count { it > 0 } }

    /** Number of timelines Black has created (they are numbered -1..-n). */
    val blackTimelines: Int by lazy { timelines.keys.count { it < 0 } }

    fun isActive(l: Int): Boolean = when {
        l == 0 -> true
        l > 0 -> l <= blackTimelines + 1
        else -> -l <= whiteTimelines + 1
    }

    /** Half-move index of the present. */
    val present: Int by lazy { timelines.values.filter { isActive(it.index) }.minOf { it.end } }

    /** Timelines whose present board the side to move still has to play. */
    val mandatoryTimelines: List<Int> by lazy {
        if (Side.toMoveAt(present) != sideToMove) {
            emptyList()
        } else {
            timelineIndices.filter { isActive(it) && timelines.getValue(it).end == present }
        }
    }

    val canSubmit: Boolean get() = turnMoves.isNotEmpty() && Side.toMoveAt(present) != sideToMove

    fun boardAt(l: Int, t: Int): Board? = timelines[l]?.boardAt(t)

    fun pieceAt(pos: Pos): Piece? = boardAt(pos.l, pos.t)?.get(pos.x, pos.y)

    /** Whether [side] may move on board ([l], [t]): it must be the last board of its timeline. */
    fun isPlayable(l: Int, t: Int, side: Side = sideToMove): Boolean {
        val timeline = timelines[l] ?: return false
        return timeline.end == t && Side.toMoveAt(t) == side
    }

    fun playableTimelines(side: Side = sideToMove): List<Timeline> =
        timelineIndices.map { timelines.getValue(it) }.filter { Side.toMoveAt(it.end) == side }

    /** Legal moves for the piece at [from], or empty if it can't move this turn. */
    fun legalMovesFrom(from: Pos): List<Move> {
        val piece = pieceAt(from) ?: return emptyList()
        if (piece.side != sideToMove || !isPlayable(from.l, from.t)) return emptyList()
        return generateMoves(from, capturesOnly = false)
    }

    /** Every move the side to move can make right now. */
    fun allMoves(): List<Move> = buildList {
        for (timeline in playableTimelines()) {
            for ((square, piece) in timeline.last.pieces()) {
                if (piece.side == sideToMove) {
                    addAll(generateMoves(Pos(timeline.index, timeline.end, square.x, square.y), capturesOnly = false))
                }
            }
        }
    }

    fun apply(move: Move): GameState {
        val source = timelines.getValue(move.from.l)
        require(source.end == move.from.t && Side.toMoveAt(move.from.t) == sideToMove) { "Board is not playable: $move" }
        val board = source.last
        val piece = requireNotNull(board[move.from.square]) { "No piece at ${move.from}" }
        val movedPiece = piece.copy(type = move.promotion ?: piece.type, moved = true)
        val updated = timelines.toMutableMap()

        if (move.isSpatial) {
            val changes = mutableListOf<Pair<Square, Piece?>>(move.from.square to null, move.to.square to movedPiece)
            move.enPassantCapture?.let { changes += it to null }
            move.castleRook?.let { (rookFrom, rookTo) ->
                changes += rookFrom to null
                changes += rookTo to board[rookFrom]!!.copy(moved = true)
            }
            val enPassant = if (move.doubleStep) Square(move.from.x, (move.from.y + move.to.y) / 2) else null
            updated[source.index] = source.appended(board.with(changes, enPassant))
        } else {
            val destination = timelines.getValue(move.to.l)
            val arrived = destination.boardAt(move.to.t)!!.with(listOf(move.to.square to movedPiece))
            updated[source.index] = source.appended(board.with(listOf(move.from.square to null)))
            if (move.to.l != move.from.l && destination.end == move.to.t) {
                updated[destination.index] = destination.appended(arrived)
            } else {
                val index = newTimelineIndex(piece.side)
                updated[index] = Timeline(index, move.to.t + 1, listOf(arrived), parent = destination.index)
            }
        }
        return GameState(updated, sideToMove, turnMoves + move, timelineLimit)
    }

    fun submit(): GameState {
        check(canSubmit) { "Turn is not complete" }
        return GameState(timelines, sideToMove.opponent, timelineLimit = timelineLimit)
    }

    /** Whether [side] has created as many timelines as the [timelineLimit] allows. */
    fun atTimelineLimit(side: Side): Boolean =
        timelineLimit != null && (if (side == Side.WHITE) whiteTimelines else blackTimelines) >= timelineLimit

    private fun newTimelineIndex(side: Side): Int =
        if (side == Side.WHITE) whiteTimelines + 1 else -(blackTimelines + 1)

    /** Moves by [attacker] (from boards [attacker] could play) that would capture a royal piece. */
    fun kingCaptures(attacker: Side, firstOnly: Boolean = false): List<Move> =
        captures(attacker, firstOnly) { it.type.isRoyal }

    /** Moves by [attacker] (from boards [attacker] could play) that would capture a piece matching [target]. */
    internal fun captures(attacker: Side, firstOnly: Boolean = false, target: (Piece) -> Boolean = { true }): List<Move> {
        val found = ArrayList<Move>()
        for (timeline in playableTimelines(attacker)) {
            for ((square, piece) in timeline.last.pieces()) {
                if (piece.side != attacker) continue
                for (move in generateMoves(Pos(timeline.index, timeline.end, square.x, square.y), capturesOnly = true)) {
                    val taken = pieceAt(move.to)
                    if (taken != null && taken.side != attacker && target(taken)) {
                        found += move
                        if (firstOnly) return found
                    }
                }
            }
        }
        return found
    }

    /** The piece [move] would take, if any. */
    fun capturedBy(move: Move): Piece? =
        pieceAt(move.to) ?: move.enPassantCapture?.let { boardAt(move.from.l, move.from.t)?.get(it) }

    /**
     * A copy with [piece] put on [pos], which must be on the latest board of its timeline: how a
     * piece freed from the [Inferno] returns.
     */
    fun withPiece(pos: Pos, piece: Piece): GameState {
        val timeline = timelines.getValue(pos.l)
        require(timeline.end == pos.t) { "Not a latest board: $pos" }
        val board = timeline.last.with(listOf(pos.square to piece), timeline.last.enPassant)
        val updated = Timeline(timeline.index, timeline.start, timeline.boards.dropLast(1) + board, timeline.parent)
        return GameState(timelines + (pos.l to updated), sideToMove, turnMoves, timelineLimit)
    }

    /** True if, were [side] to submit now, the opponent could capture one of [side]'s kings. */
    fun isInCheck(side: Side): Boolean = kingCaptures(side.opponent, firstOnly = true).isNotEmpty()

    /**
     * [isInCheck] for the side to move, for a position reached from [parent] by that side's moves,
     * where [parentInCheck] is the parent's: much quicker, as only the new boards need looking at.
     *
     * Moves never change a board, they only add boards, and only boards with the opponent to move
     * (a moved piece arrives on the next half-move). So a capture the parent didn't allow must
     * start on a new board, or travel through or onto one: a line of attack used to stop where a
     * board didn't exist. Pieces on new boards are looked at in full; elsewhere, only the
     * directions whose line crosses a new board.
     */
    internal fun isInCheckAfter(parent: GameState, parentInCheck: Boolean): Boolean {
        if (parentInCheck) return true
        val side = sideToMove
        val attacker = side.opponent
        val added = ArrayList<BoardId>()
        for ((l, timeline) in timelines) {
            val before = parent.timelines[l]
            if (before === timeline) continue
            val first = if (before == null) timeline.start else before.end + 1
            for (t in first..timeline.end) added += BoardId(l, t)
        }
        if (added.isEmpty()) return false
        for (timeline in playableTimelines(attacker)) {
            val here = BoardId(timeline.index, timeline.end)
            val lines = if (here in added) null else Lines.between(here, added)
            // Nothing on a board with no line to a new board can make a new capture, and a pawn
            // only captures a board or two away.
            if (lines != null && lines.isEmpty) continue
            var found = false
            timeline.last.forEachPiece { x, y, piece ->
                if (found || piece.side != attacker) return@forEachPiece
                if (lines != null && piece.type.isPawnLike && !lines.nearby) return@forEachPiece
                for (move in generateMoves(Pos(timeline.index, timeline.end, x, y), capturesOnly = true, through = lines)) {
                    val taken = pieceAt(move.to)
                    if (taken != null && taken.side == side && taken.type.isRoyal) {
                        found = true
                        return@forEachPiece
                    }
                }
            }
            if (found) return true
        }
        return false
    }

    /**
     * King captures the opponent threatens if the side to move left its remaining present boards
     * unplayed. This is what "check" means in this game, and what the UI shows in red.
     */
    fun threats(): List<Move> {
        if (mandatoryTimelines.isEmpty()) return kingCaptures(sideToMove.opponent)
        val passed = timelines.toMutableMap()
        for (l in mandatoryTimelines) {
            val timeline = passed.getValue(l)
            passed[l] = timeline.appended(timeline.last.with(emptyList()))
        }
        return GameState(passed, sideToMove, turnMoves, timelineLimit).kingCaptures(sideToMove.opponent)
    }

    /** Decides whether the side to move is checkmated or stalemated. */
    fun evaluateOutcome(nodeBudget: Int = 4_000, maxOptionalMoves: Int = 1): Outcome =
        when (TurnSearch(nodeBudget, maxOptionalMoves).findLegalTurn(this)) {
            is TurnSearchResult.Found, TurnSearchResult.Inconclusive -> Outcome.Ongoing
            TurnSearchResult.NoLegalTurn ->
                if (threats().isNotEmpty()) Outcome.Checkmate(sideToMove.opponent) else Outcome.Stalemate
        }

    companion object {
        fun initial(variant: Variant = Variant.STANDARD, timelineLimit: Int? = null): GameState =
            GameState(mapOf(0 to Timeline(0, 0, listOf(variant.board()))), Side.WHITE, timelineLimit = timelineLimit)
    }
}
