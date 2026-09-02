package com.bikegps.companion;

import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Offline demonstration route centered in Lagoa da Prata, Minas Gerais. */
public final class DemoRoute {
  public static final String NAME = "Circuito Lagoa da Prata · Demo";
  public static final double[][] POINTS = {
      {-20.0187, -45.5437}, {-20.0160, -45.5378}, {-20.0198, -45.5309},
      {-20.0271, -45.5290}, {-20.0334, -45.5350}, {-20.0321, -45.5446},
      {-20.0270, -45.5503}, {-20.0208, -45.5492}, {-20.0187, -45.5437}
  };

  private DemoRoute() {}

  public static byte[] gpx() {
    StringBuilder xml = new StringBuilder();
    xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
        .append("<gpx version=\"1.1\" creator=\"Bike GPS\" xmlns=\"http://www.topografix.com/GPX/1/1\">")
        .append("<metadata><name>").append(NAME).append("</name></metadata><rte><name>")
        .append(NAME).append("</name>");
    for (double[] point : POINTS) {
      xml.append(String.format(Locale.US, "<rtept lat=\"%.6f\" lon=\"%.6f\"/>", point[0], point[1]));
    }
    xml.append("</rte></gpx>");
    return xml.toString().getBytes(StandardCharsets.UTF_8);
  }
}
