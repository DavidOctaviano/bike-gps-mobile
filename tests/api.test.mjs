import test from "node:test";
import assert from "node:assert/strict";
import { once } from "node:events";
import { createBikeGpsServer } from "../apps/api/src/server.mjs";

test("HTTP API completes OAuth, lists routes and exports GPX", async t => {
  const upstream = [];
  const fakeFetch = async (url, options = {}) => {
    upstream.push({ url, options });
    if (url.endsWith("/oauth/token")) {
      return Response.json({
        access_token: "test-access",
        refresh_token: "test-refresh",
        expires_at: 4_102_444_800,
        athlete: { id: 42, firstname: "Ada", lastname: "Bike" }
      });
    }
    if (url.includes("/athletes/42/routes")) {
      return Response.json([{
        id_str: "9001",
        name: "Volta da Lagoa",
        distance: 8400,
        elevation_gain: 70,
        estimated_moving_time: 1800,
        map: { summary_polyline: "_p~iF~ps|U_ulLnnqC_mqNvxq`@" },
        private: true,
        updated_at: "2026-09-02T12:00:00Z"
      }]);
    }
    if (url.endsWith("/routes/9001/export_gpx")) {
      return new Response("<gpx><rte><rtept lat=\"-20.0\" lon=\"-45.5\"/></rte></gpx>", {
        headers: { "Content-Type": "application/gpx+xml" }
      });
    }
    throw new Error(`unexpected upstream ${url}`);
  };
  let token = 0;
  const server = createBikeGpsServer({
    STRAVA_CLIENT_ID: "test-client",
    STRAVA_CLIENT_SECRET: "server-only-secret",
    STRAVA_CALLBACK_URL: "https://api.example/oauth/strava/callback",
    APP_REDIRECT_URL: "bikegps://oauth/strava"
  }, { fetch: fakeFetch, randomToken: () => `token-${++token}` });
  server.listen(0, "127.0.0.1");
  await once(server, "listening");
  t.after(() => server.close());
  const base = `http://127.0.0.1:${server.address().port}`;

  const start = await fetch(`${base}/oauth/strava/start?app_redirect_uri=${encodeURIComponent("bikegps://oauth/strava")}`, {
    redirect: "manual"
  });
  assert.equal(start.status, 302);
  const authorization = new URL(start.headers.get("location"));
  assert.equal(authorization.hostname, "www.strava.com");
  assert.equal(authorization.searchParams.has("client_secret"), false);

  const callback = await fetch(`${base}/oauth/strava/callback?code=one-use&scope=read,read_all&state=${authorization.searchParams.get("state")}`, {
    redirect: "manual"
  });
  const appRedirect = new URL(callback.headers.get("location"));
  assert.equal(appRedirect.protocol, "bikegps:");

  const ticket = await fetch(`${base}/oauth/ticket`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ ticket: appRedirect.searchParams.get("ticket") })
  });
  const session = await ticket.json();
  assert.equal(session.athleteName, "Ada Bike");
  assert.equal(Object.hasOwn(session, "access_token"), false);

  const routesResponse = await fetch(`${base}/strava/routes`, {
    headers: { Authorization: `Bearer ${session.sessionToken}` }
  });
  const routes = await routesResponse.json();
  assert.equal(routes.routes[0].id, "9001");
  assert.equal(routes.routes[0].name, "Volta da Lagoa");
  assert.equal(typeof routes.sessionToken, "string");

  const gpx = await fetch(`${base}/strava/routes/9001/gpx`, {
    headers: { Authorization: `Bearer ${session.sessionToken}` }
  });
  assert.equal(gpx.status, 200);
  assert.equal(typeof gpx.headers.get("x-bikegps-session"), "string");
  assert.match(await gpx.text(), /<gpx>/);
  assert.equal(upstream.some(item => item.options.headers?.Authorization === "Bearer test-access"), true);
});
