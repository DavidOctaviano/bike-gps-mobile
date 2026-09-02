import test from "node:test";
import assert from "node:assert/strict";
import { StravaOAuthBroker } from "../apps/api/src/oauth-broker.mjs";

function fixture() {
  let counter = 0;
  const requests = [];
  const fetch = async (url, options = {}) => {
    requests.push({ url, options });
    if (url.endsWith("/oauth/token")) {
      return Response.json({
        access_token: "unit-test-access-value",
        refresh_token: "unit-test-refresh-value",
        expires_at: 4_102_444_800,
        athlete: { id: 42, firstname: "Ada", lastname: "Bike" }
      });
    }
    if (url.includes("/athletes/42/routes")) return Response.json([]);
    throw new Error(`unexpected URL ${url}`);
  };
  const config = {
    clientId: "unit-test-client-id",
    clientSecret: "unit-test-server-secret",
    callbackUrl: "https://backend.test/oauth/strava/callback",
    appRedirectUrl: "bikegps://oauth/strava"
  };
  const options = { fetch, now: () => 1_700_000_000_000, randomToken: () => `random-${++counter}` };
  const broker = new StravaOAuthBroker(config, options);
  return { broker, requests, config, options };
}

test("OAuth exchanges on the backend and returns only an opaque app session", async () => {
  const { broker, requests } = fixture();
  const authorization = new URL(broker.begin("bikegps://oauth/strava"));
  assert.equal(authorization.hostname, "www.strava.com");
  assert.equal(authorization.searchParams.get("client_id"), "unit-test-client-id");
  assert.equal(authorization.searchParams.has("client_secret"), false);

  const appRedirect = new URL(await broker.callback({
    code: "one-time-code",
    state: authorization.searchParams.get("state"),
    scope: "read,read_all"
  }));
  const ticket = appRedirect.searchParams.get("ticket");
  const appResult = broker.exchangeTicket(ticket);
  assert.equal(typeof appResult.sessionToken, "string");
  assert.equal(appResult.athleteName, "Ada Bike");
  assert.equal(appResult.sessionToken.includes("unit-test-access-value"), false);
  assert.equal(Object.hasOwn(appResult, "access_token"), false);
  assert.equal(Object.hasOwn(appResult, "refresh_token"), false);

  const tokenBody = new URLSearchParams(requests[0].options.body);
  assert.equal(tokenBody.get("client_secret"), "unit-test-server-secret");
  assert.equal(tokenBody.get("code"), "one-time-code");
  assert.equal(requests[0].options.headers["Content-Type"], "application/x-www-form-urlencoded");
  assert.throws(() => broker.exchangeTicket(ticket), /OAUTH_TICKET_INVALID/);
});

test("OAuth rejects an app redirect outside the allowlist", () => {
  const { broker } = fixture();
  assert.throws(() => broker.begin("https://attacker.test/callback"), /APP_REDIRECT_NOT_ALLOWED/);
});

test("OAuth state is single-use", async () => {
  const { broker } = fixture();
  const state = new URL(broker.begin("bikegps://oauth/strava")).searchParams.get("state");
  await broker.callback({ code: "first", state, scope: "read,read_all" });
  await assert.rejects(() => broker.callback({ code: "replay", state, scope: "read,read_all" }), /OAUTH_STATE_INVALID/);
});

test("OAuth refuses route access when read_all was not granted", async () => {
  const { broker, requests } = fixture();
  const authorization = new URL(broker.begin("bikegps://oauth/strava"));
  const redirect = new URL(await broker.callback({
    code: "unused-code",
    state: authorization.searchParams.get("state"),
    scope: "read"
  }));
  assert.equal(redirect.searchParams.get("error"), "STRAVA_SCOPE_READ_ALL_REQUIRED");
  assert.equal(requests.length, 0);
});

test("encrypted session remains valid after a backend restart and rejects tampering", async () => {
  const { broker, config, options } = fixture();
  const authorization = new URL(broker.begin("bikegps://oauth/strava"));
  const redirect = new URL(await broker.callback({
    code: "one-time-code",
    state: authorization.searchParams.get("state"),
    scope: "read,read_all"
  }));
  const session = broker.exchangeTicket(redirect.searchParams.get("ticket")).sessionToken;

  const restarted = new StravaOAuthBroker(config, options);
  const result = await restarted.listRoutes(session);
  assert.deepEqual(result.routes, []);
  assert.equal(result.sessionToken, session);
  await assert.rejects(() => restarted.listRoutes(session.slice(0, -1) + "x"), /SESSION_INVALID/);
});
