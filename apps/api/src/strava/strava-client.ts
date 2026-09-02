export type StravaTokens = {
  access_token: string;
  refresh_token: string;
  expires_at: number;
  athlete: { id: number; firstname: string; lastname: string };
};

export class StravaClient {
  constructor(
    private readonly clientId: string,
    private readonly clientSecret: string
  ) {}

  authorizationUrl(redirectUri: string, state: string): string {
    const query = new URLSearchParams({
      client_id: this.clientId,
      redirect_uri: redirectUri,
      response_type: "code",
      approval_prompt: "auto",
      scope: "read,read_all",
      state
    });
    return `https://www.strava.com/oauth/authorize?${query}`;
  }

  async exchangeCode(code: string): Promise<StravaTokens> {
    return this.tokenRequest({ code, grant_type: "authorization_code" });
  }

  async refresh(refreshToken: string): Promise<StravaTokens> {
    return this.tokenRequest({
      refresh_token: refreshToken,
      grant_type: "refresh_token"
    });
  }

  async listRoutes(accessToken: string, athleteId: string, page = 1) {
    const response = await fetch(
      `https://www.strava.com/api/v3/athletes/${athleteId}/routes?page=${page}&per_page=50`,
      { headers: { Authorization: `Bearer ${accessToken}` } }
    );
    if (!response.ok) throw new Error(`STRAVA_ROUTES_${response.status}`);
    const routes = await response.json() as any[];
    return routes.map(route => ({
      id: String(route.id_str ?? route.id),
      name: route.name,
      description: route.description ?? "",
      distanceMeters: route.distance,
      elevationGainMeters: route.elevation_gain,
      estimatedMovingTimeSeconds: route.estimated_moving_time,
      summaryPolyline: route.map?.summary_polyline ?? null,
      isPrivate: Boolean(route.private),
      updatedAt: route.updated_at
    }));
  }

  async exportGpx(accessToken: string, routeId: string): Promise<Uint8Array> {
    const response = await fetch(
      `https://www.strava.com/api/v3/routes/${routeId}/export_gpx`,
      { headers: { Authorization: `Bearer ${accessToken}` } }
    );
    if (!response.ok) throw new Error(`STRAVA_EXPORT_${response.status}`);
    return new Uint8Array(await response.arrayBuffer());
  }

  private async tokenRequest(payload: Record<string, string>): Promise<StravaTokens> {
    const response = await fetch("https://www.strava.com/oauth/token", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        client_id: this.clientId,
        client_secret: this.clientSecret,
        ...payload
      })
    });
    if (!response.ok) throw new Error(`STRAVA_TOKEN_${response.status}`);
    return response.json() as Promise<StravaTokens>;
  }
}

