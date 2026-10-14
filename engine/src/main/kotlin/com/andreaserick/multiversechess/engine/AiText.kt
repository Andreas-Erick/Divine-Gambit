package com.andreaserick.multiversechess.engine

/**
 * What a language model reads and writes to play this game or design its puzzles. The model never
 * moves pieces itself: as a player it chooses one of the legal turns the engine lists, and as a
 * puzzle designer it writes a position in a simple text format that the engine replays and checks.
 */
object AiText {
    /** The rules in brief, for both jobs. */
    private const val RULES = """The game is chess with time travel and parallel timelines (like "5D Chess With Multiverse Time Travel").
- The multiverse is made of timelines (L0 is the original; White's new ones are L+1, L+2…, Black's L-1, L-2…). Each timeline is a row of boards, one per half-move: turn 1 White, turn 1 Black, turn 2 White, and so on.
- A board is named by its timeline and turn, like "L0 T3". On each board, ranks and files are as in chess (a1 is White's queenside corner).
- Pieces move through four dimensions: file, rank, time (one step = one whole turn back) and timeline (one step = one timeline over). A rook goes any distance along one dimension; a bishop the same distance along two; a queen along any number; a king one step in any combination; a knight two along one dimension and one along another. Pawns move forward on the board, or one timeline toward the opponent.
- A piece can only land on boards where its own side moves. Moving to the latest board of another timeline continues that timeline; moving to an earlier board creates a new timeline branching from it (time travel).
- On your turn you must move on every board in "the present" (the earliest latest-board of the active timelines), and may move on your other latest boards; then the turn is submitted.
- You may not end your turn with any of your kings capturable, on any board, past or present. Checkmate: the side to move has no turn that leaves all its kings safe while one is attacked."""

    const val PLAYER_SYSTEM = "You are a chess player with a distinct, friendly personality, playing a game of multiverse chess " +
        "on a phone. $RULES\n\nEach turn you get the position and a numbered list of legal turns that a chess engine found. " +
        "Choose one of them. Answer with the number of your choice on the first line, then one short sentence " +
        "(at most 15 words) to your opponent about your move, in character, in your own words: don't repeat the " +
        "move's notation. Nothing else."

    /**
     * The prompt for choosing a turn in [state] among [choices], which the engine found (best
     * first, by its own judgement).
     */
    fun choosePrompt(state: GameState, choices: List<ComputerPlayer.RankedTurn>): String = buildString {
        appendLine("You play ${state.sideToMove.displayName}.")
        appendLine()
        append(describe(state))
        appendLine()
        appendLine("Legal turns you can play (the engine lists the ones it likes best first):")
        choices.forEachIndexed { i, turn ->
            var s = state
            val moves = turn.moves.joinToString("; ") { move -> Notation.describe(s, move).also { s = s.apply(move) } }
            val notes = buildList {
                if (turn.mates) add("checkmate!")
                if (turn.moves.any { !it.isSpatial }) add("travels between boards")
            }
            appendLine("${i + 1}. $moves" + if (notes.isEmpty()) "" else "  (${notes.joinToString(", ")})")
        }
        appendLine()
        append("Your choice (a number from 1 to ${choices.size}) and your sentence:")
    }

    /**
     * The turn chosen in [reply] (as an index into the [count] choices) and the sentence that came
     * with it, or null if the reply doesn't name one.
     */
    fun parseChoice(reply: String, count: Int): Pair<Int, String?>? {
        val number = Regex("""\d+""").find(reply)?.value?.toIntOrNull() ?: return null
        if (number !in 1..count) return null
        val remark = clean(reply.lines().drop(1).joinToString(" ").ifBlank { reply.substringAfter(number.toString()) })
            .trimStart(' ', '.', ':', ')', '-', '—')
            .trim('"', '“', '”', '\'', ' ')
            .let(::shorten)
            // A small model may just copy the move from the list, which says nothing.
            .takeUnless { it.contains(LISTED_MOVE) }
        return number - 1 to remark?.ifBlank { null }
    }

    /** A move as the list of choices writes it, such as "(L0 T2) Ng8-f6". */
    private val LISTED_MOVE = Regex("""\(L[+-]?\d+ T\d+\)""")

