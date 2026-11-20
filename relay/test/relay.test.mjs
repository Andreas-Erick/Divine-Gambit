import assert from "node:assert/strict";
import test from "node:test";
import {
  CODE_ALPHABET,
  CODE_LENGTH,
  MAX_EVENT_LENGTH,
  MAX_HTTP_BODY_BYTES,
  escapeHtml,
  invitationPage,
  randomCode,
  readBoundedJson,
  validCode,
  validEventMessage,
  validName,
  validSettings,
} from "../src/protocol.js";

test("randomCode generates unbiased valid 10-char codes from CODE_ALPHABET", () => {
  const seen = new Set();
  for (let i = 0; i < 200; i++) {
    const code = randomCode();
    assert.equal(code.length, CODE_LENGTH);
    assert.ok(validCode(code), `invalid generated code: ${code}`);
    for (const ch of code) {
      assert.ok(CODE_ALPHABET.includes(ch));
    }
    seen.add(code);
  }
  assert.equal(seen.size, 200);
  assert.equal(validCode("01ILABCDEF"), false); // ambiguous chars excluded
  assert.equal(validCode("SHORT"), false);
  assert.equal(validCode(null), false);
});

test("validName trims whitespace, counts Unicode code points, and blocks control/format chars", () => {
  assert.equal(validName("  Maria  "), "Maria");
  assert.equal(validName("مرحبا"), "مرحبا");
  // Multi-codepoint family emoji (7 codepoints, 11 UTF-16 code units)
  assert.equal(validName("👨‍👩‍👧‍👦"), "👨‍👩‍👧‍👦");
  assert.equal(validName("A".repeat(20)), "A".repeat(20));

  assert.equal(validName(""), null);
  assert.equal(validName("   "), null);
  assert.equal(validName("A".repeat(21)), null);
  assert.equal(validName("null\0byte"), null);
  assert.equal(validName("line\nbreak"), null);
  assert.equal(validName("bidi\u202Eoverride"), null);
  assert.equal(validName("zwsp\u200Bhere"), null);
  assert.equal(validName(123), null);
});

test("validSettings enforces strict schema and resists prototype/type confusion", () => {
  assert.deepEqual(
    validSettings({ variant: "STANDARD", limit: 4, side: "WHITE", name: " Andreas ", inferno: true }),
    { variant: "STANDARD", limit: 4, side: "WHITE", name: "Andreas", inferno: true },
  );
  assert.deepEqual(
    validSettings({ variant: "ROYAL_QUEENS", limit: null, side: "BLACK", name: "Maria" }),
    { variant: "ROYAL_QUEENS", limit: null, side: "BLACK", name: "Maria", inferno: false },
  );

  for (const bad of [
    null,
    undefined,
    "string",
    [],
    [{ variant: "STANDARD", limit: 4, side: "WHITE", name: "A" }],
    { variant: "standard", limit: 4, side: "WHITE", name: "A" },
    { variant: "../etc/passwd", limit: 4, side: "WHITE", name: "A" },
    { variant: "STANDARD", limit: 0, side: "WHITE", name: "A" },
    { variant: "STANDARD", limit: 100, side: "WHITE", name: "A" },
    { variant: "STANDARD", limit: 3.5, side: "WHITE", name: "A" },
    { variant: "STANDARD", limit: NaN, side: "WHITE", name: "A" },
    { variant: "STANDARD", limit: 4, side: "GREEN", name: "A" },
    JSON.parse('{"__proto__":{"admin":true},"variant":"STANDARD","limit":4,"side":"WHITE","name":""}'),
  ]) {
    assert.equal(validSettings(bad), null);
  }
});

test("validEventMessage handles JSON null, primitives, arrays, negative indices, and oversized strings safely", () => {
  assert.deepEqual(
    validEventMessage(JSON.stringify({ type: "event", index: 0, event: "turn ongoing 0 0,0,4,1>0,0,4,3" })),
    { index: 0, event: "turn ongoing 0 0,0,4,1>0,0,4,3" },
  );

  for (const bad of [
    null,
    123,
    "",
    "not json",
    "null", // previously threw TypeError: Cannot read properties of null!
    "true",
    "42",
    "[]",
    JSON.stringify({ type: "other", index: 0, event: "x" }),
    JSON.stringify({ type: "event", index: -1, event: "x" }),
    JSON.stringify({ type: "event", index: 1.5, event: "x" }),
    JSON.stringify({ type: "event", index: NaN, event: "x" }),
    JSON.stringify({ type: "event", index: 0, event: "" }),
    JSON.stringify({ type: "event", index: 0, event: "x".repeat(MAX_EVENT_LENGTH + 1) }),
  ]) {
    assert.equal(validEventMessage(bad), null);
  }
});

test("readBoundedJson rejects oversized bodies before parsing JSON", async () => {
  const okReq = new Request("https://relay.example/api/rooms", {
    method: "POST",
    body: JSON.stringify({ name: "Alice" }),
  });
  const ok = await readBoundedJson(okReq);
  assert.equal(ok.tooLarge, false);
  assert.deepEqual(ok.body, { name: "Alice" });

  const hugeReq = new Request("https://relay.example/api/rooms", {
    method: "POST",
    body: JSON.stringify({ pad: "x".repeat(MAX_HTTP_BODY_BYTES + 500) }),
  });
  const huge = await readBoundedJson(hugeReq);
  assert.equal(huge.tooLarge, true);
  assert.equal(huge.body, null);
});

test("invitationPage escapes XSS vectors, blocks javascript: URLs, and sets strict security headers", async () => {
  const xssName = `<img src=x onerror="alert('xss')"> & 'quotes'`;
  const res = invitationPage(
    new URL("https://relay.example/join/23456789AB"),
    "23456789AB",
    { hostName: xssName, full: false },
    { APP_PACKAGE: "com.andreaserick.multiversechess", DOWNLOAD_URL: "javascript:alert(1)" },
  );

  assert.equal(res.headers.get("referrer-policy"), "no-referrer");
  assert.equal(res.headers.get("x-content-type-options"), "nosniff");
  assert.equal(res.headers.get("x-frame-options"), "DENY");
  assert.ok(res.headers.get("content-security-policy")?.includes("default-src 'none'"));

  const html = await res.text();
  assert.ok(!html.includes("<img src=x"));
  assert.ok(!html.includes("javascript:alert"));
  assert.ok(html.includes(escapeHtml(xssName)));
  assert.ok(html.includes("23456-789AB"));
});
