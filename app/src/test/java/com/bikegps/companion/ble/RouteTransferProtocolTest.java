package com.bikegps.companion.ble;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public final class RouteTransferProtocolTest {
  @Test public void crc32MatchesStandardVector() {
    assertEquals(0xcbf43926L, Integer.toUnsignedLong(
        RouteTransferProtocol.crc32("123456789".getBytes(StandardCharsets.UTF_8))));
  }

  @Test public void packetIsLittleEndianAndCarriesPayloadCrc() {
    byte[] payload = {10, 20, 30, 40};
    byte[] packet = RouteTransferProtocol.packet(0x01020304, payload, 1, 2);
    assertEquals(14, packet.length);
    assertEquals(1, packet[0]);
    assertArrayEquals(new byte[]{4, 3, 2, 1}, new byte[]{packet[2], packet[3], packet[4], packet[5]});
    ByteBuffer view = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(2, Short.toUnsignedInt(view.getShort(6)));
    assertEquals(20, packet[8]);
    assertEquals(30, packet[9]);
    assertEquals(Integer.toUnsignedLong(RouteTransferProtocol.crc32(payload, 1, 2)),
        Integer.toUnsignedLong(view.getInt(10)));
  }

  @Test public void mtuPayloadSubtractsAttAndProtocolOverhead() {
    assertEquals(232, RouteTransferProtocol.chunkSize(244));
    assertThrows(IllegalArgumentException.class, () -> RouteTransferProtocol.chunkSize(12));
  }

  @Test public void manifestEscapesUntrustedFilename() {
    String json = RouteTransferProtocol.startJson("route", "x\".gpx", 10, 5, 2, "abc");
    assertTrue(json.contains("x\\\".gpx"));
    assertTrue(json.contains("\"chunkSize\":5"));
  }
}
