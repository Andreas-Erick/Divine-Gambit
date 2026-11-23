# Glossary

The game's own words, as the code uses them.

- **Multiverse**: every timeline of boards in a game. **Present**: the earliest timeline end a player must move on.
- **Game kind** (`GameKind`): who plays the other side: pass and play, the computer, an AI, a friend online, or a puzzle.
- **Local side** (`GameMode.localSide`): the side played on this device for the whole game; none in pass and play or a puzzle.
- **Player side** (`GameSession.playerSide`): the side whose player is at the device now: the local side, or in pass and play, the side to move.
- **Player's turn / opponent's turn**: whether someone at this device is to move, or the computer or the friend online is.
- **Opponent** (`Opponent`): who plays the computer's side: the engine, or a language model choosing among the engine's best turns.
- **Inferno**: a rule where taken pieces go to the Inferno, and their owner can win them back.
- **Soul**: a piece taken in a game with the Inferno. It is **freed** when it returns to the board.
- **Trial**: a puzzle solved in the Inferno to win back a soul; more valuable pieces need more.
- **Inferno progress** (`InfernoProgress`): this device's trials passed for each soul, and the time left in the Inferno this turn. It belongs to the device, not the game (see [ADR 0001](docs/adr/0001-inferno-progress-belongs-to-the-device.md)).
- **Waiting soul** (`WaitingSoul`): a soul of the player side still in the Inferno, with its trials passed.
- **Online event** (`OnlineEvent`): one step of an online game: a turn, an ending, or a soul freed. The relay keeps the game's events in order.
- **Online sync** (`OnlineSync`): where this device stands in the relay's events: how many it has played, and the one it sent that hasn't come back yet.
- **Relay**: the Cloudflare Worker online games meet through (`relay/`).
