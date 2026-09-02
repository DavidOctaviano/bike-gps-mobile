package com.bikegps.companion;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import java.util.Locale;

/** Single-screen cockpit optimized for starting a ride with one prominent action. */
public final class RideDashboardView extends View {
  public interface Actions {
    void onRideToggle();
    void onBle();
    void onStrava();
    void onTransferDemo();
  }

  private static final int BACKGROUND = Color.rgb(5, 13, 12);
  private static final int PANEL = Color.rgb(12, 27, 24);
  private static final int LIME = Color.rgb(204, 255, 51);
  private static final int CYAN = Color.rgb(65, 225, 217);
  private static final int MUTED = Color.rgb(148, 169, 162);
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Path routePath = new Path();
  private final RectF rideButton = new RectF();
  private final RectF bleButton = new RectF();
  private final RectF stravaButton = new RectF();
  private final RectF transferButton = new RectF();
  private Actions actions;
  private boolean riding;
  private boolean bleConnected;
  private boolean stravaConnected;
  private double averageKph;
  private double liveKph;
  private double distanceKm;
  private long startedAt;
  private String message = "Tudo pronto para o próximo pedal";
  private float transferProgress = -1f;

  public RideDashboardView(Context context) {
    super(context);
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setMinimumHeight((int) dp(740));
    setContentDescription("Painel Bike GPS. Rota demonstração Lagoa da Prata.");
  }

  public void setActions(Actions value) { actions = value; }
  public void setConnection(boolean connected) { bleConnected = connected; invalidate(); }
  public void setStravaConnected(boolean connected) { stravaConnected = connected; invalidate(); }
  public void setMessage(String value) { message = value; invalidate(); }
  public void setTransferProgress(float value) { transferProgress = value; invalidate(); }
  public boolean isRiding() { return riding; }

  public void setRiding(boolean value) {
    riding = value;
    if (value) startedAt = SystemClock.elapsedRealtime();
    else { liveKph = 0; startedAt = 0; }
    invalidate();
  }

  public void updateRide(double average, double live, double distance) {
    averageKph = average;
    liveKph = live;
    distanceKm = distance;
    invalidate();
  }

