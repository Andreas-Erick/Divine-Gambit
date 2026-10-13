package com.andreaserick.multiversechess.engine

object Notation {
    fun timelineLabel(l: Int): String = if (l > 0) "+$l" else "$l"

    /** Full-turn number shown to players (boards at half-moves 0 and 1 are turn 1). */
    fun turnNumber(t: Int): Int = t / 2 + 1

    fun boardLabel(l: Int, t: Int): String = "(L${timelineLabel(l)} T${turnNumber(t)})"

    /** Describes [move] as played in [before], e.g. `(L0 T1) Ng1-f3` or `(L0 T3) Nb1 → (L0 T2) c3`. */
    fun describe(before: GameState, move: Move): String {
        val piece = before.pieceAt(move.from) ?: return "?"
        val origin = boardLabel(move.from.l, move.from.t)
        if (move.castleRook != null) return "$origin " + if (move.to.x == 6) "O-O" else "O-O-O"
        val letter = if (piece.type == PieceType.PAWN) "" else piece.type.letter.toString()
        val captures = before.pieceAt(move.to) != null || move.enPassantCapture != null
        val promotion = move.promotion?.let { "=${it.letter}" } ?: ""
        return if (move.isSpatial) {
            "$origin $letter${move.from.square}${if (captures) "x" else "-"}${move.to.square}$promotion"
        } else {
            val target = boardLabel(move.to.l, move.to.t)
            "$origin $letter${move.from.square} → $target ${if (captures) "x" else ""}${move.to.square}$promotion"
        }
    }
}
