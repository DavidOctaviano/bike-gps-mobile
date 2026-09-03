package com.bikegps.companion;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class MapStyleModeTest {
  @Test public void cyclesThroughAllMapStyles() {
    BikeMapController.MapStyleMode mode = BikeMapController.MapStyleMode.LIGHT;
    assertEquals(BikeMapController.MapStyleMode.DARK, mode.next());
    assertEquals(BikeMapController.MapStyleMode.SATELLITE, mode.next().next());
    assertEquals(BikeMapController.MapStyleMode.LIGHT, mode.next().next().next());
  }
}
