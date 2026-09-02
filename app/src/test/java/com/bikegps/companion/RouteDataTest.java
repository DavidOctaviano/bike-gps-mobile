package com.bikegps.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class RouteDataTest {
  @Test public void parsesTrackAndRoutePoints() throws Exception {
    String gpx = "<?xml version=\"1.0\"?><gpx><metadata><name>Volta real</name></metadata>"
        + "<trk><trkseg><trkpt lat=\"-20.01\" lon=\"-45.54\"/>"
        + "<trkpt lat=\"-20.02\" lon=\"-45.55\"/></trkseg></trk></gpx>";
    RouteData route = RouteData.fromGpx(gpx.getBytes(StandardCharsets.UTF_8), "Fallback", "rota teste.gpx");
    assertEquals("Volta real", route.name);
    assertEquals("rota-teste.gpx", route.filename);
    assertEquals(2, route.points.size());
    assertEquals(-20.01, route.points.get(0)[0], 0.000001);
  }

  @Test public void rejectsGpxWithoutAtLeastTwoValidPoints() {
    byte[] empty = "<gpx><rte><rtept lat=\"999\" lon=\"0\"/></rte></gpx>"
        .getBytes(StandardCharsets.UTF_8);
    IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
        () -> RouteData.fromGpx(empty, "Empty", "empty.gpx"));
    assertEquals("GPX_ROUTE_EMPTY", failure.getMessage());
  }

  @Test public void demoIsACompleteTransferableGpx() throws Exception {
    RouteData demo = RouteData.demo();
    RouteData parsed = RouteData.fromGpx(demo.bytes, demo.name, demo.filename);
    assertTrue(parsed.points.size() >= 9);
    assertTrue(parsed.filename.endsWith(".gpx"));
  }
}
