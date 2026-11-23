package com.andreaserick.multiversechess.engine

/**
 * Where this device stands in an online game's list of events, kept by the relay: how many it has
 * played, and the event it has sent that the relay hasn't echoed back yet. Both devices play every
 * event in the relay's order, their own included, once it comes back, so they see the same game.
 *
 * The position itself stays in the caller's [GameSession]; this tracks delivery. Immutable.
 */
data class OnlineSync(
    /** How many of the game's events have been played here. */
    val applied: Int = 0,
    /** This player's event waiting for the relay's receipt. */
    val outbox: Outgoing? = null,
) {
    /** An event offered to the relay as the game's event number [index]. */
    data class Outgoing(val index: Int, val text: String)

    /** Whether this player's event is on its way, during which the game can't change here. */
    val sending: Boolean get() = outbox != null

    /** Offers [event] to the relay as the next event; the game shows it once the relay echoes it ([receive]). */
    fun send(event: OnlineEvent): OnlineSync = copy(outbox = Outgoing(applied, OnlineEvent.encode(event)))

    /**
     * Plays the game's event number [index], [text] as sent by [sender]'s player, on [current]:
     * the game as it stands here, with any moves tried but not sent.
     */
    fun receive(current: GameSession, index: Int, sender: Side, text: String): Received {
        if (index < applied) return Received.Repeated
        if (index > applied) return Received.Missed
        val next = OnlineEvent.decode(text)?.let { current.applyEvent(it, sender) } ?: return Received.Rejected(BROKEN_RULES)
        val claimed = next.outcome is Outcome.Checkmate || next.outcome == Outcome.Stalemate
        val needsConfirming = claimed && current.outcome == Outcome.Ongoing && sender != current.mode.onlineSide
        val sync = OnlineSync(applied + 1, outbox?.takeIf { it.index != applied })
        return Received.Played(sync, next, needsConfirming)
    }

    /**
     * After the relay has passed on every event so far, as it does on connecting: this player's
     * event to send again if the connection dropped before it got there, or none.
     */
    fun afterCatchingUp(): Pair<OnlineSync, Outgoing?> {
        val pending = outbox ?: return this to null
        return if (pending.index == applied) this to pending else copy(outbox = null) to null
    }

    /** The outcome of [receive]. */
    sealed interface Received {
        /** An event already played here; nothing changes. */
        data object Repeated : Received

        /** Events before this one were missed: connecting again brings them all. */
        data object Missed : Received

        /** The event breaks the rules, so the game can't go on; [problem] says why, for the player. */
        data class Rejected(val problem: String) : Received

        /**
         * The event played: the new [sync] and [game]. With [needsConfirming], the other device
         * claims the game ended in checkmate or stalemate; [confirm] checks that before it counts.
         */
        data class Played(val sync: OnlineSync, val game: GameSession, val needsConfirming: Boolean) : Received {
            /** This, once its claimed ending is checked (which can take a while), or why it's rejected. */
            fun confirm(stop: () -> Boolean = { false }): Received {
                if (!needsConfirming) return this
                if (game.confirmsOutcome(stop)) return copy(needsConfirming = false)
                val ending = if (game.outcome == Outcome.Stalemate) "stalemate" else "checkmate"
                return Rejected("Your friend's app said the game ended in $ending, but it didn't, so the game was stopped.")
            }
        }
    }

    companion object {
        const val BROKEN_RULES =
            "Your friend's app sent a move that isn't allowed, so the game was stopped. Make sure you both have the latest version."
    }
}
