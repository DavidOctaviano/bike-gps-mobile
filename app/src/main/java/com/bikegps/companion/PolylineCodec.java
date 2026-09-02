package com.bikegps.companion;

import java.util.ArrayList;
import java.util.List;

/** Decoder for the encoded polyline returned by the Strava Routes API. */
public final class PolylineCodec {
  private PolylineCodec() {}

  public static List<double[]> decode(String encoded) {
    List<double[]> points = new ArrayList<>();
    if (encoded == null || encoded.isEmpty()) return points;
    int index = 0;
    int latitude = 0;
    int longitude = 0;
    while (index < encoded.length()) {
      Decoded lat = next(encoded, index);
      Decoded lon = next(encoded, lat.nextIndex);
      index = lon.nextIndex;
      latitude += lat.delta;
      longitude += lon.delta;
      points.add(new double[]{latitude / 100000d, longitude / 100000d});
    }
    return points;
  }

  private static Decoded next(String encoded, int start) {
    int result = 0;
    int shift = 0;
    int index = start;
    int value;
    do {
      if (index >= encoded.length() || shift > 30) throw new IllegalArgumentException("POLYLINE_INVALID");
      value = encoded.charAt(index++) - 63;
      if (value < 0 || value > 63) throw new IllegalArgumentException("POLYLINE_INVALID");
      result |= (value & 0x1f) << shift;
      shift += 5;
    } while (value >= 0x20);
    int delta = (result & 1) != 0 ? ~(result >>> 1) : result >>> 1;
    return new Decoded(delta, index);
  }

  private static final class Decoded {
    final int delta;
    final int nextIndex;
    Decoded(int delta, int nextIndex) { this.delta = delta; this.nextIndex = nextIndex; }
  }
}
