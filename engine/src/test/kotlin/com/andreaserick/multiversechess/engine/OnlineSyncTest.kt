package com.andreaserick.multiversechess.engine

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Two devices playing through an in-memory relay: a list of events, each with its sender. */
class OnlineSyncTest {
    private val mode = GameMode(timelineLimit = 4)

    /** One device: its game, and where it stands in the relay's list. */
    private class Device(side: Side, mode: GameMode) {
        var game = GameSession(mode.copy(onlineSide = side))
        var sync = OnlineSync()
        val side: Side = side

        /** Tries [moves] and submits them: returns what it offers the relay, if anything. */
        fun submit(vararg moves: Pair<Pos, Pos>): OnlineSync.Outgoing? {
            for ((from, to) in moves) game = game.tap(from).tap(to)
            val next = game.submit()
            val event = OnlineEvent.between(game, next) ?: return null
            sync = sync.send(event)
            return sync.outbox
        }

        fun receive(index: Int, sender: Side, text: String): OnlineSync.Received {
            val result = sync.receive(game, index, sender, text).let { if (it is OnlineSync.Received.Played) it.confirm() else it }
            if (result is OnlineSync.Received.Played) {
                sync = result.sync
                game = result.game
            }
            return result
        }
    }

    private val relay = ArrayList<Pair<Side, String>>()

    /** The relay accepts [outgoing] from [device] and echoes it to both devices. */
    private fun deliver(outgoing: OnlineSync.Outgoing?, device: Device, vararg to: Device) {
        assertNotNull(outgoing)
        assertEquals(relay.size, outgoing.index)
        relay += device.side to outgoing.text
        for (d in to) assertIs<OnlineSync.Received.Played>(d.receive(outgoing.index, device.side, outgoing.text))
    }

    @Test
    fun `two devices reach the same game through the relay`() {
        val white = Device(Side.WHITE, mode)
        val black = Device(Side.BLACK, mode)
        deliver(white.submit(Pos.of(0, 0, "e2") to Pos.of(0, 0, "e4")), white, white, black)
        assertFalse(white.sync.sending)
        deliver(black.submit(Pos.of(0, 1, "e7") to Pos.of(0, 1, "e5")), black, white, black)
        assertEquals(white.game.log, black.game.log)
        assertEquals(2, white.sync.applied)
        assertEquals(Side.WHITE, black.game.state.sideToMove)
        // Echoes repeated on reconnecting change nothing.
        assertEquals(OnlineSync.Received.Repeated, white.receive(0, Side.WHITE, relay[0].second))
    }

    @Test
    fun `events out of order ask for a reconnect, and a hello catches up`() {
        val white = Device(Side.WHITE, mode)
        val black = Device(Side.BLACK, mode)
        deliver(white.submit(Pos.of(0, 0, "e2") to Pos.of(0, 0, "e4")), white, white)
        // Black's device never got event 0, and then sees event 1.
        assertEquals(OnlineSync.Received.Missed, black.receive(1, Side.BLACK, "turn ongoing 0 0,1,4,6>0,1,4,4"))
        // Connecting again brings every event.
        for ((index, event) in relay.withIndex()) assertIs<OnlineSync.Received.Played>(black.receive(index, event.first, event.second))
        assertEquals(Side.BLACK, black.game.state.sideToMove)
    }

    @Test
    fun `a turn sent as the connection dropped is sent again, unless it got there`() {
        val white = Device(Side.WHITE, mode)
        val outgoing = assertNotNull(white.submit(Pos.of(0, 0, "e2") to Pos.of(0, 0, "e4")))
        assertTrue(white.sync.sending)
        // It never reached the relay: on reconnecting, the hello has no events, so it goes again.
        val (stillWaiting, resend) = white.sync.afterCatchingUp()
        assertEquals(outgoing, resend)
        assertTrue(stillWaiting.sending)
        // It did reach the relay: the hello brings it back, and there's nothing to resend.
        assertIs<OnlineSync.Received.Played>(white.receive(0, Side.WHITE, outgoing.text))
        val (caughtUp, none) = white.sync.afterCatchingUp()
        assertNull(none)
        assertFalse(caughtUp.sending)
    }

    @Test
    fun `a move that breaks the rules stops the game`() {
        val black = Device(Side.BLACK, mode)
        val result = black.receive(0, Side.WHITE, "turn ongoing 0 0,0,4,1>0,0,4,5")
        assertEquals(OnlineSync.Received.Rejected(OnlineSync.BROKEN_RULES), result)
        assertEquals(OnlineSync.Received.Rejected(OnlineSync.BROKEN_RULES), black.receive(0, Side.WHITE, "garbage"))
        // Black can't send White's turn.
        assertIs<OnlineSync.Received.Rejected>(black.receive(0, Side.BLACK, "turn ongoing 0 0,0,4,1>0,0,4,3"))
    }

    @Test
    fun `a claimed checkmate that isn't one is caught`() {
        val black = Device(Side.BLACK, mode)
        val played = assertIs<OnlineSync.Received.Played>(black.sync.receive(black.game, 0, Side.WHITE, "turn checkmate:WHITE 0 0,0,4,1>0,0,4,3"))
        assertTrue(played.needsConfirming)
        val result = assertIs<OnlineSync.Received.Rejected>(played.confirm())
        assertTrue("checkmate" in result.problem)
    }

    @Test
    fun `only turns and endings are sent`() {
        val game = GameSession(mode.copy(onlineSide = Side.WHITE))
        val tried = game.tap(Pos.of(0, 0, "e2")).tap(Pos.of(0, 0, "e4"))
        assertNull(OnlineEvent.between(game, tried), "a move tried isn't sent")
        assertIs<OnlineEvent.Turn>(OnlineEvent.between(tried, tried.submit()))
        assertEquals(OnlineEvent.End(Outcome.Resignation(Side.BLACK)), OnlineEvent.between(game, game.resign()))
        assertNull(OnlineEvent.between(GameSession(mode), GameSession(mode).resign()), "not online")
    }
}
