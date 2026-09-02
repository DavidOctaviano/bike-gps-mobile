import { createCipheriv, createDecipheriv, createHash, randomBytes } from "node:crypto";

const STATE_TTL_MS = 10 * 60_000;
const TICKET_TTL_MS = 60_000;
const SESSION_TTL_MS = 30 * 24 * 60 * 60_000;

export class OAuthError extends Error {
  constructor(code, status = 400) {
    super(code);
    this.code = code;
    this.status = status;
  }
}

/** Keeps Strava credentials server-side and gives the app only an opaque session. */
export class StravaOAuthBroker {
  constructor(config, options = {}) {
    this.clientId = required(config.clientId, "STRAVA_CLIENT_ID");
    this.clientSecret = required(config.clientSecret, "STRAVA_CLIENT_SECRET");
    this.callbackUrl = required(config.callbackUrl, "STRAVA_CALLBACK_URL");
    this.appRedirectUrl = required(config.appRedirectUrl, "APP_REDIRECT_URL");
    this.fetch = options.fetch ?? globalThis.fetch;
    this.now = options.now ?? Date.now;
    this.randomToken = options.randomToken ?? (() => randomBytes(32).toString("base64url"));
    this.sessionKey = createHash("sha256")
      .update("bikegps-session-v1\0")
      .update(this.clientSecret)
      .digest();
    this.states = new Map();
    this.tickets = new Map();
  }

  begin(appRedirectUrl) {
    this.cleanup();
    if (appRedirectUrl !== this.appRedirectUrl) throw new OAuthError("APP_REDIRECT_NOT_ALLOWED");
    const state = this.randomToken();
    this.states.set(state, { appRedirectUrl, expiresAt: this.now() + STATE_TTL_MS });
    const query = new URLSearchParams({
      client_id: this.clientId,
      redirect_uri: this.callbackUrl,
      response_type: "code",
      approval_prompt: "auto",
      scope: "read,read_all",
      state
    });
    return `https://www.strava.com/oauth/authorize?${query}`;
  }

  async callback({ code, state, error, scope }) {
    this.cleanup();
    const pending = this.states.get(state);
    this.states.delete(state);
    if (!pending || pending.expiresAt <= this.now()) throw new OAuthError("OAUTH_STATE_INVALID");
    if (error) return appendQuery(pending.appRedirectUrl, { error });
    if (!code) throw new OAuthError("OAUTH_CODE_MISSING");
    const acceptedScopes = new Set((scope ?? "").split(/[ ,]+/).filter(Boolean));
    if (!acceptedScopes.has("read_all")) {
      return appendQuery(pending.appRedirectUrl, { error: "STRAVA_SCOPE_READ_ALL_REQUIRED" });
    }
    const tokens = await this.tokenRequest({ code, grant_type: "authorization_code" });
    const ticket = this.randomToken();
    this.tickets.set(ticket, { tokens, expiresAt: this.now() + TICKET_TTL_MS });
    return appendQuery(pending.appRedirectUrl, { ticket });
  }

  exchangeTicket(ticket) {
    this.cleanup();
    const value = this.tickets.get(ticket);
    this.tickets.delete(ticket);
    if (!value || value.expiresAt <= this.now()) throw new OAuthError("OAUTH_TICKET_INVALID", 401);
    const sessionToken = this.sealSession(value.tokens);
    const athlete = value.tokens.athlete ?? {};
    return {
      sessionToken,
      athleteName: [athlete.firstname, athlete.lastname].filter(Boolean).join(" ") || "Atleta Strava"
    };
  }

  async listRoutes(sessionToken, page = 1) {
    const session = await this.activeSession(sessionToken);
    const athleteId = session.tokens.athlete?.id;
    if (!athleteId) throw new OAuthError("STRAVA_ATHLETE_MISSING", 502);
    const response = await this.fetch(
      `https://www.strava.com/api/v3/athletes/${encodeURIComponent(athleteId)}/routes?page=${page}&per_page=50`,
      { headers: { Authorization: `Bearer ${session.tokens.access_token}` } }
    );
    if (!response.ok) throw new OAuthError(`STRAVA_ROUTES_${response.status}`, 502);
    const routes = await response.json();
    return {
      sessionToken: session.sessionToken,
      routes: routes.map(route => ({
        id: String(route.id_str ?? route.id),
        name: route.name,
        description: route.description ?? "",
        distanceMeters: route.distance,
        elevationGainMeters: route.elevation_gain,
        estimatedMovingTimeSeconds: route.estimated_moving_time,
        summaryPolyline: route.map?.summary_polyline ?? null,
        isPrivate: Boolean(route.private),
        updatedAt: route.updated_at
      }))
    };
  }

