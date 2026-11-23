# 1. Inferno progress belongs to the device, not the game session

Date: 2026-11-23

## Context

Trials passed in the Inferno used to be a map in `GameViewModel`, kept in step with the saved game
by hand. Moving them into the engine raised the question of where they go: into each `Soul` in
`GameSession`, or beside the session.

## Decision

`InfernoProgress` is an engine value kept beside the `GameSession`, not inside it.

- **Undo must not take back a trial.** `GameSession` is an undo chain. If it held the trials, undoing
  a move would also take back a puzzle solved in the meantime.
- **Online, trials are private.** The friend's device never sees them, and `applyEvent` rebuilds
  the session from the start of the turn. Trials kept in the session would be lost each time.

`InfernoProgress.follow(previous, next)` keeps it in step instead. It drops the trials of a soul
that undo took back out of the Inferno, and gives a new allowance of time each turn. On the device,
it is saved in the same file as the game (`SavedGame.encode(game, trials)`). Online, it is saved
with the seat.

## Not done: a storage interface

The architecture review of the same date also proposed a `ProgressStore` interface over the save
files, with a file version and an in-memory version. Its only users would be tests of
`GameViewModel`, and that needs Android to run. The rules about what gets kept now live in the
engine (`GameMode.savedOnDevice`, `GameSession.isResumable`, `GameSession.solvesPuzzle`) and are
tested there. Add the interface only when something besides the files needs it.
