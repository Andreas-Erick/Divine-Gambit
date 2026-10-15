package com.andreaserick.multiversechess.engine

/** A direction through the four axes: file, rank, time (in full turns) and timeline. */
internal data class Vec(val dx: Int, val dy: Int, val dt: Int, val dl: Int) {
    val axes: Int get() = listOf(dx, dy, dt, dl).count { it != 0 }
}

internal object Directions {
    private val units: List<Vec> = buildList {
        for (dx in -1..1) for (dy in -1..1) for (dt in -1..1) for (dl in -1..1) {
            if (dx != 0 || dy != 0 || dt != 0 || dl != 0) add(Vec(dx, dy, dt, dl))
        }
    }

    /** Along exactly one axis. */
    val rook: List<Vec> = units.filter { it.axes == 1 }

    /** Equal distance along exactly two axes. */
    val bishop: List<Vec> = units.filter { it.axes == 2 }

    /** Equal distance along any number of axes (also the king's single steps). */
    val queen: List<Vec> = units

    /** Equal distance along one or two axes. */
    val princess: List<Vec> = rook + bishop

    /** Equal distance along exactly three axes. */
    val unicorn: List<Vec> = units.filter { it.axes == 3 }

    /** Equal distance along all four axes. */
    val dragon: List<Vec> = units.filter { it.axes == 4 }

    /** Two along one axis and one along another. */
    val knight: List<Vec> = buildList {
        for (a in 0 until 4) for (b in 0 until 4) {
            if (a == b) continue
            for (sa in intArrayOf(-2, 2)) for (sb in intArrayOf(-1, 1)) {
                val c = IntArray(4)
                c[a] = sa
                c[b] = sb
                add(Vec(c[0], c[1], c[2], c[3]))
            }
        }
    }

    /**
     * The directions of each list by their step through time and timelines ([timeAxes]), so the
     * ones along a given line can be found without looking at the rest. Built once and only read
     * afterwards, so searches on several threads can share it.
     */
    private val grouped = java.util.IdentityHashMap<List<Vec>, Map<Int, List<Vec>>>().apply {
        for (list in listOf(rook, bishop, queen, princess, unicorn, dragon, knight)) put(list, list.groupBy { timeAxes(it.dl, it.dt) })
    }

    fun along(directions: List<Vec>): Map<Int, List<Vec>> = grouped.getValue(directions)
}

/** A key for a step of [dl] timelines and [dt] turns. */
internal fun timeAxes(dl: Int, dt: Int): Int = (dl + 8) * 32 + (dt + 8)

/**
 * The lines through time and timelines along which a piece on one board could reach some other
 * boards (see [GameState.isInCheckAfter]): the steps of a sliding piece ([slide]), the exact jumps
 * of one that doesn't ([jump]), and the [boards] themselves, for pawns.
 */
internal class Lines(val slide: Set<Int>, val jump: Set<Int>, val boards: List<BoardId>) {
    val isEmpty: Boolean get() = slide.isEmpty() && jump.isEmpty()

    /** Whether one of the boards is within a pawn's reach: a timeline and a turn away at most. */
    val nearby: Boolean = jump.any { key -> kotlin.math.abs(key / 32 - 8) <= 1 && kotlin.math.abs(key % 32 - 8) <= 1 }

    companion object {
        /** The lines from board [from] to each of [boards] (which have the same side to move). */
        fun between(from: BoardId, boards: List<BoardId>): Lines {
            val slide = HashSet<Int>()
            val jump = HashSet<Int>()
            for (board in boards) {
                val dl = board.l - from.l
                val diff = board.t - from.t
                if (diff % 2 != 0 || (dl == 0 && diff == 0)) continue
                val dt = diff / 2
                jump += timeAxes(dl, dt)
                // A slide covers equal steps along each axis it moves on.
                if (dl == 0 || dt == 0 || kotlin.math.abs(dl) == kotlin.math.abs(dt)) slide += timeAxes(Integer.signum(dl), Integer.signum(dt))
            }
            return Lines(slide, jump, boards)
        }
    }
}

/**
 * Pseudo-legal moves of the piece at [from], ignoring whether the board is playable and whether
 * the mover's kings end up capturable (that's checked when the turn is submitted).
 * With [capturesOnly], only moves that capture are returned (used for check detection).
 */
internal fun GameState.generateMoves(from: Pos, capturesOnly: Boolean): List<Move> = generateMoves(from, capturesOnly, through = null)

/**
 * Like [generateMoves], but with [through], only the moves along those lines: the ones whose path
 * crosses or ends on one of its boards (see [GameState.isInCheckAfter]).
 */
internal fun GameState.generateMoves(from: Pos, capturesOnly: Boolean, through: Lines?): List<Move> {
    val board = boardAt(from.l, from.t) ?: return emptyList()
    val piece = board[from.x, from.y] ?: return emptyList()
    val out = ArrayList<Move>()
    fun rays(directions: List<Vec>, slide: Boolean) {
        if (through == null) return addRays(from, piece.side, directions, slide, capturesOnly, out)
        val groups = Directions.along(directions)
        for (key in if (slide) through.slide else through.jump) groups[key]?.let { addRays(from, piece.side, it, slide, capturesOnly, out) }
    }
    when (piece.type) {
        PieceType.KING -> {
            rays(Directions.queen, slide = false)
            if (!capturesOnly) addCastling(from, board, piece, out)
        }
        PieceType.COMMON_KING -> rays(Directions.queen, slide = false)
        PieceType.QUEEN, PieceType.ROYAL_QUEEN -> rays(Directions.queen, slide = true)
        PieceType.ROOK -> rays(Directions.rook, slide = true)
        PieceType.BISHOP -> rays(Directions.bishop, slide = true)
        PieceType.KNIGHT -> rays(Directions.knight, slide = false)
        PieceType.PRINCESS -> rays(Directions.princess, slide = true)
        PieceType.UNICORN -> rays(Directions.unicorn, slide = true)
        PieceType.DRAGON -> rays(Directions.dragon, slide = true)
        PieceType.PAWN, PieceType.BRAWN -> {
            addPawnMoves(from, board, piece, capturesOnly, out)
            // A pawn never passes through a board, so only where it lands matters.
            if (through != null) out.removeAll { BoardId(it.to.l, it.to.t) !in through.boards }
        }
    }
    // At the timeline limit, moves that would branch a new timeline aren't allowed.
    if (atTimelineLimit(piece.side)) out.removeAll { it.branches(this) }
    return out
}

