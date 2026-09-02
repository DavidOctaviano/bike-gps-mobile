package com.bikegps.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.provider.Settings;
import android.view.Window;
import android.view.WindowInsetsController;
import android.widget.ScrollView;

import com.bikegps.companion.ble.AndroidBleTransport;
import com.bikegps.companion.ble.RouteTransferEngine;
import com.bikegps.companion.ble.RouteTransferProtocol;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements RideDashboardView.Actions, LocationListener {
  private static final int REQUEST_BLE = 41;
  private static final int REQUEST_LOCATION = 42;
  private static final long SCAN_MILLIS = 12_000;

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final RouteTransferEngine transferEngine = new RouteTransferEngine();
  private final Runnable scanTimeout = this::finishScanWithoutDevice;
  private RideDashboardView dashboard;
  private BluetoothLeScanner scanner;
  private AndroidBleTransport transport;
  private LocationManager locationManager;
  private Location lastLocation;
  private double distanceMeters;
  private long rideStartedAt;
  private volatile boolean transferCancelled;
  private String backendSession;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    dashboard = new RideDashboardView(this);
    dashboard.setActions(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(android.graphics.Color.rgb(5, 13, 12));
    scroll.addView(dashboard, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    setContentView(scroll);
    configureSystemBars();
    locationManager = getSystemService(LocationManager.class);
    handleOAuthIntent(getIntent());
  }

  private void configureSystemBars() {
    Window window = getWindow();
    window.setStatusBarColor(android.graphics.Color.rgb(5, 13, 12));
    window.setNavigationBarColor(android.graphics.Color.rgb(5, 13, 12));
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
      if (controller != null) controller.setSystemBarsAppearance(0,
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
  }

  @Override public void onRideToggle() {
    if (dashboard.isRiding()) stopRide();
    else if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) startRide();
    else requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
  }

  @SuppressLint("MissingPermission")
  private void startRide() {
    if (locationManager == null || !locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
      dashboard.setMessage("Ative o GPS para iniciar o pedal");
      startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
      return;
    }
    distanceMeters = 0;
    lastLocation = null;
    rideStartedAt = android.os.SystemClock.elapsedRealtime();
    dashboard.updateRide(0, 0, 0);
    dashboard.setRiding(true);
    dashboard.setMessage("Gravando percurso · navegação ativa");
    locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1_000, 2f, this);
  }

  private void stopRide() {
    if (locationManager != null) locationManager.removeUpdates(this);
    dashboard.setRiding(false);
    dashboard.setMessage(String.format(java.util.Locale.getDefault(), "Pedal encerrado · %.2f km", distanceMeters / 1000d));
  }

  @Override public void onLocationChanged(Location location) {
    if (!dashboard.isRiding() || location.getAccuracy() > 40f) return;
    if (lastLocation != null) {
      float segment = lastLocation.distanceTo(location);
      if (segment >= 1f && segment < 250f) distanceMeters += segment;
    }
    lastLocation = location;
    double elapsedHours = Math.max(1, android.os.SystemClock.elapsedRealtime() - rideStartedAt) / 3_600_000d;
    double average = distanceMeters / 1000d / elapsedHours;
    double live = location.hasSpeed() ? Math.max(0, location.getSpeed() * 3.6d) : 0;
    dashboard.updateRide(average, live, distanceMeters / 1000d);
  }

  @Override public void onBle() {
    if (transport != null && transport.isConnected()) {
      dashboard.setMessage("Bike GPS já está conectado");
      return;
    }
    if (!hasBlePermissions()) {
      if (Build.VERSION.SDK_INT >= 31) {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE);
      } else {
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_BLE);
      }
      return;
    }
    startBleScan();
  }

  private boolean hasBlePermissions() {
    if (Build.VERSION.SDK_INT >= 31) {
      return checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
          && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private void startBleScan() {
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) { dashboard.setMessage("Este aparelho não oferece Bluetooth LE"); return; }
    if (!adapter.isEnabled()) { dashboard.setMessage("Ative o Bluetooth para procurar o Bike GPS"); return; }
    scanner = adapter.getBluetoothLeScanner();
    if (scanner == null) { dashboard.setMessage("Scanner BLE indisponível"); return; }
    dashboard.setMessage("Procurando ciclocomputador Bike GPS…");
    ScanFilter filter = new ScanFilter.Builder().setServiceUuid(new ParcelUuid(RouteTransferProtocol.SERVICE_UUID)).build();
    ScanSettings settings = new ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    scanner.startScan(Collections.singletonList(filter), settings, scanCallback);
    main.postDelayed(scanTimeout, SCAN_MILLIS);
  }

  private final ScanCallback scanCallback = new ScanCallback() {
    @Override public void onScanResult(int callbackType, ScanResult result) { connectTo(result.getDevice()); }
    @Override public void onBatchScanResults(java.util.List<ScanResult> results) {
      if (!results.isEmpty()) connectTo(results.get(0).getDevice());
    }
    @Override public void onScanFailed(int errorCode) {
      stopScan();
      dashboard.setMessage("Falha no scan BLE · código " + errorCode);
    }
  };

  private void connectTo(BluetoothDevice device) {
    stopScan();
    if (transport != null) transport.close();
    transport = new AndroidBleTransport(this, device, (connected, state) -> main.post(() -> {
      dashboard.setConnection(connected);
      dashboard.setMessage(state);
    }));
    AndroidBleTransport selected = transport;
    worker.execute(() -> {
      try {
        selected.connect();
      } catch (Exception failure) {
        main.post(() -> {
          dashboard.setConnection(false);
          dashboard.setMessage(readableFailure(failure));
        });
      }
    });
  }

  private void finishScanWithoutDevice() {
    if (scanner != null) {
      stopScan();
      dashboard.setMessage("Bike GPS não encontrado · aproxime e tente novamente");
    }
  }

  @SuppressLint("MissingPermission")
  private void stopScan() {
    if (scanner != null && hasBlePermissions()) scanner.stopScan(scanCallback);
    scanner = null;
    main.removeCallbacks(scanTimeout);
  }

  @Override public void onTransferDemo() {
    AndroidBleTransport active = transport;
    if (active == null || !active.isConnected()) {
      dashboard.setMessage("Conecte o Bike GPS antes de enviar a rota");
      return;
    }
    byte[] route = DemoRoute.gpx();
    String hash = RouteTransferProtocol.sha256(route);
    String transferId = "demo-" + hash.substring(0, 12);
    transferCancelled = false;
    dashboard.setTransferProgress(0);
    dashboard.setMessage("Preparando rota de Lagoa da Prata…");
    worker.execute(() -> {
      try {
        transferEngine.transfer(active, transferId, "lp-demo.gpx", route,
            value -> main.post(() -> {
              dashboard.setTransferProgress((float) value);
              dashboard.setMessage("Enviando rota · " + Math.round(value * 100) + "%");
            }), () -> transferCancelled);
        main.post(() -> {
          dashboard.setTransferProgress(1);
          dashboard.setMessage("Rota verificada e salva no Bike GPS");
        });
      } catch (Exception failure) {
        main.post(() -> {
          dashboard.setTransferProgress(-1);
          dashboard.setMessage(readableFailure(failure));
        });
      }
    });
  }

  @Override public void onStrava() {
    String api = normalizedApiUrl();
    if (api.isEmpty()) {
      dashboard.setMessage("Defina BIKEGPS_API_BASE_URL para conectar o Strava");
      return;
    }
    Uri authorization = Uri.parse(api + "/oauth/strava/start").buildUpon()
        .appendQueryParameter("app_redirect_uri", "bikegps://oauth/strava")
        .build();
    startActivity(new Intent(Intent.ACTION_VIEW, authorization));
  }

  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    handleOAuthIntent(intent);
  }

  private void handleOAuthIntent(Intent intent) {
    Uri uri = intent == null ? null : intent.getData();
    if (uri == null || !"bikegps".equals(uri.getScheme()) || !"oauth".equals(uri.getHost())
        || !"/strava".equals(uri.getPath())) return;
    String error = uri.getQueryParameter("error");
    String ticket = uri.getQueryParameter("ticket");
    if (error != null) { dashboard.setMessage("Strava não conectado · " + error); return; }
    if (ticket == null || ticket.isEmpty()) { dashboard.setMessage("Retorno OAuth sem ticket"); return; }
    exchangeOAuthTicket(ticket);
  }

  private void exchangeOAuthTicket(String ticket) {
    String api = normalizedApiUrl();
    if (api.isEmpty()) { dashboard.setMessage("Backend OAuth não configurado"); return; }
    dashboard.setMessage("Concluindo conexão segura com o Strava…");
    worker.execute(() -> {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(api + "/oauth/ticket").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = new JSONObject().put("ticket", ticket).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        int status = connection.getResponseCode();
        String response = readAll(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
        if (status != 200) throw new IllegalStateException("OAUTH_TICKET_" + status);
        JSONObject json = new JSONObject(response);
        backendSession = json.getString("sessionToken");
        String athlete = json.optString("athleteName", "Strava conectado");
        main.post(() -> {
          dashboard.setStravaConnected(true);
          dashboard.setMessage("Strava conectado · " + athlete);
        });
      } catch (Exception failure) {
        main.post(() -> dashboard.setMessage(readableFailure(failure)));
      } finally {
        if (connection != null) connection.disconnect();
      }
    });
  }

  private static String readAll(InputStream input) throws Exception {
    if (input == null) return "";
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
      StringBuilder value = new StringBuilder();
      String line;
      while ((line = reader.readLine()) != null) value.append(line);
      return value.toString();
    }
  }

  private static String normalizedApiUrl() {
    String value = BuildConfig.API_BASE_URL == null ? "" : BuildConfig.API_BASE_URL.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String readableFailure(Exception failure) {
    String code = failure.getMessage();
    if (code == null || code.isEmpty()) code = failure.getClass().getSimpleName();
    return "Operação não concluída · " + code.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    boolean granted = results.length > 0;
    for (int result : results) granted &= result == PackageManager.PERMISSION_GRANTED;
    if (requestCode == REQUEST_BLE) {
      if (granted) startBleScan(); else dashboard.setMessage("Permissão Bluetooth necessária para conectar");
    } else if (requestCode == REQUEST_LOCATION) {
      if (granted) startRide(); else dashboard.setMessage("Permissão de localização necessária para gravar o pedal");
    }
  }

  @Override protected void onDestroy() {
    transferCancelled = true;
    stopScan();
    if (locationManager != null) locationManager.removeUpdates(this);
    if (transport != null) transport.close();
    worker.shutdownNow();
    backendSession = null;
    super.onDestroy();
  }
}
