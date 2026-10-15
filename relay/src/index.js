// Relay for online games of Divine Gambit.
//
// Each game is a room, kept by one Durable Object: who sits where, and the game as a list of
// events (the turns, and how the game ended). The relay doesn't know chess: both apps check every
// event and replay the list to the same position. A player sends the next event with its index;
// the room keeps it only if nothing else took that index first, stamps it with the sender's side
// (so neither app can speak for the other), and echoes it to both players.
//
// A game's code is also the secret that lets someone take the free seat, so it's long enough not
// to be guessed, and creating, joining and connecting are rate-limited per IP address.
//
// Each player gives a name for the game (1 to 20 characters), shown to the other player.
//
// HTTP:
//   POST /api/rooms           {variant, limit, side, name} -> {code, token, side, variant, limit}
//   GET  /api/rooms/CODE      -> {hostName, side, variant, limit, full}: the invitation, side being the host's
//   POST /api/rooms/CODE/join {name}                       -> the same as creating, for the other seat
//   GET  /api/rooms/CODE/socket (Authorization: Bearer TOKEN) -> WebSocket
//   GET  /join/CODE                                        -> invitation page (the shared link)
//   GET  /.well-known/assetlinks.json                      -> lets /join/ links open the app
//
// WebSocket, server to client:
//   {type: "hello", side, variant, limit, joined, opponent, names: {WHITE, BLACK}, events: [{side, event}, ...]}
//   {type: "event", index, side, event} an event was added (also the sender's own, as its receipt)
//   {type: "joined", name}              the second player took their seat
//   {type: "presence", opponent}        the opponent connected or went away
// client to server:
//   {type: "event", index, event}

import { DurableObject } from "cloudflare:workers";

const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
const CODE_LENGTH = 10;
const MAX_NAME_LENGTH = 20;
const MAX_EVENT_LENGTH = 8_000;
const MAX_EVENTS = 5_000;
/** A connection sending more than this many messages in [FLOOD_WINDOW_MS] is closed. */
const FLOOD_MESSAGES = 20;
const FLOOD_WINDOW_MS = 10_000;
/** A room is forgotten this long after anything last happened in it. */
const ROOM_LIFETIME_MS = 30 * 24 * 60 * 60 * 1000;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const path = url.pathname;
    let match;

    if (path === "/.well-known/assetlinks.json") {
      return Response.json([
        {
          relation: ["delegate_permission/common.handle_all_urls"],
          target: {
            namespace: "android_app",
            package_name: env.APP_PACKAGE,
            sha256_cert_fingerprints: env.APP_FINGERPRINTS.split(",").map((f) => f.trim()),
          },
        },
      ]);
    }

    const joinPage = path.match(/^\/join\/([A-Za-z0-9-]+)\/?$/);
    if ((path.startsWith("/api/") || joinPage) && !(await allowed(env, request, path))) {
      return error(429, "Too many requests, try again in a minute");
    }

    if (joinPage && request.method === "GET") {
      const code = joinPage[1].replaceAll("-", "").toUpperCase();
      const invitation = validCode(code) ? await room(env, code).invitation() : null;
      return invitationPage(url, code, invitation, env);
    }

    if (path === "/api/rooms" && request.method === "POST") {
      const body = await request.json().catch(() => null);
      const settings = validSettings(body);
      if (!settings) return error(400, "Bad game settings");
      for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        const ticket = await room(env, code).create(settings);
        if (ticket) return Response.json({ code, ...ticket });
      }
      return error(503, "No free game code, try again");
    }

    if ((match = path.match(/^\/api\/rooms\/([A-Z0-9]+)$/)) && request.method === "GET") {
      const code = match[1];
      const invitation = validCode(code) ? await room(env, code).invitation() : null;
      return invitation ? Response.json(invitation) : error(404, "No such game");
    }

    if ((match = path.match(/^\/api\/rooms\/([A-Z0-9]+)\/join$/)) && request.method === "POST") {
      const code = match[1];
      if (!validCode(code)) return error(404, "No such game");
      const name = validName((await request.json().catch(() => null))?.name);
      if (!name) return error(400, "Bad name");
      const result = await room(env, code).join(name);
      if (result.error) return error(result.error, result.message);
      return Response.json({ code, ...result });
    }

    if ((match = path.match(/^\/api\/rooms\/([A-Z0-9]+)\/socket$/))) {
      const code = match[1];
      if (!validCode(code)) return error(404, "No such game");
      if (request.headers.get("Upgrade") !== "websocket") return error(426, "Expected a WebSocket");
      return room(env, code).fetch(request);
    }

    if (path === "/") return new Response("Divine Gambit relay\n");
    return error(404, "Not found");
  },
};