  @Override protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.drawColor(BACKGROUND);
    float width = getWidth();
    float margin = dp(20);
    drawHeader(canvas, margin, width);
    drawMap(canvas, margin, dp(112), width - margin, dp(424));
    drawMetrics(canvas, margin, dp(442), width - margin, dp(576));
    drawActions(canvas, margin, dp(594), width - margin);
  }

  private void drawHeader(Canvas canvas, float margin, float width) {
    text(canvas, "BIKE GPS", margin, dp(42), dp(14), LIME, true);
    text(canvas, riding ? "PEDAL EM CURSO" : "PRONTO PARA PEDALAR?", margin, dp(77), dp(24), Color.WHITE, true);
    text(canvas, message, margin, dp(101), dp(12), MUTED, false);
    statusPill(canvas, width - dp(146), dp(24), "GPS", riding, CYAN);
    statusPill(canvas, width - dp(82), dp(24), "BLE", bleConnected, LIME);
  }

  private void drawMap(Canvas canvas, float left, float top, float right, float bottom) {
    roundPanel(canvas, left, top, right, bottom, dp(24), PANEL);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    paint.setColor(Color.rgb(25, 48, 43));
    for (int column = 1; column < 6; column++) {
      float x = left + (right - left) * column / 6f;
      canvas.drawLine(x, top + dp(18), x, bottom - dp(18), paint);
    }
    for (int row = 1; row < 5; row++) {
      float y = top + (bottom - top) * row / 5f;
      canvas.drawLine(left + dp(18), y, right - dp(18), y, paint);
    }

    float[][] route = {
        {.12f, .49f}, {.22f, .23f}, {.45f, .16f}, {.70f, .28f}, {.84f, .51f},
        {.72f, .78f}, {.45f, .84f}, {.22f, .72f}, {.12f, .49f}
    };
    routePath.reset();
    for (int index = 0; index < route.length; index++) {
      float x = left + route[index][0] * (right - left);
      float y = top + route[index][1] * (bottom - top);
      if (index == 0) routePath.moveTo(x, y); else routePath.lineTo(x, y);
    }
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.ROUND);
    paint.setStrokeJoin(Paint.Join.ROUND);
    paint.setStrokeWidth(dp(10));
    paint.setColor(Color.argb(45, 204, 255, 51));
    canvas.drawPath(routePath, paint);
    paint.setStrokeWidth(dp(4));
    paint.setShader(new LinearGradient(left, top, right, bottom, CYAN, LIME, Shader.TileMode.CLAMP));
    canvas.drawPath(routePath, paint);
    paint.setShader(null);

    float centerX = (left + right) / 2f;
    float centerY = (top + bottom) / 2f + dp(4);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(Color.argb(220, 5, 13, 12));
    canvas.drawCircle(centerX, centerY, dp(38), paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(2));
    paint.setColor(LIME);
    canvas.drawCircle(centerX, centerY, dp(38), paint);
    Path arrow = new Path();
    arrow.moveTo(centerX, centerY - dp(20));
    arrow.lineTo(centerX + dp(15), centerY + dp(17));
    arrow.lineTo(centerX, centerY + dp(9));
    arrow.lineTo(centerX - dp(15), centerY + dp(17));
    arrow.close();
    paint.setStyle(Paint.Style.FILL);
    canvas.drawPath(arrow, paint);

    text(canvas, "ROTA DEMONSTRAÇÃO", left + dp(18), top + dp(28), dp(10), MUTED, true);
    text(canvas, "Lagoa da Prata · MG", left + dp(18), top + dp(50), dp(16), Color.WHITE, true);
    text(canvas, "8,4 km  •  circuito urbano", left + dp(18), bottom - dp(18), dp(11), MUTED, false);
  }

  private void drawMetrics(Canvas canvas, float left, float top, float right, float bottom) {
    roundPanel(canvas, left, top, right, bottom, dp(20), Color.rgb(9, 22, 20));
    text(canvas, "VELOCIDADE MÉDIA", left + dp(18), top + dp(27), dp(10), MUTED, true);
    text(canvas, String.format(Locale.getDefault(), "%.1f", averageKph), left + dp(18), top + dp(87), dp(52), Color.WHITE, true);
    text(canvas, "km/h", left + dp(116), top + dp(84), dp(12), LIME, true);
    text(canvas, "AGORA", left + dp(190), top + dp(26), dp(9), MUTED, true);
    text(canvas, String.format(Locale.getDefault(), "%.1f km/h", liveKph), left + dp(190), top + dp(52), dp(16), Color.WHITE, true);
    text(canvas, "DISTÂNCIA", left + dp(190), top + dp(78), dp(9), MUTED, true);
    text(canvas, String.format(Locale.getDefault(), "%.2f km", distanceKm), left + dp(190), top + dp(104), dp(16), Color.WHITE, true);
    String elapsed = "00:00";
    if (riding && startedAt > 0) {
      long seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000;
      elapsed = String.format(Locale.getDefault(), "%02d:%02d", seconds / 60, seconds % 60);
      postInvalidateDelayed(1_000);
    }
    text(canvas, elapsed, right - dp(64), top + dp(27), dp(10), CYAN, true);
  }

  private void drawActions(Canvas canvas, float left, float top, float right) {
    rideButton.set(left, top, right, top + dp(62));
    roundPanel(canvas, rideButton.left, rideButton.top, rideButton.right, rideButton.bottom, dp(18), riding ? Color.rgb(255, 111, 82) : LIME);
    textCentered(canvas, riding ? "ENCERRAR PEDAL" : "INICIAR PEDAL", rideButton.centerX(), rideButton.centerY() + dp(5), dp(15), BACKGROUND, true);

    float gap = dp(9);
    float itemWidth = (right - left - gap * 2) / 3f;
    float secondTop = top + dp(74);
    bleButton.set(left, secondTop, left + itemWidth, secondTop + dp(50));
    stravaButton.set(bleButton.right + gap, secondTop, bleButton.right + gap + itemWidth, secondTop + dp(50));
    transferButton.set(stravaButton.right + gap, secondTop, right, secondTop + dp(50));
    outlineButton(canvas, bleButton, bleConnected ? "BLE OK" : "CONECTAR", bleConnected ? LIME : MUTED);
    outlineButton(canvas, stravaButton, stravaConnected ? "STRAVA OK" : "STRAVA", stravaConnected ? CYAN : MUTED);
    outlineButton(canvas, transferButton, "ENVIAR ROTA", LIME);
    if (transferProgress >= 0) {
      float progressRight = left + (right - left) * Math.max(0, Math.min(1, transferProgress));
      paint.setColor(LIME);
      paint.setStrokeWidth(dp(3));
      canvas.drawLine(left, secondTop + dp(58), progressRight, secondTop + dp(58), paint);
    }
  }

  private void outlineButton(Canvas canvas, RectF bounds, String label, int color) {
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(PANEL);
    canvas.drawRoundRect(bounds, dp(14), dp(14), paint);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(dp(1));
    paint.setColor(Color.rgb(39, 65, 59));
    canvas.drawRoundRect(bounds, dp(14), dp(14), paint);
    textCentered(canvas, label, bounds.centerX(), bounds.centerY() + dp(4), dp(10), color, true);
  }

  private void statusPill(Canvas canvas, float left, float top, String label, boolean active, int activeColor) {
    RectF bounds = new RectF(left, top, left + dp(54), top + dp(26));
    roundPanel(canvas, bounds.left, bounds.top, bounds.right, bounds.bottom, dp(13), PANEL);
    paint.setColor(active ? activeColor : Color.rgb(75, 91, 86));
    paint.setStyle(Paint.Style.FILL);
    canvas.drawCircle(left + dp(12), top + dp(13), dp(3), paint);
    text(canvas, label, left + dp(20), top + dp(17), dp(9), active ? Color.WHITE : MUTED, true);
  }

  private void roundPanel(Canvas canvas, float left, float top, float right, float bottom, float radius, int color) {
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(color);
    canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
  }

  private void text(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
    paint.setStyle(Paint.Style.FILL);
    paint.setShader(null);
    paint.setTextSize(size);
    paint.setColor(color);
    paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    paint.setTextAlign(Paint.Align.LEFT);
    canvas.drawText(value, x, y, paint);
  }

  private void textCentered(Canvas canvas, String value, float x, float y, float size, int color, boolean bold) {
    paint.setTextAlign(Paint.Align.CENTER);
    paint.setTextSize(size);
    paint.setColor(color);
    paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
    canvas.drawText(value, x, y, paint);
    paint.setTextAlign(Paint.Align.LEFT);
  }

  @Override public boolean onTouchEvent(MotionEvent event) {
    if (event.getAction() != MotionEvent.ACTION_UP) return true;
    if (actions == null) return true;
    float x = event.getX(), y = event.getY();
    if (rideButton.contains(x, y)) actions.onRideToggle();
    else if (bleButton.contains(x, y)) actions.onBle();
    else if (stravaButton.contains(x, y)) actions.onStrava();
    else if (transferButton.contains(x, y)) actions.onTransferDemo();
    performClick();
    return true;
  }

  @Override public boolean performClick() { super.performClick(); return true; }
  private float dp(float value) { return value * getResources().getDisplayMetrics().density; }
}
