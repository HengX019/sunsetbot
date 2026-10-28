package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import cn.hengx.sunset.ForecastEngine.Point;
import java.util.*;

public final class ForecastCharts extends View {
  private final Forecast f;
  private final boolean section;
  private final Paint p = new Paint(3);
  private final float d;

  public ForecastCharts(Context c) {
    this(c, null, false);
  }

  public ForecastCharts(Context c, Forecast data, boolean crossSection) {
    super(c);
    f = data;
    section = crossSection;
    d = getResources().getDisplayMetrics().density;
    setContentDescription(crossSection ? "太阳方向云层大气截面示意图" : "日出日落前后云量变化曲线");
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(0xff16273d);
    if (f == null) return;
    if (section) cross(c);
    else timeline(c);
  }

  private void text(Canvas c, String t, float x, float y, int color) {
    p.setColor(color);
    p.setTextSize(10 * d);
    p.setStyle(Paint.Style.FILL);
    c.drawText(t, x, y, p);
  }

  private void timeline(Canvas c) {
    float l = 32 * d, r = getWidth() - 12 * d, t = 24 * d, b = getHeight() - 34 * d;
    p.setStrokeWidth(d);
    for (int v = 0; v <= 100; v += 25) {
      float y = b - (b - t) * v / 100;
      p.setColor(Color.rgb(56, 72, 89));
      c.drawLine(l, y, r, y, p);
      text(c, "" + v, 3 * d, y + 3 * d, 0xffaeb9c9);
    }
    int[] colors = {0xff8fc5df, 0xffbb9dec, 0xffed9bc5};
    for (int k = 0; k < 3; k++) {
      Path path = new Path();
      for (int i = 0; i < f.points.size(); i++) {
        Cloud cloud = f.points.get(i).cloud;
        double v = k == 0 ? cloud.low : k == 1 ? cloud.mid : cloud.high;
        float x = l + (r - l) * i / 20, y = b - (float) v * (b - t);
        if (i == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      p.setStyle(Paint.Style.STROKE);
      p.setStrokeWidth(2 * d);
      p.setColor(colors[k]);
      c.drawPath(path, p);
      text(c, new String[] {"低云", "中云", "高云"}[k], l + k * 65 * d, 14 * d, colors[k]);
    }
    text(c, "−50 分", l, b + 20 * d, 0xffaeb9c9);
    text(c, "日出 / 日落", (l + r) / 2 - 28 * d, b + 20 * d, 0xffaeb9c9);
    text(c, "+50 分", r - 35 * d, b + 20 * d, 0xffaeb9c9);
  }

  private void cross(Canvas c) {
    float l = 28 * d, r = getWidth() - 12 * d, top = 25 * d, bottom = getHeight() - 30 * d;
    for (int h = 0; h <= 12; h += 3) {
      float y = bottom - (bottom - top) * h / 12;
      p.setColor(0xff384859);
      p.setStrokeWidth(d);
      c.drawLine(l, y, r, y, p);
      text(c, h + " km", 2 * d, y, 0xffaeb9c9);
    }
    Point peak = f.peak;
    for (int i = 0; i < 5; i++) {
      float x = l + (r - l) * DISTANCES[i] / 600f;
      Cloud cl = peak.far[i];
      double[] values = {cl.low, cl.mid, cl.high};
      for (int k = 0; k < 3; k++) {
        float h = new float[] {1, 4, 9}[k], y = bottom - (bottom - top) * h / 12;
        p.setColor(Color.argb((int) (30 + 160 * values[k]), 184, 169, 220));
        c.drawRoundRect(x - 14 * d, y - 5 * d, x + 14 * d, y + 5 * d, 5 * d, 5 * d, p);
      }
      text(c, "" + DISTANCES[i], x - 10 * d, bottom + 19 * d, 0xffaeb9c9);
    }
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(2 * d);
    p.setColor(0xffefb16d);
    for (int height : new int[] {4, 9}) {
      Path path = new Path();
      for (int km = 0; km <= 600; km += 10) {
        double z = height + km * Math.tan(rad(peak.alt)) + km * km / (2 * 6371.0);
        float x = l + (r - l) * km / 600, y = bottom - (float) z / 12 * (bottom - top);
        if (km == 0) path.moveTo(x, y);
        else path.lineTo(x, y);
      }
      c.drawPath(path, p);
    }
    p.setStyle(Paint.Style.FILL);
    text(c, "太阳方向 · 近似光路，不含真实地形遮挡", l, 14 * d, 0xffb9c7d9);
  }
}
