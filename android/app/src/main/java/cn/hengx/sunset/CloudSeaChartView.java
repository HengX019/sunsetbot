package cn.hengx.sunset;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Native hourly weather plots. Place inside a HorizontalScrollView to view the full day. */
public final class CloudSeaChartView extends View {
  public static final int MIN_WIDTH_DP = 720;
  public static final int HEIGHT_DP = 410;

  private static final int BACKGROUND = 0xfff7fafc;
  private static final int TEXT = 0xff263c52;
  private static final int MUTED = 0xff586a7a;
  private static final int GRID = 0xffd7e0e8;
  private static final int RED = 0xffed554e;
  private static final int PURPLE = 0xff9b6dc5;
  private static final int CYAN = 0xff2da5b3;
  private static final int BLUE = 0xff277fe0;
  private static final float LEFT = 52, RIGHT = 666;

  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final float density;
  private List<CloudSeaForecast.Hour> rows = Collections.emptyList();
  private ZoneId zone = ZoneId.of("Asia/Shanghai");

  public CloudSeaChartView(Context context) {
    super(context);
    density = getResources().getDisplayMetrics().density;
    setMinimumWidth(Math.round(MIN_WIDTH_DP * density));
    setMinimumHeight(Math.round(HEIGHT_DP * density));
    setBackgroundColor(BACKGROUND);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    describe();
  }

  public void setRows(List<CloudSeaForecast.Hour> hours, ZoneId timezone) {
    rows = hours == null ? Collections.emptyList() : new ArrayList<>(hours);
    zone = timezone == null ? ZoneId.of("Asia/Shanghai") : timezone;
    describe();
    invalidate();
  }

  private void describe() {
    setContentDescription(
        rows.isEmpty()
            ? "暂无逐小时图表数据"
            : "逐小时气温、低云量、湿度、降水和风速趋势，共"
                + rows.size()
                + "个时段，时间为"
                + zone.getId()
                + "。可左右滑动，具体数值见下方表格；低云量表示覆盖比例，不表示云层高度。");
  }

  @Override
  protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
    setMeasuredDimension(
        resolveSize(Math.round(MIN_WIDTH_DP * density), widthMeasureSpec),
        resolveSize(Math.round(HEIGHT_DP * density), heightMeasureSpec));
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    canvas.save();
    canvas.scale(getWidth() / 720f, getHeight() / 410f);
    if (rows.isEmpty()) {
      text(canvas, "暂无图表数据", 24, 35, MUTED, 13, Paint.Align.LEFT, false);
      canvas.restore();
      return;
    }

    double[] temperature = range(0), wind = range(3), rain = range(4);
    double temperatureMin = temperature == null ? 0 : Math.floor(temperature[0] / 5) * 5;
    double temperatureMax =
        temperature == null ? 5 : Math.max(temperatureMin + 5, Math.ceil(temperature[1] / 5) * 5);
    double windMax = wind == null ? 2 : Math.max(2, Math.ceil(wind[1] / 2) * 2);
    double rainMax = rain == null ? 1 : Math.max(1, Math.ceil(rain[1]));

    panel(canvas, 22, 125, "气温 (°C)", temperatureMin, temperatureMax);
    drawSeries(canvas, 0, RED, 22, 125, temperatureMin, temperatureMax);
    if (temperature == null) missing(canvas, "气温数据缺测", 78);

    panel(canvas, 158, 261, "低云量 / 湿度 (%)", 0, 100);
    text(canvas, "降水右轴 (mm)", RIGHT, 151, BLUE, 10, Paint.Align.RIGHT, false);
    if (rain != null) {
      canvas.save();
      canvas.clipRect(LEFT - 5, 158, RIGHT + 5, 261);
      paint.setColor(0x593c89e7);
      paint.setStyle(Paint.Style.FILL);
      for (int i = 0; i < rows.size(); i++) {
        double value = rows.get(i).rain;
        if (!Double.isFinite(value)) continue;
        float height = (float) (Math.max(0, value) / rainMax * 103);
        canvas.drawRect(x(i) - 4, 261 - height, x(i) + 4, 261, paint);
      }
      canvas.restore();
      for (int step = 0; step <= 4; step++) {
        float y = 261 - step * 103 / 4f;
        text(
            canvas,
            number(rainMax * step / 4),
            RIGHT + 8,
            y + 3,
            BLUE,
            10,
            Paint.Align.LEFT,
            false);
      }
    } else {
      text(canvas, "降水缺测", RIGHT, 169, BLUE, 10, Paint.Align.RIGHT, false);
    }
    drawSeries(canvas, 1, PURPLE, 158, 261, 0, 100);
    drawSeries(canvas, 2, CYAN, 158, 261, 0, 100);
    boolean lowMissing = range(1) == null, humidityMissing = range(2) == null;
    if (lowMissing || humidityMissing) {
      String label =
          lowMissing && humidityMissing ? "低云量、湿度数据缺测" : lowMissing ? "低云量数据缺测" : "湿度数据缺测";
      missing(canvas, label, 214);
    }

