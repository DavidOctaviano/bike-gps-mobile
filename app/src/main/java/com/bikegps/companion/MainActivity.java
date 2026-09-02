package com.bikegps.companion;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.drawable.GradientDrawable;
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
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsetsController;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity implements RideDashboardView.Actions, LocationListener {
  private static final int REQUEST_BLE = 41;
  private static final int REQUEST_LOCATION = 42;
  private static final int REQUEST_EXPORT_GPX = 43;
  private static final int REQUEST_ENABLE_BLUETOOTH = 44;
  private static final long SCAN_MILLIS = 12_000;
  private static final String CONFIG_PREFS = "bikegps_config";
  private static final String API_URL = "api_base_url";

  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final RouteTransferEngine transferEngine = new RouteTransferEngine();
  private final Runnable scanTimeout = this::finishScanWithoutDevice;
  private RideDashboardView dashboard;
  private BikeMapController mapController;
  private TextView routeBadge;
  private BluetoothLeScanner scanner;
  private AndroidBleTransport transport;
  private LocationManager locationManager;
  private SecureSessionStore sessionStore;
  private SharedPreferences config;
  private Location lastLocation;
  private RouteData selectedRoute = RouteData.demo();
  private double distanceMeters;
  private long rideStartedAt;
  private volatile boolean transferCancelled;
  private boolean pendingTransfer;
  private boolean pendingRideStart;
  private boolean connecting;
  private volatile String backendSession;

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    config = getSharedPreferences(CONFIG_PREFS, MODE_PRIVATE);
    sessionStore = new SecureSessionStore(this);
    backendSession = sessionStore.load();
    locationManager = getSystemService(LocationManager.class);
    dashboard = new RideDashboardView(this);
    dashboard.setActions(this);
    dashboard.setStravaConnected(backendSession != null);
    mapController = new BikeMapController(this, state, dashboard::setMessage);
    setContentView(buildContent());
    configureSystemBars();
    handleOAuthIntent(getIntent());
    main.post(this::requestInitialLocation);
  }

  private View buildContent() {
    FrameLayout content = new FrameLayout(this);
    content.setMinimumHeight(dp(740));
    content.setBackgroundColor(Color.rgb(5, 13, 12));
    content.addView(dashboard, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, dp(740)));

    View map = mapController.view();
    map.setElevation(dp(2));
    map.setClipToOutline(true);
    map.setOutlineProvider(new ViewOutlineProvider() {
      @Override public void getOutline(View view, Outline outline) {
        outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(24));
      }
    });
    FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, dp(312));
    mapParams.setMargins(dp(20), dp(112), dp(20), 0);
    content.addView(map, mapParams);

    routeBadge = new TextView(this);
    routeBadge.setText(selectedRoute.name);
    routeBadge.setTextColor(Color.WHITE);
    routeBadge.setTextSize(12);
    routeBadge.setMaxLines(1);
    routeBadge.setPadding(dp(10), dp(7), dp(10), dp(7));
    routeBadge.setBackground(rounded(Color.argb(215, 5, 13, 12), dp(12)));
    routeBadge.setElevation(dp(8));
    FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(dp(235), dp(38));
    badgeParams.gravity = Gravity.TOP | Gravity.START;
    badgeParams.setMargins(dp(31), dp(124), 0, 0);
    content.addView(routeBadge, badgeParams);

    TextView locate = new TextView(this);
    locate.setText(R.string.map_center_gps);
    locate.setGravity(Gravity.CENTER);
    locate.setTextSize(11);
    locate.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
    locate.setTextColor(Color.rgb(204, 255, 51));
    locate.setContentDescription("Centralizar mapa na minha localização");
    locate.setBackground(rounded(Color.argb(225, 5, 13, 12), dp(16)));
    locate.setElevation(dp(8));
    locate.setOnClickListener(ignored -> locateUser());
    FrameLayout.LayoutParams locateParams = new FrameLayout.LayoutParams(dp(52), dp(44));
    locateParams.gravity = Gravity.TOP | Gravity.END;
    locateParams.setMargins(0, dp(124), dp(31), 0);
    content.addView(locate, locateParams);

    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setBackgroundColor(Color.rgb(5, 13, 12));
    scroll.addView(content, new ScrollView.LayoutParams(
        ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
    return scroll;
  }

  private GradientDrawable rounded(int color, int radius) {
    GradientDrawable result = new GradientDrawable();
    result.setColor(color);
    result.setCornerRadius(radius);
    return result;
  }

  private void configureSystemBars() {
    Window window = getWindow();
    window.setStatusBarColor(Color.rgb(5, 13, 12));
    window.setNavigationBarColor(Color.rgb(5, 13, 12));
    if (Build.VERSION.SDK_INT >= 30) {
      WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
      if (controller != null) controller.setSystemBarsAppearance(0,
          WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
              | WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
    }
  }

  private void requestInitialLocation() {
    if (hasLocationPermission()) startLocationTracking();
    else requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
        Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
  }

  private boolean hasLocationPermission() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        || checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  private boolean hasPreciseLocation() {
    return checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
  }

  @SuppressLint("MissingPermission")
  private void startLocationTracking() {
    if (locationManager == null || !hasLocationPermission()) return;
    Location best = null;
    for (String provider : new String[]{LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER}) {
      try {
        Location candidate = locationManager.getLastKnownLocation(provider);
        if (candidate != null && (best == null || candidate.getTime() > best.getTime())) best = candidate;
        if (locationManager.isProviderEnabled(provider)) {
          locationManager.requestLocationUpdates(provider, 1_000, 1f, this);
        }
      } catch (IllegalArgumentException ignored) { }
    }
    if (best != null) onLocationChanged(best);
    if (!hasPreciseLocation()) {
      dashboard.setMessage("Localização aproximada · habilite Localização precisa para navegação");
    } else if (best == null) {
      dashboard.setMessage("Buscando sinal GPS preciso…");
    }
  }

  private void locateUser() {
    if (!hasLocationPermission()) {
      requestInitialLocation();
    } else if (!mapController.centerOnUser()) {
      dashboard.setMessage("Aguardando a primeira posição do GPS…");
      startLocationTracking();
    }
  }

  @Override public void onRideToggle() {
    if (dashboard.isRiding()) stopRide();
    else if (hasPreciseLocation()) startRide();
    else {
      pendingRideStart = true;
      dashboard.setMessage("Permita Localização precisa para medir o pedal");
      requestPermissions(new String[]{Manifest.permission.ACCESS_COARSE_LOCATION,
          Manifest.permission.ACCESS_FINE_LOCATION}, REQUEST_LOCATION);
    }
  }

  private void startRide() {
    pendingRideStart = false;
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
    mapController.setFollowing(true);
    dashboard.setMessage("Gravando percurso · navegação GPS ativa");
    startLocationTracking();
  }

  private void stopRide() {
    dashboard.setRiding(false);
    mapController.setFollowing(false);
    dashboard.setMessage(String.format(Locale.getDefault(),
        "Pedal encerrado · %.2f km", distanceMeters / 1000d));
  }

  @Override public void onLocationChanged(Location location) {
    if (location == null || location.getLatitude() == 0 && location.getLongitude() == 0) return;
    mapController.updateLocation(location);
    dashboard.setGpsFix(true);
    if (!dashboard.isRiding() || location.getAccuracy() > 40f) {
      dashboard.setMessage(String.format(Locale.getDefault(),
          "Localização atual · precisão ±%.0f m", location.getAccuracy()));
      return;
    }
    if (lastLocation != null) {
      float segment = lastLocation.distanceTo(location);
      if (segment >= 1f && segment < 250f) distanceMeters += segment;
    }
    lastLocation = new Location(location);
    double elapsedHours = Math.max(1,
        android.os.SystemClock.elapsedRealtime() - rideStartedAt) / 3_600_000d;
    double average = distanceMeters / 1000d / elapsedHours;
    double live = location.hasSpeed() ? Math.max(0, location.getSpeed() * 3.6d) : 0;
    dashboard.updateRide(average, live, distanceMeters / 1000d);
    dashboard.setMessage(String.format(Locale.getDefault(),
        "Pedal em curso · GPS ±%.0f m", location.getAccuracy()));
  }

  @Override public void onBle() {
    if (transport != null && transport.isConnected()) {
      dashboard.setMessage("Bike GPS já está conectado");
      return;
    }
    if (!hasBlePermissions()) {
      if (Build.VERSION.SDK_INT >= 31) {
        requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT}, REQUEST_BLE);
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
    if (scanner != null || connecting) return;
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null) { dashboard.setMessage("Este aparelho não oferece Bluetooth LE"); return; }
    if (!adapter.isEnabled()) {
      dashboard.setMessage("Solicitando ativação do Bluetooth…");
      startActivityForResult(new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH);
      return;
    }
    scanner = adapter.getBluetoothLeScanner();
    if (scanner == null) { dashboard.setMessage("Scanner BLE indisponível"); return; }
    dashboard.setMessage("Procurando Bike GPS S3 por 12 segundos…");
    List<ScanFilter> filters = new ArrayList<>();
    filters.add(new ScanFilter.Builder()
        .setServiceUuid(new ParcelUuid(RouteTransferProtocol.SERVICE_UUID)).build());
    filters.add(new ScanFilter.Builder().setDeviceName("BikeGPS").build());
    filters.add(new ScanFilter.Builder().setDeviceName("Bike GPS S3").build());
    ScanSettings settings = new ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build();
    scanner.startScan(filters, settings, scanCallback);
    main.postDelayed(scanTimeout, SCAN_MILLIS);
  }

  private final ScanCallback scanCallback = new ScanCallback() {
    @Override public void onScanResult(int callbackType, ScanResult result) { connectTo(result.getDevice()); }
    @Override public void onBatchScanResults(List<ScanResult> results) {
      if (!results.isEmpty()) connectTo(results.get(0).getDevice());
    }
    @Override public void onScanFailed(int errorCode) {
      stopScan();
      dashboard.setMessage("Falha ao procurar BLE · código " + errorCode);
    }
  };

  private void connectTo(BluetoothDevice device) {
    if (connecting) return;
    connecting = true;
    stopScan();
    if (transport != null) transport.close();
    transport = new AndroidBleTransport(this, device, (connected, state) -> main.post(() -> {
      if (connected) connecting = false;
      dashboard.setConnection(connected);
      dashboard.setMessage(state);
      if (connected && pendingTransfer) {
        pendingTransfer = false;
        beginTransfer();
      }
    }));
    AndroidBleTransport selected = transport;
    worker.execute(() -> {
      try {
        selected.connect();
      } catch (Exception failure) {
        main.post(() -> {
          connecting = false;
          dashboard.setConnection(false);
          dashboard.setMessage(readableFailure(failure));
        });
      }
    });
  }

  private void finishScanWithoutDevice() {
    if (scanner == null) return;
    stopScan();
    pendingTransfer = false;
    dashboard.setMessage("Bike GPS S3 não encontrado · ligue o ESP32 com o firmware Bike GPS");
    new AlertDialog.Builder(this)
        .setTitle("Bike GPS não encontrado")
        .setMessage("A transferência sem fio exige um ESP32-S3 executando o protocolo Bike GPS v1. Você também pode salvar o GPX para uso manual.")
        .setPositiveButton("Tentar novamente", (dialog, which) -> onTransferDemo())
        .setNeutralButton("Salvar GPX", (dialog, which) -> exportSelectedRoute())
        .setNegativeButton("Cancelar", null)
        .show();
  }

  @SuppressLint("MissingPermission")
  private void stopScan() {
    if (scanner != null && hasBlePermissions()) scanner.stopScan(scanCallback);
    scanner = null;
    main.removeCallbacks(scanTimeout);
  }

  @Override public void onTransferDemo() {
    if (transport == null || !transport.isConnected()) {
      pendingTransfer = true;
      dashboard.setMessage("Conectando ao Bike GPS antes de enviar " + selectedRoute.name);
      onBle();
      return;
    }
    beginTransfer();
  }

  private void beginTransfer() {
    AndroidBleTransport active = transport;
    RouteData route = selectedRoute;
    if (active == null || !active.isConnected()) return;
    String hash = RouteTransferProtocol.sha256(route.bytes);
    String transferId = "route-" + hash.substring(0, 12);
    transferCancelled = false;
    dashboard.setTransferProgress(0);
    dashboard.setMessage("Preparando " + route.name + "…");
    worker.execute(() -> {
      try {
        transferEngine.transfer(active, transferId, route.filename, route.bytes,
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
    String api = apiBaseUrl();
    if (api.isEmpty()) {
      showBackendDialog();
      return;
    }
    if (backendSession != null) {
      loadStravaRoutes();
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("Conectar ao Strava")
        .setMessage("A autorização abrirá no Strava usando o servidor " + Uri.parse(api).getHost() + ".")
        .setPositiveButton("Continuar", (dialog, which) -> beginStravaOAuth(api))
        .setNeutralButton("Alterar servidor", (dialog, which) -> showBackendDialog())
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void beginStravaOAuth(String api) {
    Uri authorization = Uri.parse(api + "/oauth/strava/start").buildUpon()
        .appendQueryParameter("app_redirect_uri", "bikegps://oauth/strava")
        .build();
    dashboard.setMessage("Abrindo autorização segura do Strava…");
    startActivity(new Intent(Intent.ACTION_VIEW, authorization));
  }

  private void exportSelectedRoute() {
    Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("application/gpx+xml");
    intent.putExtra(Intent.EXTRA_TITLE, selectedRoute.filename);
    startActivityForResult(intent, REQUEST_EXPORT_GPX);
  }

  @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode == REQUEST_ENABLE_BLUETOOTH) {
      if (resultCode == RESULT_OK) startBleScan();
      else {
        pendingTransfer = false;
        dashboard.setMessage("Bluetooth não foi ativado");
      }
      return;
    }
    if (requestCode != REQUEST_EXPORT_GPX || resultCode != RESULT_OK || data == null
        || data.getData() == null) return;
    Uri destination = data.getData();
    RouteData route = selectedRoute;
    worker.execute(() -> {
      try (OutputStream output = getContentResolver().openOutputStream(destination, "wt")) {
        if (output == null) throw new IllegalStateException("GPX_DESTINATION_UNAVAILABLE");
        output.write(route.bytes);
        main.post(() -> dashboard.setMessage("GPX salvo · " + route.filename));
      } catch (Exception failure) {
        main.post(() -> dashboard.setMessage(readableFailure(failure)));
      }
    });
  }

  private void showBackendDialog() {
    EditText input = new EditText(this);
    input.setHint("https://api.seudominio.com");
    input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
        | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    input.setSingleLine(true);
    input.setText(apiBaseUrl());
    int padding = dp(22);
    FrameLayout container = new FrameLayout(this);
    container.setPadding(padding, dp(4), padding, 0);
    container.addView(input, new FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT));
    AlertDialog dialog = new AlertDialog.Builder(this)
        .setTitle("Servidor seguro do Strava")
        .setMessage("Informe o endereço HTTPS do backend Bike GPS. O segredo OAuth permanece somente nesse servidor.")
        .setView(container)
        .setNegativeButton("Cancelar", null)
        .setPositiveButton("Salvar", null)
        .create();
    dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button -> {
      String value = normalizeUrl(input.getText().toString());
      if (!isSecureApiUrl(value)) {
        input.setError("Use uma URL HTTPS válida");
        return;
      }
      String previous = apiBaseUrl();
      config.edit().putString(API_URL, value).apply();
      if (!value.equals(previous)) clearBackendSession();
      dialog.dismiss();
      onStrava();
    }));
    dialog.show();
    input.requestFocus();
    main.postDelayed(() -> {
      InputMethodManager keyboard = getSystemService(InputMethodManager.class);
      if (keyboard != null) keyboard.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT);
    }, 250);
  }

  private static boolean isSecureApiUrl(String value) {
    try {
      Uri uri = Uri.parse(value);
      return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null
          && !uri.getHost().isBlank() && (uri.getPath() == null || uri.getPath().isEmpty());
    } catch (RuntimeException invalid) { return false; }
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
    String api = apiBaseUrl();
    if (api.isEmpty()) { dashboard.setMessage("Backend OAuth não configurado"); return; }
    dashboard.setMessage("Concluindo conexão segura com o Strava…");
    worker.execute(() -> {
      HttpURLConnection connection = null;
      try {
        connection = (HttpURLConnection) new URL(api + "/oauth/ticket").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(12_000);
        connection.setReadTimeout(12_000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        byte[] body = new JSONObject().put("ticket", ticket).toString().getBytes(StandardCharsets.UTF_8);
        try (OutputStream output = connection.getOutputStream()) { output.write(body); }
        int status = connection.getResponseCode();
        String response = readAll(status >= 200 && status < 300
            ? connection.getInputStream() : connection.getErrorStream());
        if (status != 200) throw new IllegalStateException("OAUTH_TICKET_" + status);
        JSONObject json = new JSONObject(response);
        String newSession = json.getString("sessionToken");
        sessionStore.save(newSession);
        backendSession = newSession;
        String athlete = json.optString("athleteName", "Atleta Strava");
        main.post(() -> {
          dashboard.setStravaConnected(true);
          dashboard.setMessage("Strava conectado · " + athlete);
          loadStravaRoutes();
        });
      } catch (Exception failure) {
        main.post(() -> dashboard.setMessage(readableFailure(failure)));
      } finally {
        if (connection != null) connection.disconnect();
      }
    });
  }

  private void loadStravaRoutes() {
    String session = backendSession;
    if (session == null) { onStrava(); return; }
    dashboard.setMessage("Carregando suas rotas do Strava…");
    worker.execute(() -> {
      try {
        StravaClient.RouteList result = new StravaClient(apiBaseUrl(), session).listRoutes();
        updateBackendSession(result.sessionToken);
        main.post(() -> showRouteChooser(result.routes));
      } catch (Exception failure) {
        if (isInvalidSession(failure)) clearBackendSession();
        main.post(() -> dashboard.setMessage(readableFailure(failure)));
      }
    });
  }

  private void showRouteChooser(List<StravaClient.RouteSummary> routes) {
    if (routes.isEmpty()) {
      dashboard.setMessage("Strava conectado, mas nenhuma rota foi encontrada");
      return;
    }
    String[] labels = new String[routes.size()];
    for (int index = 0; index < routes.size(); index++) {
      StravaClient.RouteSummary route = routes.get(index);
      labels[index] = String.format(Locale.getDefault(), "%s · %.1f km",
          route.name, route.distanceMeters / 1000d);
    }
    new AlertDialog.Builder(this)
        .setTitle("Escolha uma rota do Strava")
        .setItems(labels, (dialog, index) -> downloadStravaRoute(routes.get(index)))
        .setNeutralButton("Configurar servidor", (dialog, which) -> showBackendDialog())
        .setNegativeButton("Cancelar", null)
        .show();
  }

  private void downloadStravaRoute(StravaClient.RouteSummary summary) {
    String session = backendSession;
    if (session == null) return;
    dashboard.setMessage("Baixando GPX · " + summary.name);
    worker.execute(() -> {
      try {
        StravaClient.RouteDownload result = new StravaClient(apiBaseUrl(), session).download(summary);
        updateBackendSession(result.sessionToken);
        RouteData route = result.route;
        main.post(() -> {
          selectedRoute = route;
          routeBadge.setText(route.name);
          mapController.setRoute(route, true);
          dashboard.setMessage(String.format(Locale.getDefault(),
              "Rota pronta · %s · %.1f km", route.name, summary.distanceMeters / 1000d));
        });
      } catch (Exception failure) {
        if (isInvalidSession(failure)) clearBackendSession();
        main.post(() -> dashboard.setMessage(readableFailure(failure)));
      }
    });
  }

  private void clearBackendSession() {
    backendSession = null;
    sessionStore.clear();
    if (dashboard != null) dashboard.post(() -> dashboard.setStravaConnected(false));
  }

  private void updateBackendSession(String value) throws Exception {
    if (value == null || value.isBlank() || value.equals(backendSession)) return;
    sessionStore.save(value);
    backendSession = value;
  }

  private static boolean isInvalidSession(Exception failure) {
    String value = failure.getMessage();
    return value != null && (value.contains("SESSION_INVALID") || value.contains("SESSION_MISSING")
        || value.contains("HTTP_401"));
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

  private String apiBaseUrl() {
    String saved = config.getString(API_URL, "");
    String value = saved == null || saved.isBlank() ? BuildConfig.API_BASE_URL : saved;
    return normalizeUrl(value);
  }

  private static String normalizeUrl(String raw) {
    String value = raw == null ? "" : raw.trim();
    while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
    return value;
  }

  private static String readableFailure(Exception failure) {
    String code = failure.getMessage();
    if (code == null || code.isEmpty()) code = failure.getClass().getSimpleName();
    if (code.contains("SESSION_INVALID") || code.contains("HTTP_401")) {
      return "Sessão Strava expirou · conecte novamente";
    }
    if (failure instanceof java.net.UnknownHostException) {
      return "Servidor indisponível · confira a internet e a URL configurada";
    }
    if (code.contains("GATT_CONNECT_TIMEOUT")) return "Tempo esgotado ao conectar ao Bike GPS";
    if (code.contains("GATT_SERVICE_UNAVAILABLE")) return "Dispositivo sem o serviço Bike GPS v1";
    if (code.contains("CHUNK_FAILED")) return "Envio interrompido · aproxime os aparelhos e tente retomar";
    return "Operação não concluída · " + code.replace('_', ' ').toLowerCase(Locale.ROOT);
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == REQUEST_BLE) {
      if (hasBlePermissions()) startBleScan();
      else {
        pendingTransfer = false;
        dashboard.setMessage("Permissão Dispositivos próximos é necessária para conectar");
      }
    } else if (requestCode == REQUEST_LOCATION) {
      if (hasLocationPermission()) {
        startLocationTracking();
        if (pendingRideStart && hasPreciseLocation()) startRide();
      } else {
        pendingRideStart = false;
        dashboard.setGpsFix(false);
        dashboard.setMessage("Sem localização · permita o acesso nas configurações do Android");
      }
    }
  }

  @Override protected void onStart() {
    super.onStart();
    mapController.onStart();
  }

  @Override protected void onResume() {
    super.onResume();
    mapController.onResume();
    if (hasLocationPermission()) startLocationTracking();
  }

  @Override protected void onPause() {
    if (locationManager != null) locationManager.removeUpdates(this);
    mapController.onPause();
    super.onPause();
  }

  @Override protected void onStop() {
    mapController.onStop();
    super.onStop();
  }

  @Override public void onLowMemory() {
    super.onLowMemory();
    mapController.onLowMemory();
  }

  @Override protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    mapController.onSaveInstanceState(state);
  }

  @Override protected void onDestroy() {
    transferCancelled = true;
    stopScan();
    if (locationManager != null) locationManager.removeUpdates(this);
    if (transport != null) transport.close();
    worker.shutdownNow();
    mapController.onDestroy();
    super.onDestroy();
  }

  private int dp(int value) {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }
}
