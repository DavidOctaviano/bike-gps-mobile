package com.bikegps.companion;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Color;
import android.location.Location;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.mapbox.common.MapboxOptions;
import com.mapbox.geojson.Feature;
import com.mapbox.geojson.FeatureCollection;
import com.mapbox.geojson.LineString;
import com.mapbox.geojson.Point;
import com.mapbox.maps.CameraOptions;
import com.mapbox.maps.MapView;
import com.mapbox.maps.MapboxMap;
import com.mapbox.maps.Style;
import com.mapbox.maps.extension.style.expressions.generated.Expression;
import com.mapbox.maps.extension.style.layers.LayerUtils;
import com.mapbox.maps.extension.style.layers.generated.CircleLayer;
import com.mapbox.maps.extension.style.layers.generated.HeatmapLayer;
import com.mapbox.maps.extension.style.layers.generated.LineLayer;
import com.mapbox.maps.extension.style.layers.properties.generated.Visibility;
import com.mapbox.maps.extension.style.sources.SourceUtils;
import com.mapbox.maps.extension.style.sources.generated.GeoJsonSource;
import com.mapbox.maps.extension.style.sources.generated.VectorSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

/** Owns the Mapbox map, OSM-backed basemap, GPS marker and activity overlays. */
@SuppressLint("Lifecycle") // MainActivity is a platform Activity, so lifecycle is forwarded explicitly.
public final class BikeMapController {
  private static final String ROUTE_SOURCE = "bikegps-route-source";
  private static final String ROUTE_LAYER_GLOW = "bikegps-route-glow";
  private static final String ROUTE_LAYER = "bikegps-route";
  private static final String POSITION_SOURCE = "bikegps-position-source";
  private static final String POSITION_HALO = "bikegps-position-halo";
  private static final String POSITION_DOT = "bikegps-position-dot";
  private static final String POI_SOURCE = "bikegps-poi-source";
  private static final String POI_CLUSTER_LAYER = "bikegps-poi-clusters";
  private static final String POI_LAYER = "bikegps-pois";
  private static final String ACTIVITY_SOURCE = "bikegps-activity-source";
  private static final String ACTIVITY_LAYER = "bikegps-activity-heatmap";

  public enum MapStyleMode {
    LIGHT("CLARO", Style.LIGHT),
    DARK("ESCURO", Style.DARK),
    SATELLITE("SATÉLITE", Style.SATELLITE_STREETS);

    public final String label;
    public final String uri;

    MapStyleMode(String label, String uri) {
      this.label = label;
      this.uri = uri;
    }

    public MapStyleMode next() {
      MapStyleMode[] values = values();
      return values[(ordinal() + 1) % values.length];
    }
  }

  private final FrameLayout container;
  private final MapView mapView;
  private final String activityTilesUrl;
  private final String activityTilesLayer;
  private final Consumer<String> errors;
  private MapboxMap map;
  private Style style;
  private GeoJsonSource routeSource;
  private GeoJsonSource positionSource;
  private HeatmapLayer activityHeatmapLayer;
  private RouteData route = RouteData.demo();
  private Location location;
  private MapStyleMode styleMode = MapStyleMode.DARK;
  private boolean centeredOnFirstFix;
  private boolean following;
  private boolean heatmapVisible = true;

