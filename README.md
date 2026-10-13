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
| `engine/` | Game rules: multiverse state, move generation, check/checkmate search, computer player, variants, puzzles, session/undo logic. Unit-tested. |
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
    Black); an unmoved pawn may take a double step either way; captures diagonally on the board or
    one timeline forward plus one turn back or ahead; en passant and promotion (to a queen) on the
    board.
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

## Variants

Pick a variant in the menu before starting a game. Fairy pieces are drawn as discs with their
5D chess notation letter.

| Variant | Setup |
| --- | --- |
| Standard | The usual pieces. |
| Princesses | Queens become princesses (**S**): any distance along one or two dimensions. |
| Unicorns | Bishops become unicorns (**U**): equal distance along exactly three dimensions. |
| Brawns | Pawns become brawns (**W**): pawns that can also capture by combining one step forward (rank or timeline) with one step along any other dimension. |
| Menagerie | Unicorn, princess, dragon (**D**, equal distance along all four dimensions) and brawns. |

Unicorns and dragons can never move on their own board, so they wake up once there is history to
travel through.

## Puzzles

**Puzzles** in the menu opens six "White to checkmate in one turn" puzzles that teach the core
ideas: attacking a king's past, which it can't step away from, with a rook, bishop and knight;
protecting the attacker; one move that plays two timelines; and a unicorn. The positions are
original. They're built by replaying a short history so every board in them is consistent.
Each has a hint and a reveal-solution option, a wrong answer is taken back with an example of how
Black escapes, and solved puzzles are remembered on the device.

The puzzles are defined in `engine/.../Puzzles.kt`. `PuzzlesTest` checks that each stated solution
is mate and that the solver (`PuzzleSolver`) finds at most two mating turns, so new puzzles can be
added safely.

## Known simplifications

* Checkmate detection searches for a legal turn with a node budget. In very complex positions
  with many timelines the search can give up; the game then continues instead of declaring mate
  (players can resign).
* Castling only checks whether the king's squares are attacked on its own board, not by pieces
  elsewhere in the multiverse.
* Pawns always promote to a queen.
* The computer opponent is basic: it evaluates material one turn ahead.
* Pieces are drawn with the Unicode chess symbols from the system font.
