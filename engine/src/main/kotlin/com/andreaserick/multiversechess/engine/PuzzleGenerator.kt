package com.andreaserick.multiversechess.engine

import kotlin.random.Random

/**
 * Makes new "checkmate in one turn" puzzles the way the built-in ones are made: a sparse position
 * (two kings with a few pawns, and some White attackers), then a few random turns of history,
 * sometimes with a piece travelling back in time. After each turn, the solver checks whether White
 * has a mate; a position becomes a puzzle if it has one or two mating turns. Mates that travel
 * through time or across timelines are preferred.
 */
class PuzzleGenerator(
    private val random: Random = Random.Default,
    private val maxMates: Int = 2,
    private val stop: () -> Boolean = { false },
) {
    /** A new puzzle numbered [number], or null if none was found (or [stop] cut the search short). */
    fun generate(number: Int): Puzzle? {
        // About half the puzzles start from more than one timeline, if one turns up soon enough.
        if (random.nextBoolean()) {
            for (attempt in 0 until BRANCHED_ATTEMPTS) {
                if (stop()) return null
                val found = attempt(minTimelines = 2) ?: continue
                if (isInteresting(found.first, found.second)) return puzzle(number, found.first, found.second)
            }
        }
        var plain: Pair<GameState, List<Move>>? = null
        for (attempt in 0 until MAX_ATTEMPTS) {
            if (stop()) break
            val found = attempt(minTimelines = 1) ?: continue
            if (isInteresting(found.first, found.second)) return puzzle(number, found.first, found.second)
            if (plain == null) plain = found
            // An ordinary mate will do if no time-travelling one turns up after a while.
            if (attempt >= PLAIN_AFTER) break
        }
        return plain?.let { puzzle(number, it.first, it.second) }
    }

    /** Builds one random position and plays random history on it; returns the first puzzle found. */
    private fun attempt(minTimelines: Int): Pair<GameState, List<Move>>? {
        var state = randomStart() ?: return null
        for (turn in 0 until HISTORY_TURNS) {
            if (stop()) return null
            if (turn >= 1 && state.sideToMove == Side.WHITE && state.timelines.size >= minTimelines) check(state)?.let { return it }
            state = randomTurn(state) ?: return null
        }
        return null
    }

    /** A sparse, legal position with White to move and neither king attacked. */
    private fun randomStart(): GameState? {
        val cells = HashMap<Square, Piece>()
        fun free(x: Int, y: Int) = Square.isOnBoard(x, y) && Square(x, y) !in cells
        fun put(x: Int, y: Int, piece: Piece): Boolean = free(x, y).also { if (it) cells[Square(x, y)] = piece }

        // Black's king on its back two ranks behind a few pawns; White's at home behind its own.
        val bx = random.nextInt(BOARD_SIZE)
        val by = BOARD_SIZE - 1 - random.nextInt(2)
        put(bx, by, Piece(PieceType.KING, Side.BLACK, moved = true))
        for (dx in -1..1) if (random.nextInt(3) > 0) put(bx + dx, by - 1, Piece(PieceType.PAWN, Side.BLACK, moved = true))
        val wx = random.nextInt(BOARD_SIZE)
        put(wx, 0, Piece(PieceType.KING, Side.WHITE, moved = true))
        for (dx in -1..1) put(wx + dx, 1, Piece(PieceType.PAWN, Side.WHITE))
        // White's attackers, and sometimes a Black defender.
        repeat(1 + random.nextInt(2)) {
            val type = listOf(PieceType.ROOK, PieceType.QUEEN, PieceType.BISHOP, PieceType.KNIGHT, PieceType.ROOK).random(random)
            put(random.nextInt(BOARD_SIZE), random.nextInt(BOARD_SIZE - 2), Piece(type, Side.WHITE, moved = true))
        }
        if (random.nextInt(3) == 0) {
            val type = listOf(PieceType.ROOK, PieceType.KNIGHT, PieceType.BISHOP).random(random)
            put(random.nextInt(BOARD_SIZE), 3 + random.nextInt(BOARD_SIZE - 3), Piece(type, Side.BLACK, moved = true))
        }
        val empty = Board.parse(*Array(BOARD_SIZE) { ".".repeat(BOARD_SIZE) })
        val state = GameState(mapOf(0 to Timeline(0, 0, listOf(empty.with(cells.map { it.key to it.value })))), Side.WHITE)
        if (state.kingCaptures(Side.WHITE, firstOnly = true).isNotEmpty() || state.isInCheck(Side.WHITE)) return null
        return state
    }

    /**
     * A random legal turn for the side to move. White sometimes sends a piece into the past, to
     * give the puzzle history to work with; otherwise moves stay on their boards and don't capture
     * a king's defenders wholesale.
     */
    private fun randomTurn(state: GameState): GameState? {
        val travel = state.sideToMove == Side.WHITE && state.timelines.size < MAX_TIMELINES && random.nextInt(3) == 0
        val order = { s: GameState, moves: List<Move> ->
            val (travelling, rest) = moves.shuffled(random).partition { !it.isSpatial }
            if (travel) travelling + rest else rest + travelling
        }
        val turn = try {
            TurnWalker(TURN_BUDGET, maxOptionalMoves = 0, order = order, stop = stop).walk(state) { true }
        } catch (_: BudgetExceeded) {
            null
        } ?: return null
        val next = turn.submit()
        // A turn that already ends the game leaves nothing to solve.
        return next.takeIf { TurnSearch(TURN_BUDGET).findLegalTurn(it) is TurnSearchResult.Found }
    }

    /** [position] with one of its mates, if it has a mate and few enough of them. */
    private fun check(position: GameState): Pair<GameState, List<Move>>? {
        if (position.threats().isNotEmpty()) return null
        // With more boards to play, the moves on the others are free choices, so a mate would come
        // in countless versions. Mates are counted without optional moves elsewhere, which only
        // add variations of the same idea.
        if (position.mandatoryTimelines.size > MAX_BOARDS) return null
        val mates = PuzzleSolver.matesWithin(position, SOLVER_BUDGET, maxOptionalMoves = 0, stop = stop) ?: return null
        if (mates.isEmpty() || mates.size > maxMates) return null
        return position to mates.minBy { it.size }
    }

    private fun isInteresting(start: GameState, mate: List<Move>) = mate.any { !it.isSpatial }

    private fun puzzle(number: Int, start: GameState, mate: List<Move>): Puzzle {
        val hint = when {
            mate.any { it.to.l != it.from.l } -> "The mate comes from another timeline."
            mate.any { !it.isSpatial } -> "The mate comes through time: where was the king before?"
            start.timelines.size > 1 -> "Every timeline counts, but the mate is played on a single board."
            else -> "An ordinary mate, played on the present board."
        }
        return Puzzle(
            id = "generated-${random.nextLong().toULong().toString(16)}",
            title = "Generated puzzle $number",
            hint = hint,
            solution = mate.map { it.from to it.to },
        ) { position(start) }
    }

    private companion object {
        const val MAX_ATTEMPTS = 400
        const val BRANCHED_ATTEMPTS = 150
        const val PLAIN_AFTER = 120
        const val HISTORY_TURNS = 8
        const val MAX_TIMELINES = 3
        const val MAX_BOARDS = 2
        const val TURN_BUDGET = 2_000
        const val SOLVER_BUDGET = 20_000
    }
}

