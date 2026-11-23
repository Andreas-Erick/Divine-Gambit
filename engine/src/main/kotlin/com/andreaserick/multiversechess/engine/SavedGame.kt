package com.andreaserick.multiversechess.engine

/**
 * Saves a game as plain text and restores it. What's kept: the mode, the position at the start of
 * the current turn, the moves made in it so far (replayed on restore, so they can still be undone),
 * the move log, the last turn's moves, the pieces in the Inferno with the trials passed for them,
 * and how the game stands. Undo history from earlier turns isn't kept.
 *
 * Puzzles aren't saved: they're short, and restart from their own position.
 */
object SavedGame {
    private const val VERSION = "multiverse-chess 1"

    /** A game read back: the session, and the player's [InfernoProgress] in it (time there starts afresh). */
    data class Saved(val game: GameSession, val trials: InfernoProgress)

    fun encode(session: GameSession, trials: InfernoProgress = InfernoProgress()): String {
        require(session.mode.puzzle == null) { "Puzzles aren't saved" }
        val startSession = turnStart(session)
        val start = startSession.state
        val turn = session.state.turnMoves
        return buildString {
            appendLine(VERSION)
            val mode = session.mode
            appendLine(
                "mode ${mode.computerSide?.name ?: "-"} ${mode.variant.name} ${mode.timelineLimit ?: "-"}" +
                    (if (mode.aiOpponent) " ai" else "") + if (mode.inferno) " inferno" else "",
            )
            appendLine("outcome ${encode(session.outcome)} ${if (session.undecided) 1 else 0}")
            appendLine("side ${start.sideToMove.name}")
            for (timeline in start.timelines.values.sortedBy { it.index }) {
                appendLine("timeline ${timeline.index} ${timeline.start} ${timeline.parent ?: "-"}")
                for (board in timeline.boards) appendLine("board ${encode(board)}")
            }
            for (move in turn) appendLine("move ${encode(move.from)} ${encode(move.to)}")
            for (move in session.lastTurn) appendLine("last ${encode(move.from)} ${encode(move.to)}")
            // As at the start of the turn: replaying its moves takes the pieces they take again.
            for (soul in startSession.souls) appendLine("soul ${soul.type.letter} ${soul.side.name} ${if (soul.freed) 1 else 0}")
            startSession.returned?.let { appendLine("returned ${encode(it)}") }
            for ((index, count) in trials.solved.entries.sortedBy { it.key }) appendLine("trial $index $count")
            // The log without the current turn's lines, which replaying its moves adds back.
            for (line in session.log.dropLast(turn.size)) appendLine("log $line")
        }
    }

    /** The game saved in [text], or null if it can't be read (another version, or damaged). */
    fun decode(text: String): GameSession? = load(text)?.game

    /** The game saved in [text] with the trials passed in it, or null if it can't be read. */
    fun load(text: String): Saved? = try {
        read(text.lines().filter { it.isNotBlank() })
    } catch (_: RuntimeException) {
        null
    }

    private fun read(lines: List<String>): Saved? {
        if (lines.firstOrNull() != VERSION) return null
        var mode: GameMode? = null
        var outcome: Outcome = Outcome.Ongoing
        var undecided = false
        var side = Side.WHITE
        val timelines = LinkedHashMap<Int, Pair<Triple<Int, Int, Int?>, MutableList<Board>>>()
        var current: Int? = null
        val moves = ArrayList<Pair<Pos, Pos>>()
        val last = ArrayList<Pair<Pos, Pos>>()
        val log = ArrayList<String>()
        val souls = ArrayList<Soul>()
        var returned: Pos? = null
        val trials = HashMap<Int, Int>()
        for (line in lines.drop(1)) {
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            val words = rest.split(' ')
            when (key) {
                "mode" -> {
                    require(words.size >= 3) { "Malformed mode line" }
                    val limit = words[2].takeIf { it != "-" }?.toInt()
                    require(limit == null || limit in 0..99) { "Invalid timeline limit: $limit" }
                    mode = GameMode(
                        computerSide = words[0].takeIf { it != "-" }?.let { Side.valueOf(it) },
                        variant = Variant.valueOf(words[1]),
                        timelineLimit = limit,
                        aiOpponent = "ai" in words.drop(3),
                        inferno = "inferno" in words.drop(3),
                    )
                }
                "outcome" -> {
                    require(words.size == 2 && (words[1] == "0" || words[1] == "1")) { "Malformed outcome line" }
                    outcome = decodeOutcome(words[0])
                    undecided = words[1] == "1"
                }
                "side" -> {
                    require(words.size == 1) { "Malformed side line" }
                    side = Side.valueOf(words[0])
                }
                "timeline" -> {
                    require(words.size == 3) { "Malformed timeline line" }
                    val index = words[0].toInt()
                    val tlStart = words[1].toInt()
                    val parent = words[2].takeIf { it != "-" }?.toInt()
                    require(tlStart >= 0 && (index == 0) == (parent == null)) { "Invalid timeline metadata: $line" }
                    timelines[index] = Triple(index, tlStart, parent) to ArrayList()
                    current = index
                }
                "board" -> timelines.getValue(requireNotNull(current) { "Board before timeline" }).second += decodeBoard(rest)
                "move" -> {
                    require(words.size == 2) { "Malformed move line" }
                    moves += decodePos(words[0]) to decodePos(words[1])
                }
                "last" -> {
                    require(words.size == 2) { "Malformed last line" }
                    last += decodePos(words[0]) to decodePos(words[1])
                }
                "log" -> log += rest
                "soul" -> {
                    require(words.size == 3 && words[0].length == 1 && (words[2] == "0" || words[2] == "1")) { "Malformed soul line" }
                    souls += Soul(PieceType.entries.first { it.letter == words[0][0] }, Side.valueOf(words[1]), words[2] == "1")
                }
                "trial" -> {
                    require(words.size == 2) { "Malformed trial line" }
                    val index = words[0].toInt()
                    val count = words[1].toInt()
                    require(index >= 0 && count >= 0) { "Negative trial count" }
                    trials[index] = count
                }
                "returned" -> {
                    require(words.size == 1) { "Malformed returned line" }
                    returned = decodePos(words[0])
                }
            }
        }
        val gameMode = mode ?: return null
        if (0 !in timelines) return null
        for ((index, value) in timelines) {
            val parent = value.first.third
            if (index != 0 && (parent == null || parent !in timelines)) return null
        }
        val start = GameState(
            timelines.mapValues { (_, value) -> Timeline(value.first.first, value.first.second, value.second, value.first.third) },
            side,
            timelineLimit = gameMode.timelineLimit,
        )
        if (returned != null && start.boardAt(returned.l, returned.t) == null) return null
        // The last turn's moves are only shown, as highlights; keep the ones on existing boards.
        val lastTurn = last.filter { (from, to) -> start.boardAt(from.l, from.t) != null && start.boardAt(to.l, to.t) != null }
            .map { (from, to) -> Move(from, to) }
        var session = GameSession(
            gameMode, state = start, outcome = outcome, undecided = undecided, log = log, lastTurn = lastTurn, souls = souls, returned = returned,
        )
        for ((from, to) in moves) {
            val move = session.state.legalMovesFrom(from).firstOrNull { it.to == to } ?: return null
            session = session.play(move)
        }
        return Saved(session, InfernoProgress(trials.filterKeys { it < session.souls.size }))
    }

