package com.andreaserick.multiversechess.engine

import kotlin.math.abs

/**
 * A piece taken in a game played with the Inferno rule ([GameMode.inferno]). Its owner wins it
 * back by solving [trials] puzzles; once [freed], it has returned to the board.
 */
data class Soul(val type: PieceType, val side: Side, val freed: Boolean = false) {
    val trials: Int get() = Inferno.trialsFor(type)
}

/**
 * The Inferno: taken pieces go there, and their owner can win them back by solving puzzles while
 * the game goes on. A freed piece returns at the start of its owner's turn, on a square where it
 * can't be taken straight away.
 */
object Inferno {
    /** How many puzzles win back a piece of [type]: the more it's worth, the more. */
    fun trialsFor(type: PieceType): Int = when {
        type.isPawnLike -> 1
        type.value >= 9 -> 4
        type.value >= 5 -> 3
        else -> 2
    }

    /**
     * Where [piece], of the side to move, returns to the board: an empty square on a board that
     * side plays now, in the present if possible, where the opponent couldn't take it if the
     * turn were passed, and from which it doesn't already attack an enemy royal piece. Squares
     * nearest the side's home ranks, then the middle files, come first. Null if there's none.
     */
    fun returnSquare(state: GameState, piece: Piece): Pos? {
        val side = piece.side
        if (state.sideToMove != side) return null
        val boards = (state.mandatoryTimelines + state.playableTimelines(side).map { it.index }).distinct()
        val squares = (0 until BOARD_SIZE).flatMap { x -> (0 until BOARD_SIZE).map { y -> Square(x, y) } }
            .filter { !piece.type.isPawnLike || (it.y != 0 && it.y != BOARD_SIZE - 1) }
            .sortedWith(compareBy({ abs(it.y - side.homeRank) }, { abs(it.x * 2 - (BOARD_SIZE - 1)) }, { it.x }))
        for (l in boards) {
            val t = state.timelines.getValue(l).end
            val board = state.timelines.getValue(l).last
            for (square in squares) {
                if (board[square] != null) continue
                val pos = Pos(l, t, square.x, square.y)
                if (isSafe(state.withPiece(pos, piece), pos)) return pos
            }
        }
        return null
    }

    private fun isSafe(placed: GameState, pos: Pos): Boolean {
        val side = placed.sideToMove
        val attacksRoyal = placed.generateMoves(pos, capturesOnly = true).any { placed.pieceAt(it.to)?.type?.isRoyal == true }
        if (attacksRoyal) return false
        // The side's boards as they'd be if it passed, so the opponent's reply can be seen.
        val passed = placed.timelines.toMutableMap()
        for (timeline in placed.playableTimelines(side)) passed[timeline.index] = timeline.appended(timeline.last.with(emptyList()))
        val after = GameState(passed, side, placed.turnMoves, placed.timelineLimit)
        val target = Pos(pos.l, pos.t + 1, pos.x, pos.y)
        return after.captures(side.opponent).none { it.to == target }
    }
}

/** A soul of the player's side still in the Inferno: its [index] among the game's souls, and the trials passed for it. */
data class WaitingSoul(val index: Int, val soul: Soul, val solved: Int) {
    /** It has passed all its trials, and returns at the start of its side's next turn. */
    val free: Boolean get() = solved >= soul.trials
}

/**
 * The player's progress in the Inferno of one game: trials passed for each soul (by its index
 * among the game's souls), and the time left there this turn. It belongs to the device, not the
 * game: online, each player's progress stays on their own device, and taking back a move doesn't
 * take back a trial passed meanwhile. Immutable; every change returns new progress.
 */
