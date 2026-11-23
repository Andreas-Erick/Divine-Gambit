# 2. The rules live in a plain JVM module

Date: 2026-11-23

## Context

A turn can be several moves on several boards, and whether it is legal depends on the order they
are played in (see *How the rules are checked* in the README). Checkmate, the computer, the puzzle
solver, the AI's turns and online games all depend on getting that right, and the bugs are in
positions nobody would set up by hand. The rules need tests that run often and cover a lot of
positions: `IncrementalCheckTest` alone compares two checks on over a hundred thousand positions.

## Options

- **Rules inside `:app`.** One module, simplest to start. Its unit tests run on the JVM too, but
  every change rebuilds the Android side, and nothing stops a rule from reaching for a `Context`, a
  file or Compose state.
- **Instrumented tests on a device.** Closest to the real app, and far too slow for fuzzing.
- **A separate `:engine` module with the Kotlin JVM plugin only.** Chosen.

## Decision

`engine/` applies only `kotlin.jvm` and depends on nothing but the Kotlin standard library (its
tests add JUnit). Everything with a rule in it is there: `GameState`, move generation, `TurnSearch`,
`ComputerPlayer`, `PuzzleSolver` and `PuzzleGenerator`, `GameSession` (undo, endings, what is kept),
`InfernoProgress`, `OnlineEvent` and `OnlineSync`, and the text the language models read and write
(`AiText`, `PuzzleDrafts`). Game values are immutable: a move returns a new `GameState`.

`:app` depends on `:engine`, never the other way round. It draws, keeps files, runs searches on
background threads and talks to the relay and to models.

## Consequences

- `./gradlew :engine:test` runs every rule test without an Android SDK.
- Decisions about what a game keeps or allows are tested in the engine, not left in the view
  model (see [ADR 1](0001-inferno-progress-belongs-to-the-device.md)).
- Immutable states make undo, replaying online events and searching several turns at once on
  different cores straightforward, at the cost of allocating a new state for every move tried.
  A move copies only the timeline it changes; the other timelines and boards are shared.
- What needs Android, such as `GameViewModel`, only coordinates the engine with files, threads
  and the network, and is not unit-tested.