    /**
     * [text] if it's at most [MAX_REMARK] characters; otherwise its first sentences that fit, or
     * failing that, its words that fit followed by an ellipsis.
     */
    internal fun shorten(text: String): String {
        if (text.length <= MAX_REMARK) return text
        val head = text.take(MAX_REMARK)
        val sentence = Regex("""[.!?](?=\s)""").findAll(head).lastOrNull()
        if (sentence != null && sentence.range.first >= MAX_REMARK / 3) return head.take(sentence.range.first + 1)
        return head.take(MAX_REMARK - 1).substringBeforeLast(' ').trimEnd(',', ';', ':', ' ') + "…"
    }

    /**
     * The latest board of every timeline in [state], for a reader: which boards the side to move
     * must or may play, and the pieces on each (upper case White, lower case Black).
     */
    fun describe(state: GameState): String = buildString {
        val side = state.sideToMove
        val mandatory = state.mandatoryTimelines.toSet()
        appendLine("${side.displayName} to move. The present is turn ${Notation.turnNumber(state.present)}.")
        for (timeline in state.timelines.values.sortedBy { it.index }) {
            val t = timeline.end
            val label = "L${Notation.timelineLabel(timeline.index)} T${Notation.turnNumber(t)}"
            val status = when {
                Side.toMoveAt(t) != side -> "${side.opponent.displayName} moves next here"
                timeline.index in mandatory -> "${side.displayName} must move here"
                else -> "${side.displayName} may move here"
            }
            val inactive = if (state.isActive(timeline.index)) "" else ", inactive timeline"
            appendLine()
            appendLine("Board $label (latest of timeline L${Notation.timelineLabel(timeline.index)}; $status$inactive):")
            append(boardText(timeline.last))
        }
    }

    private fun boardText(board: Board): String = buildString {
        for (y in BOARD_SIZE - 1 downTo 0) {
            append("${y + 1} ")
            for (x in 0 until BOARD_SIZE) {
                val piece = board[x, y]
                append(if (piece == null) '.' else if (piece.side == Side.WHITE) piece.type.letter else piece.type.letter.lowercaseChar())
            }
            appendLine()
        }
        appendLine("  abcdefgh")
    }

    // Puzzles.

    /** The built-in puzzle "Into the past", in the format puzzle designers write. */
    const val EXAMPLE_PUZZLE = """TITLE: Into the past
HINT: A king can step away from your pieces, but its past self can't.
WHITE: Kg1, Re1, Pf2, Pg2, Ph2
BLACK: Ke8, Pa7, Pb7, Pc7
HISTORY:
g1h1
e8d7
h1g1
d7c6
SOLUTION:
e1e8"""

    const val DESIGNER_SYSTEM = "You design puzzles for a game of multiverse chess on a phone. $RULES\n\n" +
        "A puzzle is \"White to move and checkmate in one turn\". You write it in this exact format:\n\n" +
        "TITLE: a short, evocative title (at most 32 characters)\n" +
        "HINT: one sentence that nudges without giving the answer away\n" +
        "WHITE: White's pieces on the starting board, as a piece letter and a square, separated by commas: K king, Q queen, " +
        "R rook, B bishop, N knight, P pawn (e.g. Kg1, Re1, Pf2)\n" +
        "BLACK: Black's pieces, the same way (e.g. Ke8, Pa7). Each side has exactly one king.\n" +
        "HISTORY: the turns played from that board before the puzzle, one line per turn, starting with White and " +
        "alternating, ending with Black's turn so that White is to move. A line lists the turn's moves separated by commas.\n" +
        "SOLUTION: White's mating turn, its moves separated by commas.\n\n" +
        "A move on the latest board of a timeline is written like \"e2e4\" (timeline L0) or \"L+1 e2e4\". A move between " +
        "boards (through time or across timelines) is written from board and square to board and square, like " +
        "\"L0 T3 c3 > L0 T2 c3\": T is the turn number, and the board is the moving side's board at that turn.\n\n" +
        "The history gives the puzzle its past: a king that has moved, a piece sent back in time to create a second " +
        "timeline. Good puzzles use time travel: a rook, bishop or knight striking a king's past position, or a move from " +
        "one timeline to another. Keep it small: at most 3 timelines and a few pieces. The puzzle must have one or two " +
        "mating turns, not more. An engine checks your puzzle and tells you what's wrong, if anything.\n\n" +
        "Here is a valid example:\n\n$EXAMPLE_PUZZLE\n\nAnswer with the puzzle only, in the format above."

    /** The first request for a puzzle, optionally on a [theme]. */
    fun designPrompt(theme: String?): String =
        "Design a new puzzle" + (theme?.takeIf { it.isNotBlank() }?.let { " on this theme: ${clean(it)}" } ?: "") +
            ". Make it different from the example."