export class Room extends DurableObject {
  /** Claims this room for a new game, or returns null if it's already taken. */
  async create(settings) {
    if (await this.ctx.storage.get("meta")) return null;
    const { name, ...game } = settings;
    const meta = { ...game, hostName: name, guestName: null, hostToken: crypto.randomUUID(), guestToken: null, count: 0 };
    await this.ctx.storage.put("meta", meta);
    await this.touch();
    return { token: meta.hostToken, side: meta.side, variant: meta.variant, limit: meta.limit };
  }

  /** What someone with the code is invited to, or null if there's no such game. */
  async invitation() {
    const meta = await this.ctx.storage.get("meta");
    if (!meta) return null;
    return { hostName: meta.hostName, side: meta.side, variant: meta.variant, limit: meta.limit, full: meta.guestToken !== null };
  }

  /** Gives the second seat to whoever asks first, as [name]. */
  async join(name) {
    const meta = await this.ctx.storage.get("meta");
    if (!meta) return { error: 404, message: "No such game" };
    if (meta.guestToken) return { error: 409, message: "This game already has two players" };
    meta.guestToken = crypto.randomUUID();
    meta.guestName = name;
    await this.ctx.storage.put("meta", meta);
    await this.touch();
    this.broadcast({ type: "joined", name });
    return { token: meta.guestToken, side: opposite(meta.side), variant: meta.variant, limit: meta.limit };
  }

  async fetch(request) {
    const meta = await this.ctx.storage.get("meta");
    if (!meta) return error(404, "No such game");
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer /, "");
    const seat = token && token === meta.hostToken ? "host" : token && token === meta.guestToken ? "guest" : null;
    if (!seat) return error(403, "Not a player in this game");

    // A player has one connection: a new one replaces any left over from before.
    for (const old of this.ctx.getWebSockets(seat)) {
      try {
        old.close(4000, "Replaced by a newer connection");
      } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    this.ctx.acceptWebSocket(server, [seat]);
    const other = seat === "host" ? "guest" : "host";
    server.send(
      JSON.stringify({
        type: "hello",
        side: seat === "host" ? meta.side : opposite(meta.side),
        variant: meta.variant,
        limit: meta.limit,
        joined: meta.guestToken !== null,
        opponent: this.ctx.getWebSockets(other).length > 0,
        names: { [meta.side]: meta.hostName, [opposite(meta.side)]: meta.guestName },
        events: await this.events(meta.count),
      }),
    );
    this.broadcast({ type: "presence", opponent: true }, other);
    await this.touch();
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    if (this.flooding(ws) || typeof data !== "string" || data.length > MAX_EVENT_LENGTH + 200) {
      try {
        ws.close(1008, "Too many or too large messages");
      } catch {}
      return;
    }
    let message;
    try {
      message = JSON.parse(data);
    } catch {
      return;
    }
    if (message.type !== "event") return;
    const { index, event } = message;
    if (typeof event !== "string" || event.length === 0 || event.length > MAX_EVENT_LENGTH) return;
    const meta = await this.ctx.storage.get("meta");
    // Someone else's event took this index first: the sender learns of it from that event's echo.
    if (!meta || index !== meta.count || meta.count >= MAX_EVENTS) return;
    const seat = this.ctx.getTags(ws)[0];
    const side = seat === "host" ? meta.side : opposite(meta.side);
    meta.count++;
    await this.ctx.storage.put({ [eventKey(index)]: { side, event }, meta });
    await this.touch();
    this.broadcast({ type: "event", index, side, event });
  }

  /** Counts a message from [ws]; true once it sends more than a player plausibly would. */
  flooding(ws) {
    this.rates ??= new Map();
    const now = Date.now();
    let rate = this.rates.get(ws);
    if (!rate || now - rate.start > FLOOD_WINDOW_MS) {
      rate = { start: now, count: 0 };
      this.rates.set(ws, rate);
    }
    return ++rate.count > FLOOD_MESSAGES;
  }

  async webSocketClose(ws, code) {
    this.rates?.delete(ws);
    const seat = this.ctx.getTags(ws)[0];
    const stillHere = this.ctx.getWebSockets(seat).some((other) => other !== ws);
    if (!stillHere) this.broadcast({ type: "presence", opponent: false }, seat === "host" ? "guest" : "host");
    try {
      ws.close(code === 1005 || code === 1006 ? 1000 : code, "Bye");
    } catch {}
  }

  async alarm() {
    for (const ws of this.ctx.getWebSockets()) {
      try {
        ws.close(4001, "The game has expired");
      } catch {}
    }
    await this.ctx.storage.deleteAll();
  }

  async events(count) {
    const stored = await this.ctx.storage.list({ prefix: "e:" });
    const events = [];
    for (let i = 0; i < count; i++) events.push(stored.get(eventKey(i)));
    return events;
  }

