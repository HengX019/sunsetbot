package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;
import static cn.hengx.sunset.ForecastRepository.*;

import android.content.*;
import android.graphics.*;
import android.util.*;
import android.view.*;
import cn.hengx.sunset.ForecastRepository.Region;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Native Canvas geography, continuous forecast raster, contours, touch pan and pinch zoom. */
public final class ForecastMapView extends View {
  public interface Listener {
    void changed(Bounds b);

    void point(double lat, double lon);
  }

  private static volatile List<double[]> geography = Collections.emptyList();

  public static void loadGeography(Context c) throws Exception {
    JSONObject j = new JSONObject(CityStore.read(c, "offlineMapData.json"));
    JSONArray fs = j.getJSONArray("features");
    List<double[]> list = new ArrayList<>();
    for (int i = 0; i < fs.length(); i++) {
      JSONObject g = fs.getJSONObject(i).getJSONObject("geometry");
      JSONArray a = g.getJSONArray("coordinates");
      if (g.getString("type").equals("Polygon")) addPolygon(a, list);
      else for (int k = 0; k < a.length(); k++) addPolygon(a.getJSONArray(k), list);
    }
    JSONObject provinces = new JSONObject(CityStore.read(c, "provinceMapData.json"));
    JSONArray pf = provinces.getJSONArray("features");
    for (int k = 0; k < pf.length(); k++) {
      JSONObject g = pf.getJSONObject(k).getJSONObject("geometry");
      JSONArray a = g.getJSONArray("coordinates");
      if (g.getString("type").equals("Polygon")) addPolygon(a, list);
      else
        for (int ringIndex = 0; ringIndex < a.length(); ringIndex++)
          addPolygon(a.getJSONArray(ringIndex), list);
    }
    geography = list;
  }

  private static void addPolygon(JSONArray polygon, List<double[]> list) throws Exception {
    for (int j = 0; j < polygon.length(); j++) {
      JSONArray ring = polygon.getJSONArray(j);
      double[] a = new double[ring.length() * 2];
      for (int i = 0; i < ring.length(); i++) {
        JSONArray p = ring.getJSONArray(i);
        a[i * 2] = (p.getDouble(0) + 180) / 360;
        a[i * 2 + 1] = (1 - merc(p.getDouble(1)) / Math.PI) / 2;
      }
      list.add(a);
    }
  }

  private final Paint paint = new Paint(3);
  private final Path landPath = new Path();
  private final RectF imageBounds = new RectF();
  private final float density;
  private double centerX = (121.47 + 180) / 360,
      centerY = (1 - merc(31.23) / Math.PI) / 2,
      zoom = 6;
  private final ScaleGestureDetector scale;
  private final GestureDetector gestures;
  public boolean interactive = true, showColors = true, roads = false;
  public String emptyMessage = "正在读取周边云况…";
  public float overlayOpacity = .70f;
  public Listener listener;
  public Region region;
  private Bitmap raster;
  private float lastX, lastY, downX, downY;
  private boolean moved;
  private final ExecutorService tilesPool = Executors.newFixedThreadPool(2);
  private final Map<String, Bitmap> tiles = Collections.synchronizedMap(new LinkedHashMap<>());
  private final Set<String> pending = ConcurrentHashMap.newKeySet();
  private final Map<String, Long> failed = new ConcurrentHashMap<>();
  private final Network.Job tileJob = new Network.Job();
  public CityStore cities;

