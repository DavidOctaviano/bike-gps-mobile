package com.bikegps.companion.ble;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** Reliable chunk sender independent from Android so its retry/resume behavior is unit-testable. */
public final class RouteTransferEngine {
  public enum AckStatus { OK, CRC_ERROR, OUT_OF_ORDER, NO_SPACE }

  public static final class Ack {
    public final int sequence;
    public final AckStatus status;
    public Ack(int sequence, AckStatus status) { this.sequence = sequence; this.status = status; }
  }

  public interface Transport {
    void connect() throws IOException;
    int maximumWriteBytes() throws IOException;
    void writeControl(String json) throws IOException;
    void writeData(byte[] packet) throws IOException;
    int waitForReady(long timeoutMillis) throws IOException;
    Ack waitForAck(int sequence, long timeoutMillis) throws IOException;
    String waitForComplete(long timeoutMillis) throws IOException;
  }

  public void transfer(
      Transport transport,
      String transferId,
      String filename,
      byte[] route,
      DoubleConsumer progress,
      BooleanSupplier cancelled) throws IOException {
    Objects.requireNonNull(transport, "transport");
    if (route.length == 0 || route.length > RouteTransferProtocol.MAX_FILE_BYTES) {
      throw new IOException("INVALID_ROUTE_SIZE");
    }
    transport.connect();
    int chunkSize = RouteTransferProtocol.chunkSize(transport.maximumWriteBytes());
    int totalChunks = (route.length + chunkSize - 1) / chunkSize;
    String hash = RouteTransferProtocol.sha256(route);
    transport.writeControl(RouteTransferProtocol.startJson(
        transferId, filename, route.length, chunkSize, totalChunks, hash));

    int sequence = transport.waitForReady(5_000);
    if (sequence < 0 || sequence > totalChunks) throw new IOException("INVALID_RESUME_SEQUENCE");
    progress.accept((double) sequence / totalChunks);

    while (sequence < totalChunks) {
      if (cancelled.getAsBoolean()) throw new CancellationException("TRANSFER_CANCELLED");
      int offset = sequence * chunkSize;
      int length = Math.min(chunkSize, route.length - offset);
      byte[] packet = RouteTransferProtocol.packet(sequence, route, offset, length);
      boolean confirmed = false;
      IOException lastFailure = null;
      for (int attempt = 0; attempt < 3 && !confirmed; attempt++) {
        try {
          transport.writeData(packet);
          Ack ack = transport.waitForAck(sequence, 3_000);
          if (ack.status == AckStatus.NO_SPACE) throw new IOException("DEVICE_STORAGE_FULL");
          confirmed = ack.sequence == sequence && ack.status == AckStatus.OK;
        } catch (IOException failure) {
          if ("DEVICE_STORAGE_FULL".equals(failure.getMessage())) throw failure;
          lastFailure = failure;
        }
      }
      if (!confirmed) {
        IOException failure = new IOException("CHUNK_FAILED_" + sequence);
        if (lastFailure != null) failure.initCause(lastFailure);
        throw failure;
      }
      sequence++;
      progress.accept((double) sequence / totalChunks);
    }

    transport.writeControl(RouteTransferProtocol.commitJson(transferId, hash));
    String deviceHash = transport.waitForComplete(15_000);
    if (!hash.equalsIgnoreCase(deviceHash)) throw new IOException("FINAL_HASH_MISMATCH");
  }
}