/** Whether [this] move creates a new timeline: it travels to a board that isn't its timeline's latest. */
internal fun Move.branches(state: GameState): Boolean =
    !isSpatial && (to.l == from.l || state.timelines.getValue(to.l).end != to.t)

private fun GameState.addRays(
    from: Pos,
    side: Side,
    directions: List<Vec>,
    slide: Boolean,
    capturesOnly: Boolean,
    out: MutableList<Move>,
) {
    for (v in directions) {
        var k = 1
        while (true) {
            val x = from.x + v.dx * k
            val y = from.y + v.dy * k
            if (!Square.isOnBoard(x, y)) break
            val to = Pos(from.l + v.dl * k, from.t + 2 * v.dt * k, x, y)
            val board = boardAt(to.l, to.t) ?: break
            val occupant = board[x, y]
            if (occupant == null) {
                if (!capturesOnly) out += Move(from, to)
            } else {
                if (occupant.side != side) out += Move(from, to)
                break
            }
            if (!slide) break
            k++
        }
    }
}

private fun GameState.addPawnMoves(from: Pos, board: Board, piece: Piece, capturesOnly: Boolean, out: MutableList<Move>) {
    val side = piece.side
    val dy = side.rankForward
    val dl = side.timelineForward
    fun step(to: Pos) = Move(from, to, promotion = if (to.y == side.promotionRank) PieceType.QUEEN else null)
    fun isEmpty(p: Pos): Boolean = boardAt(p.l, p.t)?.let { Square.isOnBoard(p.x, p.y) && it[p.x, p.y] == null } == true

    if (!capturesOnly) {
        // Forward along the rank axis, or along the timeline axis onto the same square of a
        // neighbouring timeline. An unmoved pawn may go two steps either way.
        val one = from.copy(y = from.y + dy)
        if (isEmpty(one)) {
            out += step(one)
            val two = from.copy(y = from.y + 2 * dy)
            if (!piece.moved && isEmpty(two)) out += Move(from, two, doubleStep = true)
        }
        val across = from.copy(l = from.l + dl)
        if (isEmpty(across)) {
            out += step(across)
            val twoAcross = from.copy(l = from.l + 2 * dl)
            if (!piece.moved && isEmpty(twoAcross)) out += step(twoAcross)
        }
    }

    fun capture(to: Pos) {
        if (!Square.isOnBoard(to.x, to.y)) return
        val occupant = boardAt(to.l, to.t)?.get(to.x, to.y) ?: return
        if (occupant.side != side) out += step(to)
    }

    // Spatial diagonal captures, including en passant.
    for (dx in intArrayOf(-1, 1)) {
        val to = from.copy(x = from.x + dx, y = from.y + dy)
        if (!Square.isOnBoard(to.x, to.y)) continue
        if (board[to.x, to.y] != null) {
            capture(to)
        } else if (board.enPassant == to.square) {
            val victim = Square(to.x, from.y)
            val victimPiece = board[victim]
            if (victimPiece != null && victimPiece.type.isPawnLike && victimPiece.side != side) {
                out += Move(from, to, enPassantCapture = victim)
            }
        }
    }

    // Diagonal captures in the time/timeline plane: forward one timeline, one turn back or ahead.
    for (dt in intArrayOf(-1, 1)) capture(from.copy(l = from.l + dl, t = from.t + 2 * dt))

    if (piece.type == PieceType.BRAWN) {
        // Also every other diagonal that includes a forward step (rank or timeline) and no backward
        // one: forward along the rank with a step in time, forward along the timeline with a step
        // along the file, and forward along both.
        for (d in intArrayOf(-1, 1)) {
            capture(from.copy(y = from.y + dy, t = from.t + 2 * d))
            capture(from.copy(l = from.l + dl, x = from.x + d))
        }
        capture(from.copy(y = from.y + dy, l = from.l + dl))
    }
}

private fun addCastling(from: Pos, board: Board, king: Piece, out: MutableList<Move>) {
    val y = king.side.homeRank
    if (king.moved || from.x != 4 || from.y != y) return
    val enemy = king.side.opponent
    if (board.isAttackedSpatially(Square(4, y), enemy)) return

    fun tryCastle(rookX: Int, kingToX: Int, rookToX: Int, mustBeEmpty: IntRange, mustBeSafe: List<Int>) {
        val rook = board[rookX, y] ?: return
        if (rook.type != PieceType.ROOK || rook.side != king.side || rook.moved) return
        if (mustBeEmpty.any { board[it, y] != null }) return
        if (mustBeSafe.any { board.isAttackedSpatially(Square(it, y), enemy) }) return
        out += Move(from, from.copy(x = kingToX), castleRook = Square(rookX, y) to Square(rookToX, y))
    }

    tryCastle(rookX = 7, kingToX = 6, rookToX = 5, mustBeEmpty = 5..6, mustBeSafe = listOf(5, 6))
    tryCastle(rookX = 0, kingToX = 2, rookToX = 3, mustBeEmpty = 1..3, mustBeSafe = listOf(3, 2))
}