  public ForecastMapView(Context c) {
    super(c);
    density = getResources().getDisplayMetrics().density;
    setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    setContentDescription("朝晚霞区域预报地图，可拖动和双指缩放");
    setFocusable(true);
    scale =
        new ScaleGestureDetector(
            c,
            new ScaleGestureDetector.SimpleOnScaleGestureListener() {
              @Override
              public boolean onScale(ScaleGestureDetector d) {
                double old = world(),
                    x = centerX + (d.getFocusX() - getWidth() / 2f) / old,
                    y = centerY + (d.getFocusY() - getHeight() / 2f) / old;
                zoom = clamp(zoom + Math.log(d.getScaleFactor()) / Math.log(2), 2, 11);
                centerX = x - (d.getFocusX() - getWidth() / 2f) / world();
                centerY = y - (d.getFocusY() - getHeight() / 2f) / world();
                constrain();
                moved = true;
                invalidate();
                return true;
              }
            });
    gestures =
        new GestureDetector(
            c,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onDoubleTap(MotionEvent e) {
                zoomBy(1);
                return true;
              }
            });
  }

  private double world() {
    return 256 * density * Math.pow(2, zoom);
  }

  private void constrain() {
    centerX = (centerX % 1 + 1) % 1;
    centerY = clamp(centerY, (1 - merc(70) / Math.PI) / 2, (1 - merc(-70) / Math.PI) / 2);
  }

  public void setCenter(double lat, double lon, double z) {
    centerX = (wrap(lon) + 180) / 360;
    centerY = (1 - merc(lat) / Math.PI) / 2;
    zoom = clamp(z, 2, 11);
    constrain();
    invalidate();
  }

  public double latitude() {
    return invMerc((1 - 2 * centerY) * Math.PI);
  }

  public double longitude() {
    return wrap(centerX * 360 - 180);
  }

  public void zoomBy(double d) {
    zoom = clamp(zoom + d, 2, 11);
    invalidate();
    notifyBounds();
  }

  public Bounds bounds() {
    double w = world(),
        west = (centerX - getWidth() / 2 / w) * 360 - 180,
        east = (centerX + getWidth() / 2 / w) * 360 - 180;
    if (east - west > 360) {
      west = -180;
      east = 180;
    }
    return new Bounds(
        west,
        east,
        invMerc((1 - 2 * (centerY + getHeight() / 2 / w)) * Math.PI),
        invMerc((1 - 2 * (centerY - getHeight() / 2 / w)) * Math.PI));
  }

  public void notifyBounds() {
    if (listener != null && getWidth() > 0 && getHeight() > 0) listener.changed(bounds());
  }

  public void setRegion(Region r) {
    region = r;
    if (raster != null) raster.recycle();
    raster = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888);
    int[] pixels = new int[128 * 128];
    for (int y = 0; y < 128; y++)
      for (int x = 0; x < 128; x++) {
        double v = value(r.scores, x / 127.0, 1 - y / 127.0);
        if (Double.isFinite(v) && v >= 15) {
          double t = clamp((v - 15) / 65, 0, 1);
          pixels[y * 128 + x] =
              Color.argb(
                  (int) (70 + 150 * t),
                  (int) (215 - 25 * t),
                  (int) (145 - 90 * t),
                  (int) (221 - 35 * t));
        }
      }
    raster.setPixels(pixels, 0, 128, 0, 0, 128, 128);
    invalidate();
  }

  public static double value(double[] values, double x, double y) {
    if (x < 0 || x > 1 || y < 0 || y > 1) return Double.NaN;
    double a = x * 6, b = y * 6;
    int i = Math.min(5, (int) a), j = Math.min(5, (int) b);
    double fx = a - i, fy = b - j;
    double[] weights = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
    int[] ids = {j * 7 + i, j * 7 + i + 1, (j + 1) * 7 + i, (j + 1) * 7 + i + 1};
    double out = 0;
    for (int q = 0; q < 4; q++) {
      if (weights[q] < 1e-8) continue;
      if (!Double.isFinite(values[ids[q]])) return Double.NaN;
      out += values[ids[q]] * weights[q];
    }
    return out;
  }

  private float px(double lon) {
    double x = (lon + 180) / 360;
    while (x - centerX > .5) x--;
    while (x - centerX < -.5) x++;
    return (float) ((x - centerX) * world() + getWidth() / 2.0);
  }

  private float py(double lat) {
    return (float) (((1 - merc(lat) / Math.PI) / 2 - centerY) * world() + getHeight() / 2.0);
  }

  @Override
  protected void onDraw(Canvas c) {
    super.onDraw(c);
    c.drawColor(interactive ? 0xffe9eff3 : 0xfff8f7f5);
    double w = world();
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xfffaf9f6);
    for (double[] ring : geography) {
      Path p = landPath;
      p.reset();
      for (int i = 0; i < ring.length; i += 2) {
        double x = ring[i];
        while (x - centerX > .5) x--;
        while (x - centerX < -.5) x++;
        float sx = (float) ((x - centerX) * w + getWidth() / 2.0),
            sy = (float) ((ring[i + 1] - centerY) * w + getHeight() / 2.0);
        if (i == 0) p.moveTo(sx, sy);
        else p.lineTo(sx, sy);
      }
      p.close();
      paint.setStyle(Paint.Style.FILL);
      paint.setColor(0xfffaf9f6);
      c.drawPath(p, paint);
      paint.setStyle(Paint.Style.STROKE);
      paint.setColor(0xff9699a2);
      paint.setStrokeWidth(density * .65f);
      c.drawPath(p, paint);
    }
    paint.setStyle(Paint.Style.FILL);
    if (roads) drawTiles(c);
    if (region != null && raster != null && showColors) {
      Bounds b = region.bounds;
      float left = px(b.west),
          right = left + (float) ((b.east - b.west) / 360 * w),
          top = py(b.north),
          bottom = py(b.south);
      paint.setAlpha((int) (255 * overlayOpacity));
      imageBounds.set(left, top, right, bottom);
      c.drawBitmap(raster, null, imageBounds, paint);
      paint.setAlpha(255);
      drawContours(c, left, top, right, bottom);
    }
    if (cities != null) {
      int count = 0;
      paint.setTextSize(11 * density);
      for (CityStore.City city : cities.all)
        if (city.level == 1 && Double.isFinite(city.lat + city.lon)) {
          float x = px(city.lon), y = py(city.lat);
          if (x > 4 && x < getWidth() - 12 && y > 12 && y < getHeight() - 18 && count++ < 24) {
            paint.setColor(0xffeb6c56);
            c.drawCircle(x, y, 2.3f * density, paint);
            paint.setColor(0xff515967);
            c.drawText(
                city.shortName.isEmpty() ? city.name : city.shortName,
                x + 5 * density,
                y - 4 * density,
                paint);
          }
        }
    }
    paint.setColor(0xff616b7c);
    paint.setTextSize(9 * density);
    String credit = roads ? "© OpenStreetMap contributors" : "Natural Earth / DataV · 天气示意";
    c.drawText(credit, 5 * density, getHeight() - 7 * density, paint);
    if (region == null) {
      paint.setColor(0xff616b7c);
      paint.setTextSize(12 * density);
      c.drawText(emptyMessage, 12 * density, getHeight() / 2f, paint);
    }
  }

  private void drawContours(Canvas c, float left, float top, float right, float bottom) {
    paint.setColor(Color.argb(150, 231, 162, 230));
    paint.setStrokeWidth(.9f * density);
    for (int threshold : new int[] {20, 40, 60, 80})
      for (int j = 0; j < 6; j++)
        for (int i = 0; i < 6; i++) {
          double[] v = {
            region.scores[j * 7 + i],
            region.scores[j * 7 + i + 1],
            region.scores[(j + 1) * 7 + i + 1],
            region.scores[(j + 1) * 7 + i]
          };
          double[][] q = {
            {i / 6.0, j / 6.0},
            {(i + 1) / 6.0, j / 6.0},
            {(i + 1) / 6.0, (j + 1) / 6.0},
            {i / 6.0, (j + 1) / 6.0}
          };
          List<float[]> points = new ArrayList<>();
          for (int a = 0; a < 4; a++) {
            int b = (a + 1) % 4;
            if (!Double.isFinite(v[a] + v[b]) || (v[a] >= threshold) == (v[b] >= threshold))
              continue;
            double t = (threshold - v[a]) / (v[b] - v[a]),
                x = q[a][0] + (q[b][0] - q[a][0]) * t,
                y = q[a][1] + (q[b][1] - q[a][1]) * t;
            points.add(
                new float[] {
                  left + (float) x * (right - left), bottom - (float) y * (bottom - top)
                });
          }
          for (int a = 0; a + 1 < points.size(); a += 2)
            c.drawLine(
                points.get(a)[0],
                points.get(a)[1],
                points.get(a + 1)[0],
                points.get(a + 1)[1],
                paint);
        }
  }

  private void drawTiles(Canvas c) {
    int z = (int) Math.floor(zoom), n = 1 << z;
    double size = 256 * density * Math.pow(2, zoom - z),
        x0 = centerX * n - getWidth() / 2 / size,
        y0 = centerY * n - getHeight() / 2 / size;
    int xmin = (int) Math.floor(x0),
        xmax = (int) Math.ceil(x0 + getWidth() / size),
        ymin = (int) Math.floor(y0),
        ymax = (int) Math.ceil(y0 + getHeight() / size);
    for (int y = ymin; y <= ymax; y++)
      for (int x = xmin; x <= xmax; x++) {
        if (y < 0 || y >= n) continue;
        int tx = (x % n + n) % n;
        String key = z + "/" + tx + "/" + y;
        Bitmap b = tiles.get(key);
        if (b != null)
          c.drawBitmap(
              b,
              null,
              new RectF(
                  (float) ((x - x0) * size),
                  (float) ((y - y0) * size),
                  (float) ((x + 1 - x0) * size),
                  (float) ((y + 1 - y0) * size)),
              paint);
        else loadTile(key);
      }
  }

  private void loadTile(String key) {
    if (tileJob.cancelled
        || pending.contains(key)
        || failed.getOrDefault(key, 0L) > System.currentTimeMillis()) return;
    pending.add(key);
    tilesPool.submit(
        () -> {
          try {
            File f = new File(getContext().getCacheDir(), "osm-" + key.replace('/', '-') + ".png");
            byte[] bytes;
            if (f.exists() && System.currentTimeMillis() - f.lastModified() < 7 * 86400000L) {
              try (FileInputStream in = new FileInputStream(f);
                  ByteArrayOutputStream o = new ByteArrayOutputStream()) {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) != -1) o.write(b, 0, n);
                bytes = o.toByteArray();
              }
            } else {
              bytes =
                  Network.bytes(
                      "https://tile.openstreetmap.org/" + key + ".png", tileJob, 1024 * 1024);
              try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(bytes);
              }
              File[] files = getContext().getCacheDir().listFiles((d, n) -> n.startsWith("osm-"));
              if (files != null && files.length > 160) {
                Arrays.sort(files, Comparator.comparingLong(File::lastModified));
                for (int i = 0; i < files.length - 160; i++) files[i].delete();
              }
            }
            Bitmap b = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (b != null) {
              tiles.put(key, b);
              while (tiles.size() > 48) tiles.remove(tiles.keySet().iterator().next());
              postInvalidate();
            }
          } catch (Exception e) {
            failed.put(key, System.currentTimeMillis() + 60000);
          } finally {
            pending.remove(key);
          }
        });
  }

  @Override
  public boolean onTouchEvent(MotionEvent e) {
    if (!interactive) {
      if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
      return true;
    }
    scale.onTouchEvent(e);
    gestures.onTouchEvent(e);
    switch (e.getActionMasked()) {
      case MotionEvent.ACTION_DOWN:
        lastX = downX = e.getX();
        lastY = downY = e.getY();
        moved = false;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        return true;
      case MotionEvent.ACTION_MOVE:
        if (!scale.isInProgress() && e.getPointerCount() == 1) {
          float dx = e.getX() - lastX, dy = e.getY() - lastY;
          centerX -= dx / world();
          centerY -= dy / world();
          if (Math.hypot(e.getX() - downX, e.getY() - downY) > 8 * density) moved = true;
          constrain();
          invalidate();
        }
        lastX = e.getX();
        lastY = e.getY();
        return true;
      case MotionEvent.ACTION_UP:
        if (!moved && listener != null) {
          double lon = wrap((centerX + (e.getX() - getWidth() / 2f) / world()) * 360 - 180),
              lat =
                  invMerc((1 - 2 * (centerY + (e.getY() - getHeight() / 2f) / world())) * Math.PI);
          listener.point(lat, lon);
          performClick();
        } else notifyBounds();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        return true;
      case MotionEvent.ACTION_CANCEL:
        notifyBounds();
        return true;
      default:
        return true;
    }
  }

  @Override
  public boolean performClick() {
    return super.performClick();
  }

  public void dispose() {
    tileJob.cancel();
    tilesPool.shutdownNow();
  }
}
