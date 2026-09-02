package com.bikegps.companion;

import android.content.Context;
import android.location.Location;
import android.os.Bundle;

import org.maplibre.android.MapLibre;
import org.maplibre.android.camera.CameraPosition;
import org.maplibre.android.camera.CameraUpdateFactory;
import org.maplibre.android.geometry.LatLng;
import org.maplibre.android.maps.MapView;
import org.maplibre.android.maps.MapLibreMap;
import org.maplibre.android.maps.Style;
import org.maplibre.android.style.layers.CircleLayer;
import org.maplibre.android.style.layers.LineLayer;
import org.maplibre.android.style.sources.GeoJsonSource;
import org.maplibre.geojson.Feature;
import org.maplibre.geojson.LineString;
import org.maplibre.geojson.Point;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.maplibre.android.style.layers.PropertyFactory.circleColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.circleRadius;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor;
import static org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth;
import static org.maplibre.android.style.layers.PropertyFactory.lineColor;
import static org.maplibre.android.style.layers.PropertyFactory.lineOpacity;
import static org.maplibre.android.style.layers.PropertyFactory.lineWidth;

/** Owns the real interactive map, current GPS marker and selected route overlay. */
public final class BikeMapController {
  private static final String STYLE_URL = "https://tiles.openfreemap.org/styles/fiord";
  private static final String ROUTE_SOURCE = "bikegps-route-source";
  private static final String ROUTE_LAYER_GLOW = "bikegps-route-glow";
  private static final String ROUTE_LAYER = "bikegps-route";
  private static final String POSITION_SOURCE = "bikegps-position-source";
  private static final String POSITION_HALO = "bikegps-position-halo";
  private static final String POSITION_DOT = "bikegps-position-dot";

  private final MapView view;
  private MapLibreMap map;
  private Style style;
  private RouteData route = RouteData.demo();
  private Location location;
  private boolean centeredOnFirstFix;
  private boolean following;

  public BikeMapController(Context context, Bundle state, Consumer<String> errors) {
    MapLibre.getInstance(context.getApplicationContext());
    view = new MapView(context);
    view.onCreate(state);
    view.addOnDidFailLoadingMapListener(error ->
        errors.accept("Mapa indisponível · confira sua conexão com a internet"));
    view.getMapAsync(ready -> {
      map = ready;
      ready.getUiSettings().setCompassEnabled(true);
      ready.getUiSettings().setAttributionEnabled(true);
      ready.setStyle(new Style.Builder().fromUri(STYLE_URL), loaded -> {
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
    });
  }

  public MapView view() { return view; }

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
    map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
        .target(new LatLng(location.getLatitude(), location.getLongitude()))
        .zoom(17.0)
        .bearing(following && location.hasBearing() ? location.getBearing() : 0)
        .tilt(following ? 28 : 0)
        .build()), 700);
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
    map.animateCamera(CameraUpdateFactory.newCameraPosition(new CameraPosition.Builder()
        .target(new LatLng(latitude / route.points.size(), longitude / route.points.size()))
        .zoom(13.5)
        .build()), 500);
  }

  private void installLayers(Style loaded) {
    List<Point> points = routePoints();
    loaded.addSource(new GeoJsonSource(ROUTE_SOURCE, Feature.fromGeometry(LineString.fromLngLats(points))));
    loaded.addLayer(new LineLayer(ROUTE_LAYER_GLOW, ROUTE_SOURCE).withProperties(
        lineColor("#55c8ff33"), lineWidth(11f), lineOpacity(0.30f)));
    loaded.addLayer(new LineLayer(ROUTE_LAYER, ROUTE_SOURCE).withProperties(
        lineColor("#ccff33"), lineWidth(4.5f), lineOpacity(0.95f)));
    loaded.addSource(new GeoJsonSource(POSITION_SOURCE));
    loaded.addLayer(new CircleLayer(POSITION_HALO, POSITION_SOURCE).withProperties(
        circleColor("#414fe1d9"), circleRadius(14f), circleOpacity(0.24f)));
    loaded.addLayer(new CircleLayer(POSITION_DOT, POSITION_SOURCE).withProperties(
        circleColor("#41e1d9"), circleRadius(7f), circleStrokeColor("#ffffff"), circleStrokeWidth(2f)));
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
    Style active = style;
    if (active == null) return;
    GeoJsonSource source = active.getSourceAs(ROUTE_SOURCE);
    if (source != null) source.setGeoJson(Feature.fromGeometry(LineString.fromLngLats(routePoints())));
  }

  private void renderLocation() {
    Style active = style;
    Location current = location;
    if (active == null || current == null) return;
    GeoJsonSource source = active.getSourceAs(POSITION_SOURCE);
    if (source != null) source.setGeoJson(Feature.fromGeometry(
        Point.fromLngLat(current.getLongitude(), current.getLatitude())));
  }

  public void onStart() { view.onStart(); }
  public void onResume() { view.onResume(); }
  public void onPause() { view.onPause(); }
  public void onStop() { view.onStop(); }
  public void onLowMemory() { view.onLowMemory(); }
  public void onSaveInstanceState(Bundle state) { view.onSaveInstanceState(state); }
  public void onDestroy() { view.onDestroy(); }
}
