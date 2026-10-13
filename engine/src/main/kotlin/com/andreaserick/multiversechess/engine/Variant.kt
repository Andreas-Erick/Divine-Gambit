package com.andreaserick.multiversechess.engine

/** A starting position. [backRank] lists White's first rank from a to h; Black mirrors it. */
enum class Variant(
    val displayName: String,
    val description: String,
    private val backRank: String,
    private val pawn: Char = 'P',
) {
    STANDARD("Standard", "The usual chess pieces.", "RNBQKBNR"),
    PRINCESSES("Princesses", "Queens are replaced by princesses, which move like a rook or a bishop.", "RNBSKBNR"),
    UNICORNS("Unicorns", "Bishops are replaced by unicorns, which only move through time and timelines.", "RNUQKUNR"),
    BRAWNS("Brawns", "Pawns are replaced by brawns, which have extra ways to capture.", "RNBQKBNR", pawn = 'W'),
    MENAGERIE(
        "Menagerie",
        "Every fairy piece at once: a princess, a unicorn, a dragon and a row of brawns.",
        "RNUSKDNR",
        pawn = 'W',
    ),
    ;

    fun board(): Board = Board.parse(
        backRank.lowercase(),
        pawn.lowercase().repeat(BOARD_SIZE),
        "........",
        "........",
        "........",
        "........",
        pawn.toString().repeat(BOARD_SIZE),
        backRank,
    )

    /** Piece types that appear in this variant beyond the standard six. */
    val fairyPieces: List<PieceType> get() =
        (backRank + pawn).map { c -> PieceType.entries.first { it.letter == c } }.filter { it.isFairy }.distinct()
}
