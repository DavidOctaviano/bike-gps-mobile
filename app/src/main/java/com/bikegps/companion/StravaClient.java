package com.bikegps.companion;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Calls only the Bike GPS backend; Strava credentials and tokens never enter this client. */
public final class StravaClient {
  public static final class RouteList {
    public final List<RouteSummary> routes;
    public final String sessionToken;
    RouteList(List<RouteSummary> routes, String sessionToken) {
      this.routes = routes;
      this.sessionToken = sessionToken;
    }
  }

  public static final class RouteDownload {
    public final RouteData route;
    public final String sessionToken;
    RouteDownload(RouteData route, String sessionToken) {
      this.route = route;
      this.sessionToken = sessionToken;
    }
  }

  public static final class RouteSummary {
    public final String id;
    public final String name;
    public final double distanceMeters;
    public final List<double[]> previewPoints;

    RouteSummary(String id, String name, double distanceMeters, List<double[]> previewPoints) {
      this.id = id;
      this.name = name;
      this.distanceMeters = distanceMeters;
      this.previewPoints = previewPoints;
    }
  }

  private final String apiBaseUrl;
  private final String sessionToken;

  public StravaClient(String apiBaseUrl, String sessionToken) {
    this.apiBaseUrl = apiBaseUrl;
    this.sessionToken = sessionToken;
  }

  public RouteList listRoutes() throws Exception {
    HttpResult response = get("/strava/routes?page=1", 2 * 1024 * 1024);
    JSONObject body = new JSONObject(new String(response.bytes, StandardCharsets.UTF_8));
    JSONArray routes = body.getJSONArray("routes");
    List<RouteSummary> result = new ArrayList<>();
    for (int index = 0; index < routes.length(); index++) {
      JSONObject route = routes.getJSONObject(index);
      String id = route.getString("id");
      if (!id.matches("\\d+")) continue;
      result.add(new RouteSummary(
          id,
          route.optString("name", "Rota Strava"),
          route.optDouble("distanceMeters", 0),
          PolylineCodec.decode(route.optString("summaryPolyline", ""))));
    }
    return new RouteList(result, body.optString("sessionToken", sessionToken));
  }

  public RouteDownload download(RouteSummary route) throws Exception {
    HttpResult response = get("/strava/routes/" + route.id + "/gpx", 16 * 1024 * 1024);
    RouteData data = RouteData.fromGpx(response.bytes, route.name, "strava-" + route.id + ".gpx");
    return new RouteDownload(data, response.sessionToken == null ? sessionToken : response.sessionToken);
  }

  private HttpResult get(String path, int maximumBytes) throws Exception {
    HttpURLConnection connection = (HttpURLConnection) new URL(apiBaseUrl + path).openConnection();
    try {
      connection.setRequestMethod("GET");
      connection.setConnectTimeout(12_000);
      connection.setReadTimeout(30_000);
      connection.setRequestProperty("Authorization", "Bearer " + sessionToken);
      connection.setRequestProperty("Accept", "application/json, application/gpx+xml");
      int status = connection.getResponseCode();
      InputStream input = status >= 200 && status < 300
          ? connection.getInputStream() : connection.getErrorStream();
      byte[] bytes = readLimited(input, maximumBytes);
      if (status < 200 || status >= 300) {
        String code = "HTTP_" + status;
        try { code = new JSONObject(new String(bytes, StandardCharsets.UTF_8)).optString("error", code); }
        catch (Exception ignored) { }
        throw new IllegalStateException(code);
      }
      return new HttpResult(bytes, connection.getHeaderField("X-BikeGPS-Session"));
    } finally {
      connection.disconnect();
    }
  }

  private static final class HttpResult {
    final byte[] bytes;
    final String sessionToken;
    HttpResult(byte[] bytes, String sessionToken) {
      this.bytes = bytes;
      this.sessionToken = sessionToken;
    }
  }

  private static byte[] readLimited(InputStream input, int maximumBytes) throws Exception {
    if (input == null) return new byte[0];
    try (InputStream source = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[8192];
      int count;
      int total = 0;
      while ((count = source.read(buffer)) != -1) {
        total += count;
        if (total > maximumBytes) throw new IllegalStateException("RESPONSE_TOO_LARGE");
        output.write(buffer, 0, count);
      }
      return output.toByteArray();
    }
  }
}
