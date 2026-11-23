# 4. Language models choose from the engine's turns

Date: 2026-11-23

## Context

The AI opponent can be Gemini, a model on the player's computer, or a small one on the phone.
None of them reliably writes legal moves in a game with time travel and parallel timelines, and
the small ones barely follow a format. A model's turn or puzzle that breaks the rules would break
the game.

## Options

- **Let the model write its moves**, and check them. Every mistake needs a retry; a weak model
  might never produce a legal turn.
- **Let the model pick from every legal turn.** Legal, but there can be thousands, far too many
  for a prompt, and most are pointless.
- **Let the model pick from the engine's best few.** Chosen.

## Decision

For a turn, `ComputerPlayer.shortlist` ranks up to ten legal turns, `AiText.choosePrompt` lists them
with a description of the boards, and the model answers with a number and a sentence
(`AiText.parseChoice`). A remark that names a move it didn't play is left out (`remarkFits`). If the
model fails, answers badly or takes too long, the engine plays its favourite turn and says so.

For a puzzle, the model writes pieces, a short history and a solution as text. `PuzzleDrafts`
replays the history, solves the position, and tells the model exactly what's wrong for up to four
attempts. If none works out, the engine generates the puzzle and the model only names it.

Each kind of model is a `LanguageModel`, and `Opponent` decides who plays the computer's side. A
new provider is a new `LanguageModel` and its settings, with no change to the game.

## Consequences

- An AI can never make an illegal move or keep an unsolvable puzzle.
- How well it plays is capped by the engine's shortlist, and a small model chooses close to
  randomly among good turns. That suits a sparring partner, which is all this aims for.
- Prompts and parsing are plain text functions in the engine, so they are unit-tested
  (`AiTextTest`) without a model.