  broadcast(message, seat) {
    const text = JSON.stringify(message);
    for (const ws of seat ? this.ctx.getWebSockets(seat) : this.ctx.getWebSockets()) {
      try {
        ws.send(text);
      } catch {}
    }
  }

  /** Pushes back the day this room is forgotten. */
  async touch() {
    await this.ctx.storage.setAlarm(Date.now() + ROOM_LIFETIME_MS);
  }
}

/** Whether this IP address may make another request like this one, under the limits in wrangler.toml. */
async function allowed(env, request, path) {
  const limiter =
    path.endsWith("/socket") ? env.CONNECT_LIMIT : path === "/api/rooms" && request.method === "POST" ? env.CREATE_LIMIT : env.JOIN_LIMIT;
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const { success } = await limiter.limit({ key: ip });
  return success;
}

function room(env, code) {
  return env.ROOMS.get(env.ROOMS.idFromName(code));
}

function validSettings(body) {
  if (!body || typeof body !== "object") return null;
  const { variant, limit, side } = body;
  if (typeof variant !== "string" || !/^[A-Z_]{1,32}$/.test(variant)) return null;
  if (limit !== null && !(Number.isInteger(limit) && limit >= 1 && limit <= 99)) return null;
  if (side !== "WHITE" && side !== "BLACK") return null;
  const name = validName(body.name);
  if (!name) return null;
  return { variant, limit, side, name };
}

/** [name] trimmed, if it's 1 to 20 characters with no control or formatting characters; otherwise null. */
function validName(name) {
  if (typeof name !== "string") return null;
  const trimmed = name.trim();
  const length = [...trimmed].length;
  if (length < 1 || length > MAX_NAME_LENGTH || /[\p{Cc}\p{Cf}\p{Zl}\p{Zp}]/u.test(trimmed)) return null;
  return trimmed;
}

function escapeHtml(text) {
  return text.replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

function opposite(side) {
  return side === "WHITE" ? "BLACK" : "WHITE";
}

function eventKey(index) {
  return "e:" + String(index).padStart(6, "0");
}

function randomCode() {
  const bytes = crypto.getRandomValues(new Uint8Array(CODE_LENGTH));
  return Array.from(bytes, (b) => CODE_ALPHABET[b % CODE_ALPHABET.length]).join("");
}

function validCode(code) {
  return code.length === CODE_LENGTH && [...code].every((c) => CODE_ALPHABET.includes(c));
}

function error(status, message) {
  return Response.json({ error: message }, { status });
}

/**
 * The page a shared link shows when it doesn't open the app directly: the app isn't installed, or
 * the link was opened inside another app's browser.
 */
function invitationPage(url, code, invitation, env) {
  const fallback = encodeURIComponent(env.DOWNLOAD_URL);
  const intent =
    `intent://${url.host}/join/${code}#Intent;scheme=${url.protocol.replace(":", "")};` +
    `package=${env.APP_PACKAGE};S.browser_fallback_url=${fallback};end`;
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Divine Gambit invitation</title>
<style>
  :root { color-scheme: light; }
  body { margin: 0; min-height: 100vh; display: grid; place-items: center; background: #f3eee4;
         color: #2b2238; font: 17px/1.5 Georgia, "Times New Roman", serif; }
  main { max-width: 360px; padding: 32px 20px; text-align: center; }
  h1 { font-weight: normal; font-size: 32px; margin: 0; }
  .sub { letter-spacing: 0.5em; color: #6d4c9f; margin: 0 0 24px; }
  .code { font: 600 30px/1 ui-monospace, monospace; letter-spacing: 0.15em; margin: 8px 0 24px; }
  a.button { display: block; padding: 14px 20px; border-radius: 999px; text-decoration: none;
             margin: 10px 0; background: #6d4c9f; color: #fff; }
  a.secondary { background: #fffaf2; color: #2b2238; border: 1.5px solid #2b2238aa; }
  p.small { font-size: 14px; color: #5f566b; }
</style>
</head>
<body>
<main>
  <h1>Multiverse</h1>
  <p class="sub">CHESS</p>
  <p>${
    !invitation
      ? "There's no game with this code. It may have expired: ask for a new invitation."
      : invitation.full
        ? "This game already has two players."
        : `${escapeHtml(invitation.hostName)} invites you to a game of chess with time travel.`
  }</p>
  <div class="code">${code.slice(0, 5)}-${code.slice(5)}</div>
  <a class="button" href="${intent}">Open in the app</a>
  <a class="button secondary" href="${env.DOWNLOAD_URL}">Download the app</a>
  <p class="small">Or open Divine Gambit, choose <b>Join a friend</b> and enter the code.</p>
</main>
</body>
</html>`;
  return new Response(html, { headers: { "content-type": "text/html; charset=utf-8" } });
}
