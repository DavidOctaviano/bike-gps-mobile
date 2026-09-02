package com.bikegps.companion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.util.List;
import org.junit.Test;

public final class PolylineCodecTest {
  @Test public void decodesGoogleAndStravaPolylineFormat() {
    List<double[]> points = PolylineCodec.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@");
    assertEquals(3, points.size());
    assertEquals(38.5, points.get(0)[0], 0.00001);
    assertEquals(-120.2, points.get(0)[1], 0.00001);
    assertEquals(43.252, points.get(2)[0], 0.00001);
    assertEquals(-126.453, points.get(2)[1], 0.00001);
  }

  @Test public void rejectsTruncatedPolyline() {
    assertThrows(IllegalArgumentException.class, () -> PolylineCodec.decode("_p~iF"));
  }
}