    /** Asks for a corrected puzzle after [problem]. */
    fun retryPrompt(problem: String): String =
        "The engine checked your puzzle: $problem\nFix it and answer with the whole corrected puzzle, in the same format."

    const val PRESENTER_SYSTEM = "You write titles and hints for puzzles in a game of multiverse chess on a phone. $RULES"

    /** Asks for a title and hint for [puzzle], a valid puzzle the engine made. */
    fun presentPrompt(puzzle: Puzzle): String = buildString {
        val start = puzzle.start()
        appendLine("Here is a \"White to move and checkmate in one turn\" puzzle.")
        appendLine()
        append(describe(start))
        appendLine()
        var s = start
        appendLine("The solution: " + puzzle.solution.joinToString("; ") { (from, to) ->
            val move = s.legalMovesFrom(from).first { it.to == to }
            Notation.describe(s, move).also { s = s.apply(move) }
        })
        appendLine()
        appendLine("Write a short, evocative title (at most 32 characters) and one hint sentence that nudges without giving the solution away. Answer exactly:")
        appendLine("TITLE: …")
        append("HINT: …")
    }

    /** The TITLE and HINT lines of [reply], cleaned up, if it has both. */
    fun parseTitleAndHint(reply: String): Pair<String, String>? {
        val title = field(reply, "TITLE")?.take(MAX_TITLE) ?: return null
        val hint = field(reply, "HINT")?.take(MAX_HINT) ?: return null
        return title to hint
    }

    private fun field(text: String, name: String): String? =
        text.lines().firstOrNull { startsSection(it, name) }
            ?.substringAfter(':')?.let(::clean)?.trim('"', '*', ' ')?.takeIf { it.isNotEmpty() }

    /** Whether [line] starts the section [name], however it's decorated ("**TITLE:**", "## Title:"). */
    internal fun startsSection(line: String, name: String): Boolean =
        line.trim().trimStart('#', '*', ' ').uppercase().replace("*", "").startsWith("$name:")

    /** [text] on one line, without control characters or markdown emphasis. */
    private fun clean(text: String): String =
        text.replace(Regex("""[\p{Cc}\p{Cf}]"""), " ").replace("**", "").replace(Regex("""\s+"""), " ").trim()

    const val MAX_TITLE = 40
    const val MAX_HINT = 200
    const val MAX_REMARK = 120
}

/** A puzzle as a designer wrote it: not yet replayed or checked. */
class PuzzleDraft(val title: String, val hint: String, val board: List<String>, val history: List<String>, val solution: String?)

/** What checking a [PuzzleDraft] found: a [Puzzle], or the [problem] to tell its designer. */
sealed interface DraftCheck {
    class Valid(val puzzle: Puzzle) : DraftCheck
    class Invalid(val problem: String) : DraftCheck
}

/** Reads and checks puzzles written in [AiText]'s format. */
object PuzzleDrafts {
    /** Positions the solver may look at before calling a puzzle too complicated. */
    private const val SOLVER_BUDGET = 40_000
    private const val MAX_TIMELINES = 4
    private const val MAX_MATES = 2

    /** The draft in [text], or the reason it can't be read. */
    fun parse(text: String): Result<PuzzleDraft> = runCatching {
        val lines = text.lines().map { it.replace(ASIDE, " ").trim().trim('`') }.filter { it.isNotEmpty() && !it.startsWith("```") }
        fun section(name: String): List<String>? {
            val start = lines.indexOfFirst { AiText.startsSection(it, name) }
            if (start < 0) return null
            val first = lines[start].substringAfter(':').trim().trim('*', ' ')
            val rest = lines.drop(start + 1).takeWhile { line -> SECTIONS.none { AiText.startsSection(line, it) } }
            return listOfNotNull(first.takeIf { it.isNotEmpty() }) + rest
        }
        fun required(name: String) = requireNotNull(section(name)) { "The $name section is missing." }
        val white = section("WHITE")
        val black = section("BLACK")
        // Pieces by square; a board drawn as 8 lines of 8 characters is read too.
        val board = if (white != null || black != null) {
            boardFromPieces(white.orEmpty().joinToString(","), black.orEmpty().joinToString(","))
        } else {
            val rows = required("BOARD").map { it.replace(" ", "") }.filter { it.isNotEmpty() }
            require(rows.size == BOARD_SIZE && rows.all { it.length == BOARD_SIZE }) {
                "The BOARD must be 8 lines of 8 characters (it has ${rows.size} lines: ${rows.joinToString(" / ")}). " +
                    "Better: list the pieces instead, as WHITE: and BLACK: lines."
            }
            rows
        }

        PuzzleDraft(
            title = section("TITLE")?.firstOrNull()?.take(AiText.MAX_TITLE) ?: "",
            hint = section("HINT")?.joinToString(" ")?.take(AiText.MAX_HINT) ?: "",
            board = board,
            history = section("HISTORY").orEmpty().filter { !it.trim('.', ' ').equals("none", ignoreCase = true) },
            solution = required("SOLUTION").joinToString(", ").takeIf { it.isNotBlank() },
        )
    }

