package com.bikegps.companion;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public final class MainActivity extends Activity {
  private static final int BLE_PERMISSION_REQUEST = 42;
  private final Handler handler = new Handler(Looper.getMainLooper());
  private TextView status;
  private TextView device;
  private ProgressBar progress;
  private BluetoothLeScanner scanner;
  private final ScanCallback scanCallback = new ScanCallback() {
    @Override public void onScanResult(int callbackType, ScanResult result) {
      if (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
        String name = result.getDevice().getName();
        if (name != null && name.toLowerCase().contains("bike gps")) {
          device.setText(name + " • " + result.getRssi() + " dBm");
          status.setText("Ciclocomputador encontrado");
          stopScan();
        }
      }
    }
  };

  @Override protected void onCreate(Bundle state) {
    super.onCreate(state);
    setContentView(buildInterface());
  }

  private View buildInterface() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setPadding(dp(24), dp(38), dp(24), dp(24));
    root.setBackgroundColor(Color.rgb(7, 16, 14));

    TextView brand = label("BIKE GPS", 14, Color.rgb(200, 255, 40));
    brand.setTypeface(Typeface.DEFAULT_BOLD);
    root.addView(brand);

    TextView title = label("Pronto para pedalar?", 32, Color.WHITE);
    title.setTypeface(Typeface.DEFAULT_BOLD);
    title.setPadding(0, dp(20), 0, dp(6));
    root.addView(title);
    root.addView(label("Conecte o ciclocomputador e envie sua rota.", 16, Color.rgb(174, 190, 184)));

    device = label("Nenhum dispositivo conectado", 17, Color.WHITE);
    device.setPadding(dp(18), dp(24), dp(18), dp(24));
    LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
    cardParams.setMargins(0, dp(30), 0, dp(12));
    root.addView(device, cardParams);

    Button scan = button("PROCURAR BIKE GPS");
    scan.setOnClickListener(v -> requestAndScan());
    root.addView(scan, new LinearLayout.LayoutParams(-1, dp(58)));

    Button strava = button("CONECTAR STRAVA");
    LinearLayout.LayoutParams secondary = new LinearLayout.LayoutParams(-1, dp(58));
    secondary.setMargins(0, dp(12), 0, 0);
    strava.setOnClickListener(v -> status.setText("Configure STRAVA_CLIENT_ID no backend para autenticação real"));
    root.addView(strava, secondary);

    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgress(0);
    LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(8));
    progressParams.setMargins(0, dp(28), 0, dp(12));
    root.addView(progress, progressParams);

    status = label("Sistema pronto", 15, Color.rgb(174, 190, 184));
    status.setGravity(Gravity.CENTER);
    root.addView(status, new LinearLayout.LayoutParams(-1, -2));

    return root;
  }

  private void requestAndScan() {
    if (Build.VERSION.SDK_INT >= 31 &&
        (checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED ||
         checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) {
      requestPermissions(new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT}, BLE_PERMISSION_REQUEST);
      return;
    }
    startScan();
  }

  @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (requestCode == BLE_PERMISSION_REQUEST && results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) startScan();
    else status.setText("Permissão Bluetooth necessária");
  }

  private void startScan() {
    BluetoothManager manager = getSystemService(BluetoothManager.class);
    BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
    if (adapter == null || !adapter.isEnabled()) { status.setText("Ative o Bluetooth"); return; }
    scanner = adapter.getBluetoothLeScanner();
    status.setText("Procurando ciclocomputador…");
    scanner.startScan(scanCallback);
    handler.postDelayed(this::stopScan, 10_000);
  }

  private void stopScan() {
    if (scanner != null && (Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED)) {
      scanner.stopScan(scanCallback);
      scanner = null;
    }
  }

  private Button button(String text) {
    Button button = new Button(this);
    button.setText(text);
    button.setTextSize(15);
    button.setTypeface(Typeface.DEFAULT_BOLD);
    button.setTextColor(Color.rgb(7, 16, 14));
    button.setBackgroundColor(Color.rgb(200, 255, 40));
    return button;
  }

  private TextView label(String value, int size, int color) {
    TextView view = new TextView(this);
    view.setText(value);
    view.setTextSize(size);
    view.setTextColor(color);
    return view;
  }

  private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}