data class InfernoProgress(
    val solved: Map<Int, Int> = emptyMap(),
    val secondsLeft: Int = SECONDS_PER_TURN,
) {
    /** Souls of the side playing on this device in [game] still in the Inferno, freed by their trials or not. */
    fun souls(game: GameSession?): List<WaitingSoul> {
        if (game == null || !game.mode.inferno) return emptyList()
        val side = game.playerSide
        return game.souls.withIndex()
            .filter { (_, soul) -> soul.side == side && !soul.freed }
            .map { (i, soul) -> WaitingSoul(i, soul, solved[i] ?: 0) }
    }

    /** Souls that still have trials ahead. */
    fun waiting(game: GameSession?): List<WaitingSoul> = souls(game).filter { !it.free }

    /** Which soul the trials count for: [chosen] if it's waiting, or else the one needing the most trials. */
    fun trialsFor(game: GameSession?, chosen: Int?): Int? {
        val waiting = waiting(game)
        return chosen?.takeIf { c -> waiting.any { it.index == c } } ?: waiting.maxByOrNull { it.soul.trials }?.index
    }

    /** Why the Inferno can't be entered in [game] now, or null if it can. */
    fun closedReason(game: GameSession?): String? = when {
        game == null -> "No game."
        !game.mode.inferno || game.outcome != Outcome.Ongoing -> "The game is over."
        waiting(game).isEmpty() -> "None of your pieces are waiting for a trial."
        timeUp -> "Closed until you've played your turn."
        else -> null
    }

    val timeUp: Boolean get() = secondsLeft <= 0

    /** Whether the clock runs in [game]: only on the player's own turn, while the game goes on. */
    fun clockRuns(game: GameSession): Boolean = game.mode.inferno && game.isPlayersTurn && !timeUp

    /** A second spent in the Inferno. */
    fun tick(): InfernoProgress = copy(secondsLeft = (secondsLeft - 1).coerceAtLeast(0))

    /**
     * A trial passed in [game], counting for [chosen] if it's waiting, or else the first waiting
     * soul. Null if no soul is waiting.
     */
    fun pass(game: GameSession, chosen: Int?): TrialPassed? {
        val waiting = waiting(game)
        val soul = waiting.firstOrNull { it.index == chosen } ?: waiting.firstOrNull() ?: return null
        val progress = copy(solved = solved + (soul.index to soul.solved + 1))
        return TrialPassed(progress, soul.copy(solved = soul.solved + 1))
    }

    /**
     * The progress once the game moves from [previous] to [next]: each new turn brings a new
     * allowance of time, and trials for souls that undo took back out of the Inferno are dropped.
     */
    fun follow(previous: GameSession?, next: GameSession): InfernoProgress {
        val kept = if (solved.keys.any { it >= next.souls.size }) solved.filterKeys { it < next.souls.size } else solved
        val newTurn = previous != null && previous.state.sideToMove != next.state.sideToMove
        return if (kept === solved && !newTurn) this else copy(solved = kept, secondsLeft = if (newTurn) SECONDS_PER_TURN else secondsLeft)
    }

    /** The first soul in [game] that has passed its trials and can return to the board now, if any. */
    fun readyToFree(game: GameSession): Int? =
        game.souls.indices.firstOrNull { i -> game.canFree(i) && (solved[i] ?: 0) >= game.souls[i].trials }

    /** One `index solved` line per soul with trials passed. */
    fun encode(): String = solved.entries.sortedBy { it.key }.joinToString("\n") { (index, count) -> "$index $count" }

    companion object {
        /** Time in the Inferno on each of the player's turns. */
        const val SECONDS_PER_TURN = 90

        /** The progress in [text], as [encode] writes it, or null if it can't be read. */
        fun decode(text: String): InfernoProgress? = try {
            InfernoProgress(
                text.lines().filter { it.isNotBlank() }.associate { line ->
                    val parts = line.trim().split(' ')
                    require(parts.size == 2) { "Malformed trials line" }
                    val index = parts[0].toInt()
                    val count = parts[1].toInt()
                    require(index >= 0 && count >= 0) { "Negative trial count" }
                    index to count
                },
            )
        } catch (_: RuntimeException) {
            null
        }
    }
}

/** A trial passed: the new [progress], and the [soul] it counted for, with its new count. */
data class TrialPassed(val progress: InfernoProgress, val soul: WaitingSoul)
