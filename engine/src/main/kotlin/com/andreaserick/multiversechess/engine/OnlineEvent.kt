package com.andreaserick.multiversechess.engine

/**
 * One step of an online game. The game is the list of its events, kept by the relay in the order
 * they happened; each device replays them with [GameSession.applyEvent] to reach the same position.
 */
sealed interface OnlineEvent {
    /**
     * A submitted turn: its moves, as from and to positions, and the [outcome] the submitting
     * device judged it to have.
     */
    data class Turn(val moves: List<Pair<Pos, Pos>>, val outcome: Outcome, val undecided: Boolean) : OnlineEvent

    /** The game ended without a turn: a resignation, or no legal turn found by searching every one. */
    data class End(val outcome: Outcome) : OnlineEvent

    /**
     * In a game with the Inferno, the soul at [index] (among the game's souls) has passed its
     * trials and returns, at the start of its side's turn, on [at]: where both devices place it
     * (see [Inferno.returnSquare]), sent along so they can tell they agree.
     */
    data class Free(val index: Int, val at: Pos) : OnlineEvent

    companion object {
        /**
         * The event an online game sends when an action on this device takes it from [from] to
         * [next]: a submitted turn, or an ending without one (a resignation, or no legal turn).
         * Null when nothing needs sending (a selection, a move tried, an undo within the turn),
         * or the game isn't online.
         */
        fun between(from: GameSession, next: GameSession): OnlineEvent? = when {
            from.mode.onlineSide == null -> null
            next.state.sideToMove != from.state.sideToMove ->
                Turn(from.state.turnMoves.map { it.from to it.to }, next.outcome, next.undecided)
            from.outcome == Outcome.Ongoing && next.outcome != Outcome.Ongoing -> End(next.outcome)
            else -> null
        }

        /** E.g. `turn ongoing 0 0,0,4,1>0,0,4,3`, `end resignation:WHITE` or `free 2 0,4,2,0`. */
        fun encode(event: OnlineEvent): String = when (event) {
            is Turn -> buildString {
                append("turn ").append(SavedGame.encode(event.outcome)).append(if (event.undecided) " 1" else " 0")
                for ((from, to) in event.moves) append(' ').append(SavedGame.encode(from)).append('>').append(SavedGame.encode(to))
            }
            is End -> "end ${SavedGame.encode(event.outcome)}"
            is Free -> "free ${event.index} ${SavedGame.encode(event.at)}"
        }

        /** The event in [text], or null if it can't be read. */
        fun decode(text: String): OnlineEvent? = try {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val words = trimmed.split(' ')
            when (words[0]) {
                "turn" -> {
                    require(words.size >= 4 && (words[2] == "0" || words[2] == "1")) { "Malformed turn event" }
                    Turn(
                        moves = words.drop(3).map { move ->
                            val parts = move.split('>')
                            require(parts.size == 2) { "Malformed move pair" }
                            SavedGame.decodePos(parts[0]) to SavedGame.decodePos(parts[1])
                        },
                        outcome = SavedGame.decodeOutcome(words[1]),
                        undecided = words[2] == "1",
                    )
                }
                "end" -> {
                    require(words.size == 2) { "Malformed end event" }
                    End(SavedGame.decodeOutcome(words[1])).takeIf { it.outcome != Outcome.Ongoing }
                }
                "free" -> {
                    require(words.size == 3) { "Malformed free event" }
                    val index = words[1].toInt()
                    require(index >= 0) { "Negative soul index" }
                    Free(index, SavedGame.decodePos(words[2]))
                }
                else -> null
            }
        } catch (_: RuntimeException) {
            null
        }
    }
}
