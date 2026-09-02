package com.bikegps.companion;

import com.bikegps.companion.ble.RouteTransferProtocol;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.xml.parsers.SAXParserFactory;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

/** Validated GPX bytes and the geographic points used for map display and BLE transfer. */
public final class RouteData {
  public final String name;
  public final String filename;
  public final byte[] bytes;
  public final List<double[]> points;

  public RouteData(String name, String filename, byte[] bytes, List<double[]> points) {
    this.name = name;
    this.filename = safeFilename(filename);
    this.bytes = bytes.clone();
    List<double[]> copy = new ArrayList<>(points.size());
    for (double[] point : points) copy.add(point.clone());
    this.points = Collections.unmodifiableList(copy);
  }

  public static RouteData demo() {
    List<double[]> points = new ArrayList<>();
    Collections.addAll(points, DemoRoute.POINTS);
    return new RouteData(DemoRoute.NAME, "lagoa-da-prata-demo.gpx", DemoRoute.gpx(), points);
  }

  public static RouteData fromGpx(byte[] bytes, String fallbackName, String filename) throws Exception {
    if (bytes.length == 0 || bytes.length > RouteTransferProtocol.MAX_FILE_BYTES) {
      throw new IllegalArgumentException("GPX_SIZE_INVALID");
    }
    SAXParserFactory factory = SAXParserFactory.newInstance();
    factory.setNamespaceAware(true);
    setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
    setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
    setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
    GpxHandler handler = new GpxHandler();
    factory.newSAXParser().parse(new ByteArrayInputStream(bytes), handler);
    if (handler.points.size() < 2) throw new IllegalArgumentException("GPX_ROUTE_EMPTY");
    String name = handler.name == null || handler.name.isBlank() ? fallbackName : handler.name.trim();
    return new RouteData(name, filename, bytes, handler.points);
  }

  private static void setFeature(SAXParserFactory factory, String name, boolean value) {
    try { factory.setFeature(name, value); }
    catch (Exception unsupported) { /* Other XXE protections remain active on older Android parsers. */ }
  }

  private static String safeFilename(String value) {
    String safe = value == null ? "route.gpx" : value.replaceAll("[^A-Za-z0-9._-]", "-");
    if (!safe.toLowerCase(java.util.Locale.ROOT).endsWith(".gpx")) safe += ".gpx";
    return safe.length() > 80 ? safe.substring(safe.length() - 80) : safe;
  }

  private static final class GpxHandler extends DefaultHandler {
    final List<double[]> points = new ArrayList<>();
    String name;
    boolean readingName;
    final StringBuilder text = new StringBuilder();

    @Override public void startElement(String uri, String localName, String qName, Attributes attributes) {
      String tag = localName == null || localName.isEmpty() ? qName : localName;
      if ("trkpt".equals(tag) || "rtept".equals(tag)) {
        try {
          double latitude = Double.parseDouble(attributes.getValue("lat"));
          double longitude = Double.parseDouble(attributes.getValue("lon"));
          if (latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180) {
            points.add(new double[]{latitude, longitude});
          }
        } catch (RuntimeException ignored) { /* Invalid points are excluded. */ }
      } else if (name == null && "name".equals(tag)) {
        readingName = true;
        text.setLength(0);
      }
    }

    @Override public void characters(char[] chars, int start, int length) {
      if (readingName) text.append(chars, start, length);
    }

    @Override public void endElement(String uri, String localName, String qName) {
      String tag = localName == null || localName.isEmpty() ? qName : localName;
      if (readingName && "name".equals(tag)) {
        readingName = false;
        if (!text.toString().isBlank()) name = text.toString();
      }
    }
  }
}