  public BikeMapController(
      Context context,
      Bundle state,
      String accessToken,
      String activityTilesUrl,
      String activityTilesLayer,
      Consumer<String> errors,
      Runnable configureToken) {
    this.errors = errors;
    this.activityTilesUrl = normalizeTilesUrl(activityTilesUrl);
    this.activityTilesLayer = activityTilesLayer == null || activityTilesLayer.isBlank()
        ? "activity" : activityTilesLayer.trim();
    container = new FrameLayout(context);

    if (!MapboxTokenPolicy.isUsablePublicToken(accessToken)) {
      mapView = null;
      showTokenRequired(context, configureToken);
      return;
    }

    MapboxOptions.setAccessToken(accessToken.trim());
    mapView = new MapView(context);
    mapView.setMaximumFps(60);
    container.addView(mapView, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    map = mapView.getMapboxMap();
    map.setPrefetchZoomDelta((byte) 4);
    map.subscribeMapLoadingError(event -> {
      String message = event.getMessage();
      if (message == null || message.isBlank()) message = "falha ao carregar dados";
      errors.accept("Mapa indisponível · " + message);
    });
    loadStyle();
  }

  public View view() { return container; }

  public String cycleStyle() {
    styleMode = styleMode.next();
    loadStyle();
    return styleMode.label;
  }

  public String styleLabel() { return styleMode.label; }

  public boolean hasActivityHeatmap() { return !activityTilesUrl.isEmpty(); }

  public boolean toggleActivityHeatmap() {
    if (!hasActivityHeatmap()) return false;
    heatmapVisible = !heatmapVisible;
    if (activityHeatmapLayer != null) {
      activityHeatmapLayer.visibility(heatmapVisible ? Visibility.VISIBLE : Visibility.NONE);
    }
    return heatmapVisible;
  }

  public void setRoute(RouteData value, boolean overview) {
    route = value;
    renderRoute();
    if (overview) showRouteOverview();
  }

  public void updateLocation(Location value) {
    location = new Location(value);
    renderLocation();
    if (style != null && !centeredOnFirstFix) {
      centeredOnFirstFix = true;
      centerOnUser();
    } else if (following) {
      centerOnUser();
    }
  }

  public void setFollowing(boolean value) {
    following = value;
    if (value) centerOnUser();
  }

  public boolean centerOnUser() {
    if (map == null || location == null) return false;
    map.setCamera(new CameraOptions.Builder()
        .center(Point.fromLngLat(location.getLongitude(), location.getLatitude()))
        .zoom(17.0)
        .bearing(following && location.hasBearing() ? (double) location.getBearing() : 0.0)
        .pitch(following ? 28.0 : 0.0)
        .build());
    return true;
  }

  public void showRouteOverview() {
    if (map == null || route.points.isEmpty()) return;
    double latitude = 0;
    double longitude = 0;
    for (double[] point : route.points) {
      latitude += point[0];
      longitude += point[1];
    }
    map.setCamera(new CameraOptions.Builder()
        .center(Point.fromLngLat(
            longitude / route.points.size(), latitude / route.points.size()))
        .zoom(13.5)
        .bearing(0.0)
        .pitch(0.0)
        .build());
  }

  private void loadStyle() {
    if (map == null) return;
    style = null;
    routeSource = null;
    positionSource = null;
    activityHeatmapLayer = null;
    map.loadStyle(styleMode.uri, loaded -> {
      style = loaded;
      installLayers(loaded);
      renderRoute();
      renderLocation();
      if (location == null) showRouteOverview();
      else if (!centeredOnFirstFix) {
        centeredOnFirstFix = true;
        centerOnUser();
      }
    });
  }

  private void installLayers(Style loaded) {
    routeSource = new GeoJsonSource.Builder(ROUTE_SOURCE)
        .feature(Feature.fromGeometry(LineString.fromLngLats(routePoints())))
        .lineMetrics(true)
        .build();
    SourceUtils.addSource(loaded, routeSource);
    LayerUtils.addLayer(loaded, new LineLayer(ROUTE_LAYER_GLOW, ROUTE_SOURCE)
        .lineColor("#55C8FF")
        .lineWidth(11.0)
        .lineOpacity(0.30));
    LayerUtils.addLayer(loaded, new LineLayer(ROUTE_LAYER, ROUTE_SOURCE)
        .lineColor("#CCFF33")
        .lineWidth(4.5)
        .lineOpacity(0.96));

    positionSource = new GeoJsonSource.Builder(POSITION_SOURCE)
        .featureCollection(FeatureCollection.fromFeatures(new ArrayList<>()))
        .build();
    SourceUtils.addSource(loaded, positionSource);
    LayerUtils.addLayer(loaded, new CircleLayer(POSITION_HALO, POSITION_SOURCE)
        .circleColor("#41E1D9")
        .circleRadius(15.0)
        .circleOpacity(0.24));
    LayerUtils.addLayer(loaded, new CircleLayer(POSITION_DOT, POSITION_SOURCE)
        .circleColor("#41E1D9")
        .circleRadius(7.0)
        .circleStrokeColor("#FFFFFF")
        .circleStrokeWidth(2.0));

    SourceUtils.addSource(loaded, new GeoJsonSource.Builder(POI_SOURCE)
        .featureCollection(demoCyclingPois())
        .cluster(true)
        .clusterRadius(46)
        .clusterMaxZoom(15)
        .build());
    LayerUtils.addLayer(loaded, new CircleLayer(POI_CLUSTER_LAYER, POI_SOURCE)
        .filter(Expression.has("point_count"))
        .circleColor("#FF7426")
        .circleRadius(12.0)
        .circleStrokeColor("#07100E")
        .circleStrokeWidth(2.0)
        .circleOpacity(0.90));
    LayerUtils.addLayer(loaded, new CircleLayer(POI_LAYER, POI_SOURCE)
        .filter(Expression.not(Expression.has("point_count")))
        .circleColor("#55C8FF")
        .circleRadius(5.0)
        .circleStrokeColor("#FFFFFF")
        .circleStrokeWidth(1.5));

    if (hasActivityHeatmap()) {
      VectorSource.Builder activitySource = new VectorSource.Builder(ACTIVITY_SOURCE)
          .minzoom(0)
          .maxzoom(16)
          .minimumTileUpdateInterval(900.0);
      if (activityTilesUrl.startsWith("mapbox://")) {
        activitySource.url(activityTilesUrl);
      } else {
        activitySource.tiles(Arrays.asList(activityTilesUrl));
      }
      SourceUtils.addSource(loaded, activitySource.build());
      HeatmapLayer heatmap = new HeatmapLayer(ACTIVITY_LAYER, ACTIVITY_SOURCE)
          .sourceLayer(activityTilesLayer)
          .heatmapRadius(22.0)
          .heatmapIntensity(1.15)
          .heatmapOpacity(0.82)
          .heatmapColor(Expression.interpolate(
              Expression.linear(), Expression.heatmapDensity(),
              Expression.literal(0.0), Expression.rgba(60.0, 190.0, 255.0, 0.0),
              Expression.literal(0.25), Expression.rgba(60.0, 190.0, 255.0, 0.65),
              Expression.literal(0.55), Expression.rgba(255.0, 190.0, 35.0, 0.82),
              Expression.literal(0.80), Expression.rgba(255.0, 92.0, 25.0, 0.92),
              Expression.literal(1.0), Expression.rgba(255.0, 25.0, 35.0, 1.0)))
          .visibility(heatmapVisible ? Visibility.VISIBLE : Visibility.NONE);
      activityHeatmapLayer = heatmap;
      LayerUtils.addLayerBelow(loaded, heatmap, ROUTE_LAYER_GLOW);
    }
  }

  private List<Point> routePoints() {
    List<Point> points = new ArrayList<>();
    for (double[] value : route.points) points.add(Point.fromLngLat(value[1], value[0]));
    if (points.size() < 2) {
      points.add(Point.fromLngLat(-45.5437, -20.0187));
      points.add(Point.fromLngLat(-45.5436, -20.0186));
    }
    return points;
  }

  private void renderRoute() {
    if (style == null || routeSource == null) return;
    routeSource.feature(Feature.fromGeometry(LineString.fromLngLats(routePoints())));
  }

  private void renderLocation() {
    if (style == null || positionSource == null || location == null) return;
    positionSource.feature(Feature.fromGeometry(
        Point.fromLngLat(location.getLongitude(), location.getLatitude())));
  }

  private static FeatureCollection demoCyclingPois() {
    List<Feature> features = new ArrayList<>();
    addPoi(features, "Orla da Lagoa", -45.5431, -20.0187);
    addPoi(features, "Praça da Matriz", -45.5412, -20.0236);
    addPoi(features, "Parque de Exposições", -45.5571, -20.0094);
    addPoi(features, "Acesso à ciclorrota", -45.5294, -20.0101);
    addPoi(features, "Ponto de apoio", -45.5505, -20.0274);
    addPoi(features, "Mirante da Lagoa", -45.5467, -20.0158);
    addPoi(features, "Água", -45.5390, -20.0198);
    addPoi(features, "Oficina", -45.5480, -20.0245);
    return FeatureCollection.fromFeatures(features);
  }

  private static void addPoi(List<Feature> features, String name, double longitude, double latitude) {
    Feature feature = Feature.fromGeometry(Point.fromLngLat(longitude, latitude));
    feature.addStringProperty("name", name);
    features.add(feature);
  }

  private static String normalizeTilesUrl(String value) {
    if (value == null) return "";
    String normalized = value.trim();
    if (normalized.isEmpty()) return "";
    if (normalized.startsWith("mapbox://")) return normalized;
    return normalized.startsWith("https://") && normalized.contains("{z}")
        && normalized.contains("{x}") && normalized.contains("{y}") ? normalized : "";
  }

  private void showTokenRequired(Context context, Runnable configureToken) {
    TextView prompt = new TextView(context);
    prompt.setText("MAPBOX NÃO CONFIGURADO\nToque para informar um token público pk.");
    prompt.setTextColor(Color.WHITE);
    prompt.setTextSize(13);
    prompt.setGravity(Gravity.CENTER);
    prompt.setPadding(32, 24, 32, 24);
    prompt.setBackgroundColor(Color.rgb(13, 27, 25));
    prompt.setOnClickListener(ignored -> configureToken.run());
    container.addView(prompt, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
  }

  public void onStart() { if (mapView != null) mapView.onStart(); }
  public void onResume() { if (mapView != null) mapView.onResume(); }
  public void onPause() { }
  public void onStop() { if (mapView != null) mapView.onStop(); }
  public void onLowMemory() { if (mapView != null) mapView.onLowMemory(); }
  public void onSaveInstanceState(Bundle state) { }
  public void onDestroy() { if (mapView != null) mapView.onDestroy(); }
}
