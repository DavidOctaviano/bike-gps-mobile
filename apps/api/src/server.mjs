import { createServer } from "node:http";
import { resolve } from "node:path";
import { pathToFileURL } from "node:url";
import { OAuthError, StravaOAuthBroker } from "./oauth-broker.mjs";

export function createBikeGpsServer(config = process.env, options = {}) {
  const broker = new StravaOAuthBroker({
    clientId: config.STRAVA_CLIENT_ID,
    clientSecret: config.STRAVA_CLIENT_SECRET,
    callbackUrl: config.STRAVA_CALLBACK_URL,
    appRedirectUrl: config.APP_REDIRECT_URL
  }, options);

  return createServer(async (request, response) => {
    try {
      const url = new URL(request.url, "http://localhost");
      if (request.method === "GET" && url.pathname === "/health") return json(response, 200, { ok: true });
      if (request.method === "GET" && url.pathname === "/oauth/strava/start") {
        return redirect(response, broker.begin(url.searchParams.get("app_redirect_uri")));
      }
      if (request.method === "GET" && url.pathname === "/oauth/strava/callback") {
        const target = await broker.callback({
          code: url.searchParams.get("code"),
          state: url.searchParams.get("state"),
          error: url.searchParams.get("error")
        });
        return redirect(response, target);
      }
      if (request.method === "POST" && url.pathname === "/oauth/ticket") {
        const body = await readJson(request);
        return json(response, 200, broker.exchangeTicket(body.ticket));
      }
      if (request.method === "GET" && url.pathname === "/strava/routes") {
        const routes = await broker.listRoutes(bearer(request), positivePage(url.searchParams.get("page")));
        return json(response, 200, { routes });
      }
      const exportMatch = request.method === "GET" && url.pathname.match(/^\/strava\/routes\/(\d+)\/gpx$/);
      if (exportMatch) {
        const bytes = await broker.exportGpx(bearer(request), exportMatch[1]);
        response.writeHead(200, {
          "Content-Type": "application/gpx+xml",
          "Content-Disposition": `attachment; filename=route-${exportMatch[1]}.gpx`,
          "Content-Length": bytes.length,
          "Cache-Control": "no-store"
        });
        return response.end(bytes);
      }
      return json(response, 404, { error: "NOT_FOUND" });
    } catch (failure) {
      const known = failure instanceof OAuthError;
      return json(response, known ? failure.status : 500, { error: known ? failure.code : "INTERNAL_ERROR" });
    }
  });
}

function bearer(request) {
  const value = request.headers.authorization ?? "";
  if (!value.startsWith("Bearer ") || value.length <= 7) throw new OAuthError("SESSION_MISSING", 401);
  return value.slice(7);
}

function positivePage(raw) {
  const page = Number.parseInt(raw ?? "1", 10);
  return Number.isSafeInteger(page) && page > 0 ? page : 1;
}

async function readJson(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 16_384) throw new OAuthError("BODY_TOO_LARGE", 413);
    chunks.push(chunk);
  }
  try { return JSON.parse(Buffer.concat(chunks).toString("utf8")); }
  catch { throw new OAuthError("JSON_INVALID"); }
}

function json(response, status, body) {
  const bytes = Buffer.from(JSON.stringify(body));
  response.writeHead(status, {
    "Content-Type": "application/json; charset=utf-8",
    "Content-Length": bytes.length,
    "Cache-Control": "no-store",
    "X-Content-Type-Options": "nosniff"
  });
  response.end(bytes);
}

function redirect(response, target) {
  response.writeHead(302, { Location: target, "Cache-Control": "no-store" });
  response.end();
}

if (process.argv[1] && import.meta.url === pathToFileURL(resolve(process.argv[1])).href) {
  const port = Number.parseInt(process.env.PORT ?? "8080", 10);
  createBikeGpsServer().listen(port, "0.0.0.0", () => {
    process.stdout.write(`Bike GPS API listening on ${port}\n`);
  });
}
