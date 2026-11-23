# 3. The relay orders events; the phones check them

Date: 2026-11-23

## Context

Two phones playing online need a place to meet, a shared order of turns, and a way to pick a game
up after either app was closed. There are no accounts, and the project has no budget beyond
Cloudflare's free plan. Neither phone can trust the other: a modified app could send a move its
player isn't allowed, play for the other side, or claim a checkmate that isn't one.

## Options

- **Peer to peer.** No server, but phones behind mobile networks rarely reach each other, and
  nothing would keep a game while one phone is off.
- **A server that knows the rules.** It could reject bad moves itself, but the rules would exist
  twice, in Kotlin and in JavaScript, and would have to agree on every position, including the
  ones that take seconds of search to decide.
- **A relay that keeps events in order and stamps who sent them.** Chosen.

## Decision

`relay/` is a Cloudflare Worker with one Durable Object per game. It stores each game's events with
the sender's side, accepts an event only at the next free index, and sends every event to both
players, the sender included. It knows nothing about chess.

Each phone plays every event in the relay's order, its own turns too once they come back
(`OnlineSync`). `GameSession.applyEvent` refuses a move the sender's side can't make, a
resignation for the other side, or an ending that isn't the sender's to claim. For a claimed
checkmate or stalemate, `confirmsOutcome` searches the position again before accepting it.

## Consequences

- One copy of the rules. Both phones replay the same list, so they always agree.
- The relay stays small (`relay/src/index.js`). Where it does check input, game codes and names,
  shared test vectors (`relay/test/vectors.json`) keep it in step with the app: the relay's tests
  and the app's `SharedRelayVectorsTest` both read them.
- The relay can't stop a bad event from being stored; the phones refuse to play it instead. A
  cheating app can spoil a game, but not win it.
- A claimed ending whose search runs out of time is given the benefit of the doubt, since the
  claimant may have searched for longer.
- Whoever controls the Cloudflare account controls every game (see `relay/README.md`).