    private val SECTIONS = listOf("TITLE", "HINT", "WHITE", "BLACK", "BOARD", "HISTORY", "SOLUTION")

    /** A comment in parentheses, such as "(White moves the pawn)", but not a board like "(L0 T3)". */
    private val ASIDE = Regex("""\((?!\s*L\s*[+-]?\d)[^)]*\)""")

    private val PIECE = Regex("""^([KQRBNPSUDWYCkqrbnpsudwyc])?([a-hA-H][1-8])$""")

    /** A board, as 8 rows from rank 8 down, from the pieces listed for each side, like "Kg1, Re1, Pf2". */
    private fun boardFromPieces(white: String, black: String): List<String> {
        val rows = Array(BOARD_SIZE) { CharArray(BOARD_SIZE) { '.' } }
        for ((side, list) in listOf(Side.WHITE to white, Side.BLACK to black)) {
            for (token in list.split(',', ';', ' ').map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }) {
                val match = PIECE.find(token) ?: throw IllegalArgumentException(
                    "'$token' in ${side.displayName.uppercase()} isn't a piece and square like \"Kg1\" or \"Pf2\".",
                )
                val letter = match.groupValues[1].ifEmpty { "P" }.uppercase()[0]
                val square = Square.parse(match.groupValues[2].lowercase())
                val row = BOARD_SIZE - 1 - square.y
                require(rows[row][square.x] == '.') { "Two pieces are placed on ${square}." }
                rows[row][square.x] = if (side == Side.WHITE) letter else letter.lowercaseChar()
            }
        }
        return rows.map { String(it) }
    }

    /** Reads and checks the puzzle in [text]: what can't be read is a problem like any other. */
    fun read(text: String, id: String, stop: () -> Boolean = { false }): DraftCheck =
        parse(text).fold({ check(it, id, stop) }, { DraftCheck.Invalid(it.message ?: "The puzzle can't be read.") })

    /**
     * Replays [draft]'s history and checks that White then has one or two mating turns. A valid
     * puzzle uses the designer's solution if it's one of them, and is numbered [id].
     */
    fun check(draft: PuzzleDraft, id: String, stop: () -> Boolean = { false }): DraftCheck {
        val start = try {
            startPosition(draft)
        } catch (e: IllegalArgumentException) {
            return DraftCheck.Invalid(e.message ?: "The position can't be set up.")
        }
        if (draft.title.isBlank() || draft.hint.isBlank()) return DraftCheck.Invalid("The TITLE or HINT is missing.")
        var state = start
        for ((i, line) in draft.history.withIndex()) {
            val mover = state.sideToMove
            val label = "History turn ${i + 1} (${mover.displayName})"
            for (text in line.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
                state = when (val played = play(state, text)) {
                    is Played.Ok -> played.state
                    is Played.Wrong -> return DraftCheck.Invalid("$label: ${played.problem}")
                }
            }
            if (state.turnMoves.isEmpty()) return DraftCheck.Invalid("$label has no moves.")
            if (!state.canSubmit) {
                val missing = state.mandatoryTimelines.filter { l -> state.turnMoves.none { it.from.l == l || it.to.l == l } }
                return DraftCheck.Invalid("$label doesn't move on every board in the present: ${missing.joinToString { "L${Notation.timelineLabel(it)}" }} still needs a move.")
            }
            if (state.isInCheck(mover)) return DraftCheck.Invalid("$label leaves ${mover.displayName}'s king capturable, which isn't allowed.")
            state = state.submit()
            if (TurnSearch(10_000, stop = stop).findLegalTurn(state) == TurnSearchResult.NoLegalTurn) {
                return DraftCheck.Invalid("$label already ends the game.")
            }
        }
        if (state.sideToMove != Side.WHITE) return DraftCheck.Invalid("After the history it's Black's move; the history must end with Black's turn.")
        if (state.timelines.size > MAX_TIMELINES) return DraftCheck.Invalid("The puzzle has ${state.timelines.size} timelines; keep it to at most 3.")
        if (state.threats().isNotEmpty()) return DraftCheck.Invalid("White's king is already in check at the start; it shouldn't be.")
        if (state.isInCheck(Side.BLACK)) return DraftCheck.Invalid("At the start White could simply capture Black's king; it shouldn't be in check yet.")
        val mates = PuzzleSolver.matesWithin(state, SOLVER_BUDGET, maxOptionalMoves = 0, stop = stop)
            ?: return DraftCheck.Invalid("The position is too complicated to check; use fewer pieces and timelines.")
        if (mates.isEmpty()) return DraftCheck.Invalid("White has no turn that checkmates. " + whyNot(draft, state))
        if (mates.size > MAX_MATES) {
            val examples = mates.take(3).joinToString("; ") { turn -> describeTurn(state, turn) }
            return DraftCheck.Invalid("White has ${mates.size} different mating turns (for example $examples); a puzzle should have one or two. Remove attackers or add defenders.")
        }
        val stated = draft.solution?.let { solutionMoves(state, it) }
        val solution = mates.firstOrNull { mate -> stated != null && mate.map { it.from to it.to } == stated } ?: mates.minBy { it.size }
        return DraftCheck.Valid(Puzzle(id, draft.title, draft.hint, solution.map { it.from to it.to }) { position(state) })
    }

    /** Why the stated solution doesn't mate, if one was given. */
    private fun whyNot(draft: PuzzleDraft, position: GameState): String {
        val text = draft.solution ?: return ""
        var s = position
        for (move in text.split(',').map { it.trim() }.filter { it.isNotEmpty() }) {
            s = when (val played = play(s, move)) {
                is Played.Ok -> played.state
                is Played.Wrong -> return "Your solution doesn't work: ${played.problem}"
            }
        }
        if (!s.canSubmit) return "Your solution doesn't move on every board in the present."
        if (s.isInCheck(Side.WHITE)) return "Your solution leaves White's king capturable."
        val after = s.submit()
        val escape = (TurnSearch(Puzzle.VERIFY_BUDGET, maxOptionalMoves = 2).findLegalTurn(after) as? TurnSearchResult.Found)
            ?.result?.turnMoves?.firstOrNull()
        return if (escape != null) "After your solution, Black escapes with ${Notation.describe(after, escape)}." else ""
    }

    private fun describeTurn(state: GameState, moves: List<Move>): String {
        var s = state
        return moves.joinToString(", ") { move -> Notation.describe(s, move).also { s = s.apply(move) } }
    }

    private fun solutionMoves(position: GameState, text: String): List<Pair<Pos, Pos>>? {
        var s = position
        return text.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { move ->
            val played = play(s, move) as? Played.Ok ?: return null
            s = played.state
            played.move.from to played.move.to
        }
    }

    private fun startPosition(draft: PuzzleDraft): GameState {
        val rows = draft.board
        for (row in rows) for (c in row) require(c == '.' || PieceType.entries.any { it.letter == c.uppercaseChar() }) {
            "The BOARD has an unknown piece '$c'."
        }
        val text = rows.joinToString("")
        require(text.count { it == 'K' } == 1 && text.count { it == 'k' } == 1) { "The BOARD must have exactly one White king (K) and one Black king (k)." }
        require(rows.first().none { it == 'P' } && rows.last().none { it == 'p' } && rows.first().none { it == 'p' } && rows.last().none { it == 'P' }) {
            "Pawns can't stand on the first or last rank."
        }
        val board = Board.parse(*rows.toTypedArray())
        return GameState(mapOf(0 to Timeline(0, 0, listOf(board))), Side.WHITE)
    }

    private sealed interface Played {
        class Ok(val state: GameState, val move: Move) : Played
        class Wrong(val problem: String) : Played
    }

    // Moves as people write them, with or without the piece's letter, "x" for a capture, and check
    // or promotion marks: "e2e4", "Ra3-a8", "L+1 Nb1xc3+", "L0 T3 Nc3 > L0 T2 xc3".
    private const val LETTER = "[KQRBNPSUDWYC]?"
    private const val MARKS = """(?:=?[QRBN])?[+#!?]*"""
    private val SPATIAL = Regex("""^(?:L\s*([+-]?\d+)\s+)?(?:T\s*\d+\s+)?$LETTER([a-h][1-8])\s*[-x]?\s*$LETTER([a-h][1-8])$MARKS$""")
    /** Short notation, naming only where the piece goes: "e4", "Qxd5", "L+1 Nc3". */
    private val SHORT = Regex("""^(?:L\s*([+-]?\d+)\s+)?(?:T\s*\d+\s+)?([KQRBNPSUDWYC])?x?([a-h][1-8])$MARKS$""")
    private val BETWEEN = Regex(
        """^\(?L\s*([+-]?\d+)\s*,?\s*T\s*(\d+)\)?\s*$LETTER([a-h][1-8])\s*(?:>|->|→)\s*\(?L\s*([+-]?\d+)\s*,?\s*T\s*(\d+)\)?\s*x?$LETTER([a-h][1-8])$MARKS$""",
    )

    /**
     * A move in short notation: the one move, on a board the side to move can play, of a piece of
     * that kind (a pawn without a letter) to that square on its own board.
     */
    private fun short(state: GameState, text: String, match: MatchResult): Played {
        val timeline = match.groupValues[1].ifEmpty { null }?.toInt()
        val type = match.groupValues[2].ifEmpty { "P" }[0]
        val square = match.groupValues[3]
        val moves = state.timelines.values
            .filter { (timeline == null || it.index == timeline) && state.isPlayable(it.index, it.end) }
            .flatMap { line ->
                val target = Pos.of(line.index, line.end, square)
                line.last.pieces()
                    .filter { (_, piece) -> piece.side == state.sideToMove && piece.type.letter == type }
                    .flatMap { (from, _) -> state.legalMovesFrom(Pos(line.index, line.end, from.x, from.y)).filter { it.to == target } }
            }
        return when (moves.size) {
            1 -> Played.Ok(state.apply(moves[0]), moves[0])
            0 -> Played.Wrong("'$text': no ${state.sideToMove.displayName} piece of that kind can move to $square on a board it can play. Write the move from square to square, like \"e2e4\".")
            else -> Played.Wrong("'$text' could be several moves (${moves.joinToString { Notation.describe(state, it) }}); write it from square to square, like \"e2e4\".")
        }
    }

    /** Plays one move written as [text] in [state], or says what's wrong with it. */
    private fun play(state: GameState, text: String): Played {
        val mover = state.sideToMove
        val (from, to) = SPATIAL.find(text)?.let { m ->
            val l = m.groupValues[1].ifEmpty { "0" }.toInt()
            val timeline = state.timelines[l] ?: return Played.Wrong("'$text': there's no timeline L${Notation.timelineLabel(l)}.")
            Pos.of(l, timeline.end, m.groupValues[2].lowercase()) to Pos.of(l, timeline.end, m.groupValues[3].lowercase())
        } ?: BETWEEN.find(text)?.let { m ->
            fun board(l: String, turn: String) = l.toInt() to (turn.toInt() - 1) * 2 + if (mover == Side.BLACK) 1 else 0
            val (fl, ft) = board(m.groupValues[1], m.groupValues[2])
            val (tl, tt) = board(m.groupValues[4], m.groupValues[5])
            Pos.of(fl, ft, m.groupValues[3].lowercase()) to Pos.of(tl, tt, m.groupValues[6].lowercase())
        } ?: return SHORT.find(text)?.let { short(state, text, it) }
            ?: Played.Wrong("'$text' isn't a move in the expected format (\"e2e4\", \"L+1 e2e4\" or \"L0 T3 c3 > L0 T2 c3\").")

        val where = "${Notation.boardLabel(from.l, from.t)} ${from.square}"
        val piece = state.pieceAt(from) ?: return Played.Wrong("'$text': there's no piece on $where.")
        if (piece.side != mover) return Played.Wrong("'$text': the piece on $where is ${piece.side.displayName}'s, but it's ${mover.displayName}'s turn.")
        if (!state.isPlayable(from.l, from.t)) return Played.Wrong("'$text': ${mover.displayName} can't move on ${Notation.boardLabel(from.l, from.t)} now; only on the latest boards of timelines.")
        val legal = state.legalMovesFrom(from)
        val move = legal.firstOrNull { it.to == to }
            ?: return Played.Wrong("'$text' isn't legal. The ${piece.type.displayName.lowercase()} on $where can go to: " +
                legal.take(12).joinToString { Notation.describe(state, it).substringAfter(") ") }.ifEmpty { "nowhere" } + ".")
        return Played.Ok(state.apply(move), move)
    }
}
