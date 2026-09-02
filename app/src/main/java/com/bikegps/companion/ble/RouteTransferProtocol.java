package com.bikegps.companion.ble;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.UUID;

/** Binary and GATT contract for Bike GPS Route Transfer Protocol v1. */
public final class RouteTransferProtocol {
  public static final int VERSION = 1;
  public static final int HEADER_BYTES = 8;
  public static final int CRC_BYTES = 4;
  public static final int MAX_FILE_BYTES = 16 * 1024 * 1024;

  public static final UUID SERVICE_UUID = UUID.fromString("7b100001-6a3d-4c9a-9b62-321b9a340001");
  public static final UUID CONTROL_UUID = UUID.fromString("7b100002-6a3d-4c9a-9b62-321b9a340001");
  public static final UUID DATA_UUID = UUID.fromString("7b100003-6a3d-4c9a-9b62-321b9a340001");
  public static final UUID ACK_UUID = UUID.fromString("7b100004-6a3d-4c9a-9b62-321b9a340001");
  public static final UUID STATUS_UUID = UUID.fromString("7b100005-6a3d-4c9a-9b62-321b9a340001");

  private RouteTransferProtocol() {}

  public static int chunkSize(int maximumWriteBytes) {
    int result = maximumWriteBytes - HEADER_BYTES - CRC_BYTES;
    if (result < 1) throw new IllegalArgumentException("NEGOTIATED_PAYLOAD_TOO_SMALL");
    return result;
  }

  public static byte[] packet(int sequence, byte[] bytes, int offset, int length) {
    if (sequence < 0 || offset < 0 || length < 0 || offset + length > bytes.length || length > 0xffff) {
      throw new IllegalArgumentException("INVALID_PACKET_ARGUMENT");
    }
    ByteBuffer packet = ByteBuffer.allocate(HEADER_BYTES + length + CRC_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    packet.put((byte) VERSION);
    packet.put((byte) 0);
    packet.putInt(sequence);
    packet.putShort((short) length);
    packet.put(bytes, offset, length);
    packet.putInt(crc32(bytes, offset, length));
    return packet.array();
  }

  public static int crc32(byte[] bytes) {
    return crc32(bytes, 0, bytes.length);
  }

  public static int crc32(byte[] bytes, int offset, int length) {
    int crc = 0xffffffff;
    for (int index = offset; index < offset + length; index++) {
      crc ^= bytes[index] & 0xff;
      for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (0xedb88320 & -(crc & 1));
    }
    return crc ^ 0xffffffff;
  }

  public static String sha256(byte[] bytes) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
      StringBuilder value = new StringBuilder(64);
      for (byte item : digest) value.append(String.format(Locale.ROOT, "%02x", item & 0xff));
      return value.toString();
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }

  public static String startJson(
      String transferId, String filename, int fileSize, int chunkSize, int totalChunks, String sha256) {
    return "{\"command\":\"START\",\"protocolVersion\":1,\"transferId\":\"" + escape(transferId)
        + "\",\"filename\":\"" + escape(filename) + "\",\"format\":\"GPX\",\"fileSize\":" + fileSize
        + ",\"chunkSize\":" + chunkSize + ",\"totalChunks\":" + totalChunks + ",\"sha256\":\""
        + escape(sha256) + "\"}";
  }

  public static String commitJson(String transferId, String sha256) {
    return "{\"command\":\"COMMIT\",\"transferId\":\"" + escape(transferId) + "\",\"sha256\":\""
        + escape(sha256) + "\"}";
  }

  private static String escape(String value) {
    StringBuilder escaped = new StringBuilder(value.length());
    for (int index = 0; index < value.length(); index++) {
      char character = value.charAt(index);
      switch (character) {
        case '\\': escaped.append("\\\\"); break;
        case '"': escaped.append("\\\""); break;
        case '\n': escaped.append("\\n"); break;
        case '\r': escaped.append("\\r"); break;
        case '\t': escaped.append("\\t"); break;
        default:
          if (character < 0x20) escaped.append(String.format(Locale.ROOT, "\\u%04x", (int) character));
          else escaped.append(character);
      }
    }
    return escaped.toString();
  }

  public static byte[] utf8(String value) {
    return value.getBytes(StandardCharsets.UTF_8);
  }
}
