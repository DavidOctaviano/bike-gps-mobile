package com.bikegps.companion.ble;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Android GATT adapter for the protocol's Control, Data, ACK and Status characteristics. */
@SuppressLint("MissingPermission")
public final class AndroidBleTransport implements RouteTransferEngine.Transport, AutoCloseable {
  private static final UUID CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");

  public interface Listener { void onState(boolean connected, String state); }

  private final Context context;
  private final BluetoothDevice device;
  private final Listener listener;
  private final Object writeLock = new Object();
  private final BlockingQueue<String> ackMessages = new LinkedBlockingQueue<>();
  private final BlockingQueue<String> statusMessages = new LinkedBlockingQueue<>();
  private volatile BluetoothGatt gatt;
  private volatile BluetoothGattCharacteristic control;
  private volatile BluetoothGattCharacteristic data;
  private volatile BluetoothGattCharacteristic ack;
  private volatile BluetoothGattCharacteristic status;
  private volatile CountDownLatch connectionLatch;
  private volatile CountDownLatch writeLatch;
  private volatile boolean writeSucceeded;
  private volatile boolean connected;
  private volatile int maximumWriteBytes = 20;
  private int descriptorStep;

  public AndroidBleTransport(Context context, BluetoothDevice device, Listener listener) {
    this.context = context.getApplicationContext();
    this.device = device;
    this.listener = listener;
  }

