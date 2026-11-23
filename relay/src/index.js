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
//   POST /api/rooms           {variant, limit, side, name, inferno} -> {code, token, side, variant, limit, inferno}
//   GET  /api/rooms/CODE      -> {hostName, side, variant, limit, inferno, full}: the invitation, side being the host's
//   POST /api/rooms/CODE/join {name}                       -> the same as creating, for the other seat
//   GET  /api/rooms/CODE/socket (Authorization: Bearer TOKEN) -> WebSocket
//   GET  /join/CODE                                        -> invitation page (the shared link)
//   GET  /.well-known/assetlinks.json                      -> lets /join/ links open the app
//
// WebSocket, server to client:
//   {type: "hello", side, variant, limit, inferno, joined, opponent, names: {WHITE, BLACK}, events: [{side, event}, ...]}
//   {type: "event", index, side, event} an event was added (also the sender's own, as its receipt)
//   {type: "joined", name}              the second player took their seat
//   {type: "presence", opponent}        the opponent connected or went away
// client to server:
//   {type: "event", index, event}

import { DurableObject } from "cloudflare:workers";
import {
  MAX_EVENTS,
  MAX_EVENT_LENGTH,
  eventKey,
  invitationPage,
  opposite,
  randomCode,
  readBoundedJson,
  validCode,
  validEventMessage,
  validName,
  validSettings,
} from "./protocol.js";

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
            sha256_cert_fingerprints: String(env.APP_FINGERPRINTS || "")
              .split(",")
              .map((f) => f.trim())
              .filter(Boolean),
          },
        },
      ]);
    }

    const joinPage = path.match(/^\/join\/([A-Za-z0-9-]+)\/?$/);
    if ((path.startsWith("/api/") || joinPage) && !(await allowed(env, request, path))) {
      return error(429, "Too many requests, try again in a minute", { "Retry-After": "60" });
    }

    if (joinPage && request.method === "GET") {
      const code = joinPage[1].replaceAll("-", "").toUpperCase();
      const invitation = validCode(code) ? await room(env, code).invitation() : null;
      return invitationPage(url, code, invitation, env);
    }

    if (path === "/api/rooms" && request.method === "POST") {
      const { tooLarge, body } = await readBoundedJson(request);
      if (tooLarge) return error(413, "Request too large");
      const settings = validSettings(body);
      if (!settings) return error(400, "Bad game settings");
      for (let attempt = 0; attempt < 5; attempt++) {
        const code = randomCode();
        const ticket = await room(env, code).create(settings);
        if (ticket) return Response.json({ code, ...ticket });
      }
      return error(503, "No free game code, try again", { "Retry-After": "1" });
    }

    if ((match = path.match(/^\/api\/rooms\/([A-Z0-9]+)$/)) && request.method === "GET") {
      const code = match[1];
      const invitation = validCode(code) ? await room(env, code).invitation() : null;
      return invitation ? Response.json(invitation) : error(404, "No such game");
    }

    if ((match = path.match(/^\/api\/rooms\/([A-Z0-9]+)\/join$/)) && request.method === "POST") {
      const code = match[1];
      if (!validCode(code)) return error(404, "No such game");
      const { tooLarge, body } = await readBoundedJson(request);
      if (tooLarge) return error(413, "Request too large");
      const name = validName(body?.name);
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

    if (path === "/") return new Response("Divine Gambit relay\n", { headers: { "x-content-type-options": "nosniff" } });
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
    return { token: meta.hostToken, side: meta.side, variant: meta.variant, limit: meta.limit, inferno: meta.inferno === true };
  }

  /** What someone with the code is invited to, or null if there's no such game. */
  async invitation() {
    const meta = await this.ctx.storage.get("meta");
    if (!meta) return null;
    return {
      hostName: meta.hostName,
      side: meta.side,
      variant: meta.variant,
      limit: meta.limit,
      inferno: meta.inferno === true,
      full: meta.guestToken !== null,
    };
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
    return { token: meta.guestToken, side: opposite(meta.side), variant: meta.variant, limit: meta.limit, inferno: meta.inferno === true };
  }

  async fetch(request) {
    const meta = await this.ctx.storage.get("meta");
    if (!meta) return error(404, "No such game");
    const token = (request.headers.get("Authorization") || "").replace(/^Bearer /, "");
    const seat = token && token === meta.hostToken ? "host" : token && token === meta.guestToken ? "guest" : null;
    if (!seat) return error(403, "Not a player in this game");

    // A player has one connection: a new one replaces any left over from before.
    for (const old of this.ctx.getWebSockets(seat)) {
      closeQuietly(old, 4000, "Replaced by a newer connection");
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
        inferno: meta.inferno === true,
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
      closeQuietly(ws, 1008, "Too many or too large messages");
      return;
    }
    const parsed = validEventMessage(data);
    if (!parsed) return;
    const { index, event } = parsed;
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
    closeQuietly(ws, code === 1005 || code === 1006 ? 1000 : code, "Bye");
  }

  async alarm() {
    for (const ws of this.ctx.getWebSockets()) {
      closeQuietly(ws, 4001, "The game has expired");
    }
    await this.ctx.storage.deleteAll();
  }

  async events(count) {
    const stored = await this.ctx.storage.list({ prefix: "e:" });
    const events = [];
    for (let i = 0; i < count; i++) {
      const entry = stored.get(eventKey(i));
      if (entry) events.push(entry);
    }
    return events;
  }

  broadcast(message, seat) {
    const text = JSON.stringify(message);
    for (const ws of seat ? this.ctx.getWebSockets(seat) : this.ctx.getWebSockets()) {
      try {
        ws.send(text);
      } catch {
        // The socket is already closing; its player catches up from the stored events on reconnect.
      }
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
  if (!limiter) return true;
  const ip = request.headers.get("CF-Connecting-IP") || "unknown";
  const { success } = await limiter.limit({ key: ip });
  return success;
}

function room(env, code) {
  return env.ROOMS.get(env.ROOMS.idFromName(code));
}

function error(status, message, extraHeaders = {}) {
  return Response.json({ error: message }, { status, headers: { "x-content-type-options": "nosniff", ...extraHeaders } });
}

/**
 * Closes [ws], which may already be closed or closing: then close() throws, and there is nothing
 * left to do, since every event is stored and replayed to a player when they reconnect.
 */
function closeQuietly(ws, code, reason) {
  try {
    ws.close(code, reason);
  } catch {
    // Already closed.
  }
}