  async exportGpx(sessionToken, routeId) {
    const session = await this.activeSession(sessionToken);
    if (!/^\d+$/.test(routeId)) throw new OAuthError("ROUTE_ID_INVALID");
    const response = await this.fetch(`https://www.strava.com/api/v3/routes/${routeId}/export_gpx`, {
      headers: { Authorization: `Bearer ${session.tokens.access_token}` }
    });
    if (!response.ok) throw new OAuthError(`STRAVA_EXPORT_${response.status}`, 502);
    return {
      bytes: new Uint8Array(await response.arrayBuffer()),
      sessionToken: session.sessionToken
    };
  }

  async activeSession(token) {
    this.cleanup();
    const session = this.openSession(token);
    let sessionToken = token;
    if (Number(session.tokens.expires_at) * 1000 <= this.now() + 5 * 60_000) {
      session.tokens = await this.tokenRequest({
        refresh_token: session.tokens.refresh_token,
        grant_type: "refresh_token"
      });
      sessionToken = this.sealSession(session.tokens, session.expiresAt);
    }
    return { ...session, sessionToken };
  }

  async tokenRequest(values) {
    const response = await this.fetch("https://www.strava.com/api/v3/oauth/token", {
      method: "POST",
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body: new URLSearchParams({
        client_id: this.clientId,
        client_secret: this.clientSecret,
        ...values
      }).toString()
    });
    if (!response.ok) throw new OAuthError(`STRAVA_TOKEN_${response.status}`, 502);
    const tokens = await response.json();
    if (!tokens.access_token || !tokens.refresh_token) throw new OAuthError("STRAVA_TOKEN_INVALID", 502);
    return tokens;
  }

  cleanup() {
    const now = this.now();
    for (const [key, value] of this.states) if (value.expiresAt <= now) this.states.delete(key);
    for (const [key, value] of this.tickets) if (value.expiresAt <= now) this.tickets.delete(key);
  }

  sealSession(tokens, expiresAt = this.now() + SESSION_TTL_MS) {
    const iv = randomBytes(12);
    const cipher = createCipheriv("aes-256-gcm", this.sessionKey, iv);
    const plaintext = Buffer.from(JSON.stringify({ version: 1, expiresAt, tokens }));
    const encrypted = Buffer.concat([cipher.update(plaintext), cipher.final()]);
    return Buffer.concat([iv, cipher.getAuthTag(), encrypted]).toString("base64url");
  }

  openSession(token) {
    try {
      if (typeof token !== "string" || token.length < 40) throw new Error("invalid");
      const packed = Buffer.from(token, "base64url");
      if (packed.length < 29) throw new Error("invalid");
      const decipher = createDecipheriv("aes-256-gcm", this.sessionKey, packed.subarray(0, 12));
      decipher.setAuthTag(packed.subarray(12, 28));
      const plaintext = Buffer.concat([decipher.update(packed.subarray(28)), decipher.final()]);
      const value = JSON.parse(plaintext.toString("utf8"));
      if (value.version !== 1 || value.expiresAt <= this.now() || !value.tokens?.access_token
          || !value.tokens?.refresh_token) throw new Error("invalid");
      return value;
    } catch (invalid) {
      throw new OAuthError("SESSION_INVALID", 401);
    }
  }
}

function required(value, name) {
  if (!value) throw new Error(`${name}_REQUIRED`);
  return value;
}

function appendQuery(url, values) {
  const parsed = new URL(url);
  for (const [key, value] of Object.entries(values)) parsed.searchParams.set(key, value);
  return parsed.toString();
}
