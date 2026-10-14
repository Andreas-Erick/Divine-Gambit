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
}

/**
 * Pseudo-legal moves of the piece at [from], ignoring whether the board is playable and whether
 * the mover's kings end up capturable (that's checked when the turn is submitted).
 * With [capturesOnly], only moves that capture are returned (used for check detection).
 */
internal fun GameState.generateMoves(from: Pos, capturesOnly: Boolean): List<Move> {
    val board = boardAt(from.l, from.t) ?: return emptyList()
    val piece = board[from.x, from.y] ?: return emptyList()
    val out = ArrayList<Move>()
    when (piece.type) {
        PieceType.KING -> {
            addRays(from, piece.side, Directions.queen, slide = false, capturesOnly, out)
            if (!capturesOnly) addCastling(from, board, piece, out)
        }
        PieceType.COMMON_KING -> addRays(from, piece.side, Directions.queen, slide = false, capturesOnly, out)
        PieceType.QUEEN, PieceType.ROYAL_QUEEN -> addRays(from, piece.side, Directions.queen, slide = true, capturesOnly, out)
        PieceType.ROOK -> addRays(from, piece.side, Directions.rook, slide = true, capturesOnly, out)
        PieceType.BISHOP -> addRays(from, piece.side, Directions.bishop, slide = true, capturesOnly, out)
        PieceType.KNIGHT -> addRays(from, piece.side, Directions.knight, slide = false, capturesOnly, out)
        PieceType.PRINCESS -> addRays(from, piece.side, Directions.princess, slide = true, capturesOnly, out)
        PieceType.UNICORN -> addRays(from, piece.side, Directions.unicorn, slide = true, capturesOnly, out)
        PieceType.DRAGON -> addRays(from, piece.side, Directions.dragon, slide = true, capturesOnly, out)
        PieceType.PAWN, PieceType.BRAWN -> addPawnMoves(from, board, piece, capturesOnly, out)
    }
    return out
}

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