    panel(canvas, 294, 385, "风速 (m/s)", 0, windMax);
    drawSeries(canvas, 3, BLUE, 294, 385, 0, windMax);
    if (wind == null) missing(canvas, "风速数据缺测", 344);
    canvas.restore();
  }

  private void panel(Canvas canvas, float top, float bottom, String title, double min, double max) {
    text(canvas, title, LEFT, top - 7, TEXT, 13, Paint.Align.LEFT, true);
    for (int step = 0; step <= 4; step++) {
      double value = min + (max - min) * step / 4;
      float y = bottom - (bottom - top) * step / 4;
      line(canvas, LEFT, y, RIGHT, y, GRID, 1);
      text(canvas, number(value), LEFT - 7, y + 3, MUTED, 10, Paint.Align.RIGHT, false);
    }
    for (int i = 0; i < rows.size(); i++) {
      if (i % 3 != 0 && i != rows.size() - 1) continue;
      // The final hour may be close to the preceding tick on days with more than 24 hours.
      if (i == rows.size() - 1 && i % 3 == 1) continue;
      float x = x(i);
      line(canvas, x, top, x, bottom, 0xffe7edf2, 1);
      text(
          canvas,
          rows.get(i).timeLabel(zone),
          x,
          bottom + 14,
          MUTED,
          10,
          Paint.Align.CENTER,
          false);
    }
  }

  private void drawSeries(
      Canvas canvas, int field, int color, float top, float bottom, double min, double max) {
    canvas.save();
    canvas.clipRect(LEFT - 3, top - 3, RIGHT + 3, bottom + 3);
    Path path = new Path();
    int points = 0;
    float lastX = 0, lastY = 0;
    for (int i = 0; i <= rows.size(); i++) {
      double value = i == rows.size() ? Double.NaN : value(rows.get(i), field);
      if (!Double.isFinite(value)) {
        drawSegment(canvas, path, points, lastX, lastY, color);
        path.reset();
        points = 0;
        continue;
      }
      value = Math.max(min, Math.min(max, value));
      float x = x(i), y = bottom - (float) ((value - min) / (max - min)) * (bottom - top);
      if (points == 0) path.moveTo(x, y);
      else path.lineTo(x, y);
      points++;
      lastX = x;
      lastY = y;
    }
    canvas.restore();
  }

  private void drawSegment(
      Canvas canvas, Path path, int count, float lastX, float lastY, int color) {
    if (count == 0) return;
    paint.setColor(color);
    if (count == 1) {
      paint.setStyle(Paint.Style.FILL);
      canvas.drawCircle(lastX, lastY, 2.5f, paint);
    } else {
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeWidth(2.5f);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      canvas.drawPath(path, paint);
    }
  }

  private double[] range(int field) {
    double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
    for (CloudSeaForecast.Hour hour : rows) {
      double value = value(hour, field);
      if (!Double.isFinite(value)) continue;
      min = Math.min(min, value);
      max = Math.max(max, value);
    }
    return min == Double.POSITIVE_INFINITY ? null : new double[] {min, max};
  }

  private static double value(CloudSeaForecast.Hour hour, int field) {
    switch (field) {
      case 0:
        return hour.temperature;
      case 1:
        return hour.low;
      case 2:
        return hour.humidity;
      case 3:
        return hour.wind;
      default:
        return hour.rain;
    }
  }

  private float x(int index) {
    return LEFT
        + (rows.size() == 1 ? (RIGHT - LEFT) / 2 : index * (RIGHT - LEFT) / (rows.size() - 1));
  }

  private void missing(Canvas canvas, String label, float y) {
    text(canvas, label, (LEFT + RIGHT) / 2, y, MUTED, 12, Paint.Align.CENTER, false);
  }

  private void line(Canvas canvas, float x1, float y1, float x2, float y2, int color, float width) {
    paint.setColor(color);
    paint.setStrokeWidth(width);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeCap(Paint.Cap.BUTT);
    canvas.drawLine(x1, y1, x2, y2, paint);
  }

  private void text(
      Canvas canvas,
      String text,
      float x,
      float y,
      int color,
      float size,
      Paint.Align align,
      boolean bold) {
    paint.setColor(color);
    paint.setStyle(Paint.Style.FILL);
    paint.setTextSize(size);
    paint.setTextAlign(align);
    paint.setFakeBoldText(bold);
    canvas.drawText(text, x, y, paint);
    paint.setFakeBoldText(false);
  }

  private static String number(double value) {
    double rounded = Math.rint(value * 10) / 10;
    return rounded == Math.rint(rounded)
        ? String.format(Locale.ROOT, "%.0f", rounded)
        : String.format(Locale.ROOT, "%.1f", rounded);
  }
}
