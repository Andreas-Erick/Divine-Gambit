package com.andreaserick.multiversechess.engine

/**
 * Saves a game as plain text and restores it. What's kept: the mode, the position at the start of
 * the current turn, the moves made in it so far (replayed on restore, so they can still be undone),
 * the move log, the last turn's moves and how the game stands. Undo history from earlier turns
 * isn't kept.
 *
 * Puzzles aren't saved: they're short, and restart from their own position.
 */
object SavedGame {
    private const val VERSION = "multiverse-chess 1"

    fun encode(session: GameSession): String {
        require(session.mode.puzzle == null) { "Puzzles aren't saved" }
        val start = turnStart(session)
        val turn = session.state.turnMoves
        return buildString {
            appendLine(VERSION)
            val mode = session.mode
            appendLine("mode ${mode.computerSide?.name ?: "-"} ${mode.variant.name} ${mode.timelineLimit ?: "-"}")
            appendLine("outcome ${encode(session.outcome)} ${if (session.undecided) 1 else 0}")
            appendLine("side ${start.sideToMove.name}")
            for (timeline in start.timelines.values.sortedBy { it.index }) {
                appendLine("timeline ${timeline.index} ${timeline.start} ${timeline.parent ?: "-"}")
                for (board in timeline.boards) appendLine("board ${encode(board)}")
            }
            for (move in turn) appendLine("move ${encode(move.from)} ${encode(move.to)}")
            for (move in session.lastTurn) appendLine("last ${encode(move.from)} ${encode(move.to)}")
            // The log without the current turn's lines, which replaying its moves adds back.
            for (line in session.log.dropLast(turn.size)) appendLine("log $line")
        }
    }

    /** The game saved in [text], or null if it can't be read (another version, or damaged). */
    fun decode(text: String): GameSession? = try {
        read(text.lines().filter { it.isNotBlank() })
    } catch (_: RuntimeException) {
        null
    }

    private fun read(lines: List<String>): GameSession? {
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
        for (line in lines.drop(1)) {
            val key = line.substringBefore(' ')
            val rest = line.substringAfter(' ', "")
            val words = rest.split(' ')
            when (key) {
                "mode" -> mode = GameMode(
                    computerSide = words[0].takeIf { it != "-" }?.let { Side.valueOf(it) },
                    variant = Variant.valueOf(words[1]),
                    timelineLimit = words[2].takeIf { it != "-" }?.toInt(),
                )
                "outcome" -> {
                    outcome = decodeOutcome(words[0])
                    undecided = words[1] == "1"
                }
                "side" -> side = Side.valueOf(words[0])
                "timeline" -> {
                    val index = words[0].toInt()
                    timelines[index] = Triple(index, words[1].toInt(), words[2].takeIf { it != "-" }?.toInt()) to ArrayList()
                    current = index
                }
                "board" -> timelines.getValue(current!!).second += decodeBoard(rest)
                "move" -> moves += decodePos(words[0]) to decodePos(words[1])
                "last" -> last += decodePos(words[0]) to decodePos(words[1])
                "log" -> log += rest
            }
        }
        val gameMode = mode ?: return null
        val start = GameState(
            timelines.mapValues { (_, value) -> Timeline(value.first.first, value.first.second, value.second, value.first.third) },
            side,
            timelineLimit = gameMode.timelineLimit,
        )
        // The last turn's moves are only shown, as highlights; keep the ones that can be told apart.
        val lastTurn = last.map { (from, to) -> Move(from, to) }
        var session = GameSession(gameMode, state = start, outcome = outcome, undecided = undecided, log = log, lastTurn = lastTurn)
        for ((from, to) in moves) {
            val move = session.state.legalMovesFrom(from).firstOrNull { it.to == to } ?: return null
            session = session.play(move)
        }
        return session
    }

    private fun turnStart(session: GameSession): GameState {
        var s = session
        while (s.state.turnMoves.isNotEmpty()) s = s.previous ?: error("Turn start not in the undo history")
        return s.state
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

    internal fun decodePos(text: String): Pos = text.split(',').map { it.toInt() }.let { (l, t, x, y) -> Pos(l, t, x, y) }

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
