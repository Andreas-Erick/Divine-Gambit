package com.andreaserick.multiversechess.online

import com.andreaserick.multiversechess.engine.Side

/** How an online game stands, for the screen. */
data class OnlineStatus(
    val ticket: Ticket,
    /** The invitation to share. */
    val link: String,
    val connected: Boolean = false,
    /** Whether the friend has taken their seat; until then the host waits in the lobby. */
    val opponentJoined: Boolean = false,
    /** Whether the friend has the game open right now. */
    val opponentPresent: Boolean = false,
    /** This player's turn is on its way to the relay. */
    val sending: Boolean = false,
    /** Why the game can't go on, if it can't. */
    val problem: String? = null,
    /** The names the players gave for this game, by side. */
    val names: Map<Side, String> = emptyMap(),
) {
    /** The name of [side]'s player, or the side's own name until it's known. */
    fun nameOf(side: Side): String = names[side] ?: side.displayName

    val opponentName: String get() = nameOf(ticket.side.opponent)
}

/** Creating or joining an online game: what's happening, and why it failed if it did. */
data class Connecting(val title: String, val error: String? = null)
