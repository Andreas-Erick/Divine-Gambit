# Multiverse Chess (Android)

An Android chess variant with time travel and parallel timelines, inspired by
*5D Chess With Multiverse Time Travel*. This is an independent fan project. It is not
affiliated with or endorsed by the original game's developers, and it uses none of the
original game's code or assets.

## Opening the project

1. Open the repository root in **Android Studio** (Ladybug or newer) and let Gradle sync.
   It uses AGP 9.2, Kotlin 2.4 and Jetpack Compose, with Gradle 9.4.1 through the wrapper
   (which runs on the Java 25 runtime bundled with current Android Studio).
2. Run the `app` configuration on a device or emulator (Android 7.0 / API 24 or newer).

The rules live in the `engine` module, plain Kotlin with no Android dependencies, so they can be
tested quickly on the JVM:

```
./gradlew :engine:test
```

## Project layout

| Path | What it contains |
| --- | --- |
| `engine/` | Game rules: multiverse state, move generation, check/checkmate search, computer player, session/undo logic. Unit-tested. |
| `app/` | Android app: Compose UI that draws the multiverse, handles pan/zoom/tap, and runs the computer on a background thread. |

## How to play

* The screen shows every board in the multiverse. Each **row is a timeline** (L0 is the
  original; White's new timelines are numbered +1, +2… and drawn below, Black's −1, −2… above).
  Each **column is a half-move**, advancing to the right. Drag to pan and pinch to zoom.
* Tap one of your pieces on a board outlined in gold, then tap a green destination. Destinations
  can be on other boards.
* Pieces move through four dimensions: file, rank, **time** (one step = one full turn) and
  **timeline**.
  * Rook: any distance along exactly one dimension.
  * Bishop: the same distance along exactly two dimensions.
  * Queen: the same distance along any number of dimensions. King: one step that way.
  * Knight: two along one dimension and one along another.
  * Pawn: one rank forward, or one timeline toward the opponent (up the screen for White, down for
    Black); a double step from its starting rank; captures diagonally on the board or one timeline
    forward plus one turn back or ahead; en passant and promotion (to a queen) on the board.
* Moving to the **latest board of another timeline** continues that timeline. Moving to an
  **earlier board** creates a new timeline branching from it.
* A player may have at most **one more active timeline** than the opponent. Extra timelines are
  dimmed: they can still be played, but they don't hold back the present.
* **The present** is the earliest end of any active timeline (the highlighted column). On your
  turn you must move on every board in the present (thick gold outline). You may also move on any
  of your other latest boards (thin gold outline). Then tap **Submit**.
* You can't submit a turn that lets the opponent capture one of your kings (red arrows show the
  threats). If you have no legal turn, it's checkmate when a king is threatened and stalemate
  otherwise.
* Modes: two players on one device, or against a simple computer opponent. Undo is unlimited.

## Known simplifications

* Checkmate detection searches for a legal turn with a node budget. In very complex positions
  with many timelines the search can give up; the game then continues instead of declaring mate
  (players can resign).
* Castling only checks whether the king's squares are attacked on its own board, not by pieces
  elsewhere in the multiverse.
* Pawns always promote to a queen.
* The computer opponent is basic: it evaluates material one turn ahead.
* Pieces are drawn with the Unicode chess symbols from the system font.