/** Saves puzzles as text, so generated ones survive the app being closed. */
object PuzzleCodec {
    private const val HEADER = "puzzle 1"

    fun encode(puzzle: Puzzle): String = buildString {
        appendLine(HEADER)
        appendLine("id ${puzzle.id}")
        appendLine("title ${puzzle.title}")
        appendLine("hint ${puzzle.hint}")
        appendLine("solution " + puzzle.solution.joinToString(" ") { (from, to) -> "${pos(from)}>${pos(to)}" })
        append(SavedGame.encode(GameSession(GameMode(), state = puzzle.start())))
    }

    /** The puzzle in [text], or null if it can't be read. */
    fun decode(text: String): Puzzle? = try {
        val lines = text.lines()
        require(lines[0] == HEADER)
        fun field(name: String) = lines.first { it.startsWith("$name ") }.substringAfter(' ')
        val solution = field("solution").split(' ').map { pair ->
            val (from, to) = pair.split('>')
            parse(from) to parse(to)
        }
        val start = SavedGame.decode(lines.drop(5).joinToString("\n"))!!.state
        Puzzle(field("id"), field("title"), field("hint"), solution) { position(start) }
    } catch (_: RuntimeException) {
        null
    }

    /** Several puzzles, separated by blank lines. */
    fun encodeAll(puzzles: List<Puzzle>): String = puzzles.joinToString("\n") { encode(it) }

    fun decodeAll(text: String): List<Puzzle> =
        text.split(Regex("(?m)^(?=$HEADER$)")).filter { it.isNotBlank() }.mapNotNull { decode(it.trim()) }

    private fun pos(p: Pos) = "${p.l},${p.t},${p.x},${p.y}"

    private fun parse(text: String) = text.split(',').map { it.toInt() }.let { (l, t, x, y) -> Pos(l, t, x, y) }
}
