package com.bikegps.companion.ble;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public final class RouteTransferEngineTest {
  @Test public void resumesAndRetriesCrcFailure() throws Exception {
    byte[] route = "abcdefghijklmnopqrstuvwxyz0123456789".getBytes(StandardCharsets.UTF_8);
    FakeTransport transport = new FakeTransport(route, 2);
    List<Double> progress = new ArrayList<>();

    new RouteTransferEngine().transfer(transport, "transfer-1", "route.gpx", route, progress::add, () -> false);

    assertTrue(transport.startJson.contains("\"chunkSize\":8"));
    assertEquals(2, (int) transport.attempts.get(2));
    assertEquals(List.of(2, 2, 3, 4), transport.sequences);
    assertEquals(1d, progress.get(progress.size() - 1), 0d);
    assertTrue(transport.commitJson.contains(RouteTransferProtocol.sha256(route)));
  }

  @Test public void rejectsResumePastEnd() {
    FakeTransport transport = new FakeTransport(new byte[]{1, 2, 3}, 99);
    IOException failure = assertThrows(IOException.class, () -> new RouteTransferEngine().transfer(
        transport, "transfer", "route.gpx", new byte[]{1, 2, 3}, ignored -> {}, () -> false));
    assertEquals("INVALID_RESUME_SEQUENCE", failure.getMessage());
  }

  @Test public void retriesWhenAckTimesOut() throws Exception {
    byte[] route = "route-payload-for-timeout".getBytes(StandardCharsets.UTF_8);
    FakeTransport transport = new FakeTransport(route, 0, 0);
    new RouteTransferEngine().transfer(transport, "transfer", "route.gpx", route, ignored -> {}, () -> false);
    assertEquals(2, (int) transport.attempts.get(0));
  }

  private static final class FakeTransport implements RouteTransferEngine.Transport {
    final byte[] complete;
    final int resume;
    final List<Integer> sequences = new ArrayList<>();
    final Map<Integer, Integer> attempts = new HashMap<>();
    final int timeoutSequence;
    String startJson;
    String commitJson;
    int currentSequence;

    FakeTransport(byte[] complete, int resume) { this(complete, resume, -1); }
    FakeTransport(byte[] complete, int resume, int timeoutSequence) {
      this.complete = complete;
      this.resume = resume;
      this.timeoutSequence = timeoutSequence;
    }
    @Override public void connect() {}
    @Override public int maximumWriteBytes() { return 20; }
    @Override public void writeControl(String json) {
      if (json.contains("\"START\"")) startJson = json; else commitJson = json;
    }
    @Override public void writeData(byte[] packet) {
      currentSequence = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN).getInt(2);
      sequences.add(currentSequence);
      attempts.merge(currentSequence, 1, Integer::sum);
    }
    @Override public int waitForReady(long timeoutMillis) { return resume; }
    @Override public RouteTransferEngine.Ack waitForAck(int sequence, long timeoutMillis) throws IOException {
      if (sequence == timeoutSequence && attempts.get(sequence) == 1) throw new IOException("ACK_TIMEOUT");
      RouteTransferEngine.AckStatus status = sequence == 2 && attempts.get(sequence) == 1
          ? RouteTransferEngine.AckStatus.CRC_ERROR : RouteTransferEngine.AckStatus.OK;
      return new RouteTransferEngine.Ack(sequence, status);
    }
    @Override public String waitForComplete(long timeoutMillis) { return RouteTransferProtocol.sha256(complete); }
  }
}