    private fun turnStart(session: GameSession): GameSession {
        var s = session
        while (s.state.turnMoves.isNotEmpty()) s = s.previous ?: error("Turn start not in the undo history")
        return s
    }

    internal fun encode(outcome: Outcome): String = when (outcome) {
        Outcome.Ongoing -> "ongoing"
        Outcome.Stalemate -> "stalemate"
        is Outcome.Checkmate -> "checkmate:${outcome.winner.name}"
        is Outcome.Resignation -> "resignation:${outcome.winner.name}"
    }

    internal fun decodeOutcome(text: String): Outcome = when {
        text == "ongoing" -> Outcome.Ongoing
        text == "stalemate" -> Outcome.Stalemate
        text.startsWith("checkmate:") -> Outcome.Checkmate(Side.valueOf(text.substringAfter(':')))
        text.startsWith("resignation:") -> Outcome.Resignation(Side.valueOf(text.substringAfter(':')))
        else -> error("Unknown outcome $text")
    }

    internal fun encode(pos: Pos) = "${pos.l},${pos.t},${pos.x},${pos.y}"

    internal fun decodePos(text: String): Pos {
        val parts = text.split(',')
        require(parts.size == 4) { "Malformed position: $text" }
        val (l, t, x, y) = parts.map { it.toInt() }
        require(t >= 0 && Square.isOnBoard(x, y)) { "Position out of bounds: $text" }
        return Pos(l, t, x, y)
    }

    /**
     * 64 squares from a1 along the ranks: `.` for empty, or the piece's letter (upper case White)
     * followed by `*` if it has moved; then ` ` and the en passant square, or `-`.
     */
    private fun encode(board: Board): String = buildString {
        for (y in 0 until BOARD_SIZE) for (x in 0 until BOARD_SIZE) {
            val piece = board[x, y]
            if (piece == null) {
                append('.')
            } else {
                append(if (piece.side == Side.WHITE) piece.type.letter else piece.type.letter.lowercaseChar())
                if (piece.moved) append('*')
            }
        }
        append(' ').append(board.enPassant?.toString() ?: "-")
    }

    private fun decodeBoard(text: String): Board {
        val (squares, enPassant) = text.split(' ')
        val changes = ArrayList<Pair<Square, Piece?>>()
        var i = 0
        var square = 0
        while (i < squares.length) {
            val c = squares[i++]
            if (c != '.') {
                val moved = i < squares.length && squares[i] == '*'
                if (moved) i++
                val type = PieceType.entries.first { it.letter == c.uppercaseChar() }
                changes += Square(square % BOARD_SIZE, square / BOARD_SIZE) to Piece(type, if (c.isUpperCase()) Side.WHITE else Side.BLACK, moved)
            }
            square++
        }
        require(square == BOARD_SIZE * BOARD_SIZE) { "Board has $square squares" }
        return EMPTY.with(changes, enPassant.takeIf { it != "-" }?.let { Square.parse(it) })
    }

    private val EMPTY = Board.parse(*Array(BOARD_SIZE) { ".".repeat(BOARD_SIZE) })
}
