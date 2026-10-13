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
        PieceType.QUEEN -> addRays(from, piece.side, Directions.queen, slide = true, capturesOnly, out)
        PieceType.ROOK -> addRays(from, piece.side, Directions.rook, slide = true, capturesOnly, out)
        PieceType.BISHOP -> addRays(from, piece.side, Directions.bishop, slide = true, capturesOnly, out)
        PieceType.KNIGHT -> addRays(from, piece.side, Directions.knight, slide = false, capturesOnly, out)
        PieceType.PAWN -> addPawnMoves(from, board, piece, capturesOnly, out)
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

    if (!capturesOnly) {
        val one = from.copy(y = from.y + dy)
        if (Square.isOnBoard(one.x, one.y) && board[one.x, one.y] == null) {
            out += step(one)
            val two = from.copy(y = from.y + 2 * dy)
            if (!piece.moved && Square.isOnBoard(two.x, two.y) && board[two.x, two.y] == null) {
                out += Move(from, two, doubleStep = true)
            }
        }
        // Forward along the timeline axis onto the same square of a neighbouring timeline.
        val across = from.copy(l = from.l + dl)
        val acrossBoard = boardAt(across.l, across.t)
        if (acrossBoard != null && acrossBoard[across.x, across.y] == null) out += step(across)
    }

    // Spatial diagonal captures, including en passant.
    for (dx in intArrayOf(-1, 1)) {
        val to = from.copy(x = from.x + dx, y = from.y + dy)
        if (!Square.isOnBoard(to.x, to.y)) continue
        val occupant = board[to.x, to.y]
        if (occupant != null) {
            if (occupant.side != side) out += step(to)
        } else if (board.enPassant == to.square) {
            val victim = Square(to.x, from.y)
            val victimPiece = board[victim]
            if (victimPiece != null && victimPiece.type == PieceType.PAWN && victimPiece.side != side) {
                out += Move(from, to, enPassantCapture = victim)
            }
        }
    }

    // Diagonal captures in the time/timeline plane: forward one timeline, one turn back or ahead.
    for (dt in intArrayOf(-1, 1)) {
        val to = from.copy(l = from.l + dl, t = from.t + 2 * dt)
        val target = boardAt(to.l, to.t) ?: continue
        val occupant = target[to.x, to.y]
        if (occupant != null && occupant.side != side) out += step(to)
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
