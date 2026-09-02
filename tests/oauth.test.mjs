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
    throw new Error(`unexpected URL ${url}`);
  };
  const broker = new StravaOAuthBroker({
    clientId: "unit-test-client-id",
    clientSecret: "unit-test-server-secret",
    callbackUrl: "https://backend.test/oauth/strava/callback",
    appRedirectUrl: "bikegps://oauth/strava"
  }, { fetch, now: () => 1_700_000_000_000, randomToken: () => `random-${++counter}` });
  return { broker, requests };
}

test("OAuth exchanges on the backend and returns only an opaque app session", async () => {
  const { broker, requests } = fixture();
  const authorization = new URL(broker.begin("bikegps://oauth/strava"));
  assert.equal(authorization.hostname, "www.strava.com");
  assert.equal(authorization.searchParams.get("client_id"), "unit-test-client-id");
  assert.equal(authorization.searchParams.has("client_secret"), false);

  const appRedirect = new URL(await broker.callback({
    code: "one-time-code",
    state: authorization.searchParams.get("state")
  }));
  const ticket = appRedirect.searchParams.get("ticket");
  const appResult = broker.exchangeTicket(ticket);
  assert.deepEqual(appResult, { sessionToken: "random-3", athleteName: "Ada Bike" });
  assert.equal(Object.hasOwn(appResult, "access_token"), false);
  assert.equal(Object.hasOwn(appResult, "refresh_token"), false);

  const tokenBody = JSON.parse(requests[0].options.body);
  assert.equal(tokenBody.client_secret, "unit-test-server-secret");
  assert.equal(tokenBody.code, "one-time-code");
  assert.throws(() => broker.exchangeTicket(ticket), /OAUTH_TICKET_INVALID/);
});

test("OAuth rejects an app redirect outside the allowlist", () => {
  const { broker } = fixture();
  assert.throws(() => broker.begin("https://attacker.test/callback"), /APP_REDIRECT_NOT_ALLOWED/);
});

test("OAuth state is single-use", async () => {
  const { broker } = fixture();
  const state = new URL(broker.begin("bikegps://oauth/strava")).searchParams.get("state");
  await broker.callback({ code: "first", state });
  await assert.rejects(() => broker.callback({ code: "replay", state }), /OAUTH_STATE_INVALID/);
});
