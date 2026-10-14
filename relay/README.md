# Relay for online games

Two phones playing online meet here. The relay is a Cloudflare Worker with one Durable Object per
game. Each game keeps its turns in order and passes them between the players over WebSockets. It
doesn't know chess: both apps check every turn and replay the same list, so they always agree. A
game is forgotten 30 days after anything last happened in it.

It also serves the invitation links, `https://<relay>/join/<code>`. With the app installed, Android
opens them in the app, because `/.well-known/assetlinks.json` names the app and its signing
certificates (`APP_FINGERPRINTS` in `wrangler.toml`). Without the app, the link shows a page with
the code and a download link.

The free Workers plan is plenty for this.

## Security

* **Who may send what.** The relay stamps each event with the sender's side, so one player's app
  can't play the other's turn or resign for them. The apps check every event against the rules,
  and search for themselves before accepting a checkmate or stalemate the other app claims.
* **Names.** Each player gives a name for the game, 1 to 20 characters without control
  characters, kept with the game and deleted along with it. The invitation page escapes it.
* **Seats.** A game's 10-character code is the secret that takes the free seat, so only send the
  link to the friend you mean to play: whoever opens it first gets the seat. Each player then
  connects with their own token, sent in an `Authorization` header (not in the address, which can
  end up in logs).
* **Abuse.** Creating and joining games are limited to 10 a minute per IP address, connecting to
  30 (the `[[ratelimits]]` in `wrangler.toml`); a connection sending more than 20 messages in 10
  seconds, or an oversized one, is closed. On the free plan, hitting Cloudflare's daily limits
  only makes requests fail until the next day; it can't run up a bill.
* **Your account.** Whoever controls the Cloudflare account controls every game: turn on
  two-factor authentication, and `npx wrangler logout` when you're not deploying. Keep the app's
  signing keystore private and backed up, since `assetlinks.json` trusts its certificate.

## Deploy

```
cd relay
npm install
npx wrangler login
npx wrangler deploy
```

Wrangler prints the address, e.g. `https://multiverse-chess.<your-subdomain>.workers.dev`. Put it
in `gradle.properties` at the repository root:

```
relay.url=https://multiverse-chess.<your-subdomain>.workers.dev
```

and build the app again. To check that Android trusts the links, install the app and run
`adb shell pm get-app-links com.andreaserick.multiversechess`; the relay's host should show as
`verified`.

If you sign the app with another certificate, add its SHA-256 fingerprint to `APP_FINGERPRINTS`
(`keytool -list -v -keystore <keystore>` shows it) and deploy again.

## Develop

`npm run dev` runs the relay on this computer, on port 8787. Point debug builds at it in
`local.properties`:

```
relay.debugUrl=http://localhost:8787
```

and forward the port to the device or emulator with `adb reverse tcp:8787 tcp:8787`. (Recent
Android versions don't let apps reach the computer's LAN address, such as `10.0.2.2`, without a
permission; `localhost` through `adb reverse` works.)
