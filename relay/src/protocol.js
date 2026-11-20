// Pure protocol validation, code generation and HTML rendering for the Divine Gambit relay.
// Kept free of Cloudflare runtime imports so automated Node tests can exercise every boundary directly.

export const CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
export const CODE_LENGTH = 10;
export const MAX_NAME_LENGTH = 20;
export const MAX_EVENT_LENGTH = 8_000;
export const MAX_EVENTS = 5_000;
export const MAX_HTTP_BODY_BYTES = 4_096;

/** Maximum byte value < 256 that is an exact multiple of CODE_ALPHABET.length (31 * 8 = 248). */
const UNBIASED_CEILING = Math.floor(256 / CODE_ALPHABET.length) * CODE_ALPHABET.length;

/** Generates a 10-character game code with zero modulo bias. */
export function randomCode() {
  const chars = [];
  const buf = new Uint8Array(CODE_LENGTH * 2);
  while (chars.length < CODE_LENGTH) {
    crypto.getRandomValues(buf);
    for (let i = 0; i < buf.length && chars.length < CODE_LENGTH; i++) {
      if (buf[i] < UNBIASED_CEILING) {
        chars.push(CODE_ALPHABET[buf[i] % CODE_ALPHABET.length]);
      }
    }
  }
  return chars.join("");
}

export function validCode(code) {
  return typeof code === "string" && code.length === CODE_LENGTH && [...code].every((c) => CODE_ALPHABET.includes(c));
}

export function validSettings(body) {
  if (!body || typeof body !== "object" || Array.isArray(body)) return null;
  const { variant, limit, side } = body;
  if (typeof variant !== "string" || !/^[A-Z_]{1,32}$/.test(variant)) return null;
  if (limit !== null && !(Number.isInteger(limit) && limit >= 1 && limit <= 99)) return null;
  if (side !== "WHITE" && side !== "BLACK") return null;
  const name = validName(body.name);
  if (!name) return null;
  // The Inferno rule (taken pieces can be won back); games from before it was offered are without.
  const inferno = body.inferno === true;
  return { variant, limit, side, name, inferno };
}

/** [name] trimmed, if it's 1 to 20 characters with no control or formatting characters (save ZWJ inside emojis); otherwise null. */
export function validName(name) {
  if (typeof name !== "string") return null;
  const trimmed = name.trim();
  const length = [...trimmed].length;
  if (
    length < 1 ||
    length > MAX_NAME_LENGTH ||
    trimmed.startsWith("\u200D") ||
    trimmed.endsWith("\u200D") ||
    trimmed.includes("\u200D\u200D") ||
    /[\p{Cc}\p{Zl}\p{Zp}]/u.test(trimmed) ||
    /[\p{Cf}]/u.test(trimmed.replaceAll("\u200D", ""))
  ) {
    return null;
  }
  return trimmed;
}

/** Validates an incoming client WebSocket event payload; returns {index, event} or null. */
export function validEventMessage(data) {
  if (typeof data !== "string" || data.length > MAX_EVENT_LENGTH + 200) return null;
  let message;
  try {
    message = JSON.parse(data);
  } catch {
    return null;
  }
  if (!message || typeof message !== "object" || Array.isArray(message)) return null;
  if (message.type !== "event") return null;
  const { index, event } = message;
  if (!Number.isInteger(index) || index < 0) return null;
  if (typeof event !== "string" || event.length === 0 || event.length > MAX_EVENT_LENGTH) return null;
  return { index, event };
}

export function escapeHtml(text) {
  return String(text).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

export function opposite(side) {
  return side === "WHITE" ? "BLACK" : "WHITE";
}

export function eventKey(index) {
  return "e:" + String(index).padStart(6, "0");
}

/** Reads and parses a bounded JSON body; returns {tooLarge: true} when exceeding MAX_HTTP_BODY_BYTES. */
export async function readBoundedJson(request) {
  const declared = Number(request.headers.get("content-length") || 0);
  if (declared > MAX_HTTP_BODY_BYTES) return { tooLarge: true, body: null };
  let text;
  try {
    text = await request.text();
  } catch {
    return { tooLarge: false, body: null };
  }
  if (text.length > MAX_HTTP_BODY_BYTES) return { tooLarge: true, body: null };
  try {
    return { tooLarge: false, body: JSON.parse(text) };
  } catch {
    return { tooLarge: false, body: null };
  }
}

/**
 * The page a shared link shows when it doesn't open the app directly: the app isn't installed, or
 * the link was opened inside another app's browser.
 */
export function invitationPage(url, code, invitation, env) {
  const downloadUrl = /^https:\/\//i.test(String(env.DOWNLOAD_URL || "")) ? String(env.DOWNLOAD_URL) : "https://github.com";
  const fallback = encodeURIComponent(downloadUrl);
  const scheme = url.protocol === "http:" ? "http" : "https";
  const pkg = String(env.APP_PACKAGE || "").replace(/[^A-Za-z0-9._]/g, "");
  const intent = `intent://${url.host}/join/${code}#Intent;scheme=${scheme};package=${pkg};S.browser_fallback_url=${fallback};end`;
  const html = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Divine Gambit invitation</title>
<style>
  :root { color-scheme: light; }
  body { margin: 0; min-height: 100vh; min-height: 100dvh; display: grid; place-items: center; background: #f3eee4;
         color: #2b2238; font: 17px/1.5 Georgia, "Times New Roman", serif; overflow-x: clip; }
  main { max-width: 360px; padding: 32px 20px; text-align: center; }
  h1 { font-weight: normal; font-size: 32px; margin: 0; }
  .sub { letter-spacing: 0.5em; color: #6d4c9f; margin: 0 0 24px; }
  .code { font: 600 30px/1 ui-monospace, monospace; font-variant-numeric: tabular-nums; letter-spacing: 0.15em; margin: 8px 0 24px; }
  a.button { display: block; padding: 14px 20px; border-radius: 999px; text-decoration: none;
             margin: 10px 0; background: #6d4c9f; color: #fff; }
  a.secondary { background: #fffaf2; color: #2b2238; border: 1.5px solid #2b2238aa; }
  p.small { font-size: 14px; color: #5f566b; }
</style>
</head>
<body>
<main>
  <h1>Divine</h1>
  <p class="sub">GAMBIT</p>
  <p>${
    !invitation
      ? "There's no game with this code. It may have expired: ask for a new invitation."
      : invitation.full
        ? "This game already has two players."
        : `${escapeHtml(invitation.hostName)} invites you to a game of chess with time travel.`
  }</p>
  <div class="code">${escapeHtml(code.slice(0, 5))}-${escapeHtml(code.slice(5))}</div>
  <a class="button" href="${escapeHtml(intent)}">Open in the app</a>
  <a class="button secondary" href="${escapeHtml(downloadUrl)}" rel="noopener noreferrer">Download the app</a>
  <p class="small">Or open Divine Gambit, choose <b>Join a friend</b> and enter the code.</p>
</main>
</body>
</html>`;
  return new Response(html, {
    headers: {
      "content-type": "text/html; charset=utf-8",
      "x-content-type-options": "nosniff",
      "x-frame-options": "DENY",
      // Do not leak the secret invitation code in the Referer header when opening Download.
      "referrer-policy": "no-referrer",
      "content-security-policy": "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'",
    },
  });
}