  @Override public void connect() throws IOException {
    if (connected) return;
    ackMessages.clear();
    statusMessages.clear();
    listener.onState(false, "Conectando ao Bike GPS…");
    connectionLatch = new CountDownLatch(1);
    gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE);
    if (gatt == null) throw new IOException("GATT_CONNECT_FAILED");
    await(connectionLatch, 15_000, "GATT_CONNECT_TIMEOUT");
    if (!connected) throw new IOException("GATT_SERVICE_UNAVAILABLE");
  }

  @Override public int maximumWriteBytes() throws IOException {
    if (!connected) throw new IOException("GATT_NOT_CONNECTED");
    return maximumWriteBytes;
  }

  @Override public void writeControl(String json) throws IOException {
    write(control, json.getBytes(StandardCharsets.UTF_8));
  }

  @Override public void writeData(byte[] packet) throws IOException {
    write(data, packet);
  }

  @Override public int waitForReady(long timeoutMillis) throws IOException {
    JSONObject message = waitFor(statusMessages, "READY", timeoutMillis);
    return message.optInt("resumeFromSequence", 0);
  }

  @Override public RouteTransferEngine.Ack waitForAck(int sequence, long timeoutMillis) throws IOException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (true) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) throw new IOException("ACK_TIMEOUT_" + sequence);
      String raw = poll(ackMessages, remaining, "ACK_TIMEOUT_" + sequence);
      try {
        JSONObject json = new JSONObject(raw);
        int receivedSequence = json.getInt("sequence");
        if (receivedSequence != sequence) continue;
        RouteTransferEngine.AckStatus value = RouteTransferEngine.AckStatus.valueOf(json.getString("status"));
        return new RouteTransferEngine.Ack(receivedSequence, value);
      } catch (JSONException | IllegalArgumentException malformed) {
        throw new IOException("MALFORMED_ACK", malformed);
      }
    }
  }

  @Override public String waitForComplete(long timeoutMillis) throws IOException {
    return waitFor(statusMessages, "TRANSFER_COMPLETE", timeoutMillis).optString("sha256", "");
  }

  private JSONObject waitFor(BlockingQueue<String> queue, String command, long timeoutMillis) throws IOException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (true) {
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0) throw new IOException(command + "_TIMEOUT");
      try {
        JSONObject json = new JSONObject(poll(queue, remaining, command + "_TIMEOUT"));
        if (command.equals(json.optString("command"))) return json;
        if ("ERROR".equals(json.optString("command"))) {
          throw new IOException("DEVICE_" + json.optString("code", "ERROR"));
        }
      } catch (JSONException malformed) {
        throw new IOException("MALFORMED_STATUS", malformed);
      }
    }
  }

  private static String poll(BlockingQueue<String> queue, long timeoutMillis, String error) throws IOException {
    try {
      String value = queue.poll(timeoutMillis, TimeUnit.MILLISECONDS);
      if (value == null) throw new IOException(error);
      return value;
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("INTERRUPTED", interrupted);
    }
  }

  private void write(BluetoothGattCharacteristic characteristic, byte[] value) throws IOException {
    if (!connected || characteristic == null || gatt == null) throw new IOException("GATT_NOT_CONNECTED");
    synchronized (writeLock) {
      writeLatch = new CountDownLatch(1);
      writeSucceeded = false;
      boolean started;
      if (Build.VERSION.SDK_INT >= 33) {
        started = gatt.writeCharacteristic(characteristic, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
            == android.bluetooth.BluetoothStatusCodes.SUCCESS;
      } else {
        characteristic.setWriteType(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT);
        characteristic.setValue(value);
        started = gatt.writeCharacteristic(characteristic);
      }
      if (!started) throw new IOException("GATT_WRITE_REJECTED");
      await(writeLatch, 5_000, "GATT_WRITE_TIMEOUT");
      if (!writeSucceeded) throw new IOException("GATT_WRITE_FAILED");
    }
  }

  private static void await(CountDownLatch latch, long timeoutMillis, String error) throws IOException {
    try {
      if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) throw new IOException(error);
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new IOException("INTERRUPTED", interrupted);
    }
  }

  private final BluetoothGattCallback callback = new BluetoothGattCallback() {
    @Override public void onConnectionStateChange(BluetoothGatt callbackGatt, int result, int newState) {
      if (result == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
        listener.onState(false, "BLE conectado · negociando MTU");
        callbackGatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
        if (!callbackGatt.requestMtu(517) && !callbackGatt.discoverServices()) {
          failConnection("Não foi possível descobrir os serviços GATT");
        }
      } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
        connected = false;
        listener.onState(false, result == BluetoothGatt.GATT_SUCCESS
            ? "Bike GPS desconectado" : "Conexão BLE falhou · GATT " + result);
        CountDownLatch latch = connectionLatch;
        if (latch != null) latch.countDown();
        callbackGatt.close();
      }
    }

    @Override public void onMtuChanged(BluetoothGatt callbackGatt, int mtu, int result) {
      if (result == BluetoothGatt.GATT_SUCCESS) maximumWriteBytes = Math.max(20, mtu - 3);
      if (!callbackGatt.discoverServices()) failConnection("Não foi possível descobrir os serviços GATT");
    }

    @Override public void onServicesDiscovered(BluetoothGatt callbackGatt, int result) {
      BluetoothGattService service = callbackGatt.getService(RouteTransferProtocol.SERVICE_UUID);
      if (result != BluetoothGatt.GATT_SUCCESS || service == null) { failConnection("Serviço Bike GPS não encontrado"); return; }
      control = service.getCharacteristic(RouteTransferProtocol.CONTROL_UUID);
      data = service.getCharacteristic(RouteTransferProtocol.DATA_UUID);
      ack = service.getCharacteristic(RouteTransferProtocol.ACK_UUID);
      status = service.getCharacteristic(RouteTransferProtocol.STATUS_UUID);
      if (control == null || data == null || ack == null || status == null) {
        failConnection("Características GATT incompletas");
        return;
      }
      descriptorStep = 0;
      enableNotification(callbackGatt, ack);
    }

    @Override public void onDescriptorWrite(BluetoothGatt callbackGatt, BluetoothGattDescriptor descriptor, int result) {
      if (result != BluetoothGatt.GATT_SUCCESS) { failConnection("Não foi possível assinar notificações"); return; }
      if (descriptorStep++ == 0) enableNotification(callbackGatt, status);
      else {
        connected = true;
        listener.onState(true, "Bike GPS conectado · MTU " + (maximumWriteBytes + 3));
        CountDownLatch latch = connectionLatch;
        if (latch != null) latch.countDown();
      }
    }

    @Override public void onCharacteristicWrite(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, int result) {
      writeSucceeded = result == BluetoothGatt.GATT_SUCCESS;
      CountDownLatch latch = writeLatch;
      if (latch != null) latch.countDown();
    }

    @Override public void onCharacteristicChanged(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
      handleNotification(characteristic.getUuid(), characteristic.getValue());
    }

    @Override public void onCharacteristicChanged(
        BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic, byte[] value) {
      handleNotification(characteristic.getUuid(), value);
    }
  };

  private void enableNotification(BluetoothGatt callbackGatt, BluetoothGattCharacteristic characteristic) {
    if (!callbackGatt.setCharacteristicNotification(characteristic, true)) {
      failConnection("Notificações GATT indisponíveis");
      return;
    }
    BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CCCD_UUID);
    if (descriptor == null) { failConnection("CCCD não encontrado"); return; }
    byte[] value = (characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
        ? BluetoothGattDescriptor.ENABLE_INDICATION_VALUE : BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE;
    boolean started;
    if (Build.VERSION.SDK_INT >= 33) {
      started = callbackGatt.writeDescriptor(descriptor, value) == android.bluetooth.BluetoothStatusCodes.SUCCESS;
    } else {
      descriptor.setValue(value);
      started = callbackGatt.writeDescriptor(descriptor);
    }
    if (!started) failConnection("Falha ao configurar CCCD");
  }

  private void handleNotification(UUID uuid, byte[] value) {
    if (value == null) return;
    String message = new String(value, StandardCharsets.UTF_8);
    if (RouteTransferProtocol.ACK_UUID.equals(uuid)) ackMessages.offer(message);
    else if (RouteTransferProtocol.STATUS_UUID.equals(uuid)) statusMessages.offer(message);
  }

  private void failConnection(String state) {
    connected = false;
    listener.onState(false, state);
    CountDownLatch latch = connectionLatch;
    if (latch != null) latch.countDown();
    BluetoothGatt active = gatt;
    if (active != null) active.disconnect();
  }

  public boolean isConnected() { return connected; }

  @Override public void close() {
    connected = false;
    BluetoothGatt activeGatt = gatt;
    gatt = null;
    if (activeGatt != null) {
      activeGatt.disconnect();
      activeGatt.close();
    }
  }
}
