package cn.hengx.sunset;

import java.time.*;
import java.util.*;
import org.json.*;

/** The existing independent forecast model, ported to Java. No browser or script engine. */
public final class ForecastEngine {
  public static final int[] DISTANCES = {80, 180, 300, 450, 600};

  public static double clamp(double v, double a, double b) {
    return Math.max(a, Math.min(b, v));
  }

  public static double rad(double v) {
    return Math.toRadians(v);
  }

  public static double norm(double v) {
    return (v % 360 + 360) % 360;
  }

  public static double wrap(double v) {
    return norm(v + 180) - 180;
  }

  public static final class Place {
    public String name, source, zone = "Asia/Shanghai";
    public double lat, lon, accuracy;
    public long timestamp;

    public Place(String n, double a, double o, String s) {
      name = n;
      lat = a;
      lon = o;
      source = s;
    }

    public JSONObject json() {
      JSONObject j = new JSONObject();
      try {
        j.put("name", name)
            .put("lat", lat)
            .put("lon", lon)
            .put("source", source)
            .put("timezone", zone)
            .put("accuracy", accuracy)
            .put("timestamp", timestamp);
      } catch (JSONException ignored) {
      }
      return j;
    }

    public static Place from(JSONObject j) throws JSONException {
      Place p =
          new Place(
              j.getString("name"),
              j.getDouble("lat"),
              j.getDouble("lon"),
              j.optString("source", "manual"));
      p.zone = j.optString("timezone", "Asia/Shanghai");
      p.accuracy = j.optDouble("accuracy", 0);
      p.timestamp = j.optLong("timestamp", 0);
      if (!Double.isFinite(p.lat)
          || !Double.isFinite(p.lon)
          || Math.abs(p.lat) > 72
          || Math.abs(p.lon) > 180) throw new JSONException("地点坐标超出模型范围");
      return p;
    }
  }

  public static final class Cloud {
    public double low, mid, high;

    public Cloud(double l, double m, double h) {
      low = l;
      mid = m;
      high = h;
    }
  }

  public static final class Sample {
    public Cloud cloud;
    public double visibility;

    public Sample(Cloud c, double v) {
      cloud = c;
      visibility = v;
    }
  }

  public static final class Point {
    public double t, score, min, max, aod, alt, tm, th, visibility;
    public Cloud cloud;
    public Cloud[] far;
  }

  public static final class Forecast {
    public JSONObject raw;
    public Place place;
    public String model, event, zone;
    public double eventTime, bearing;
    public long fetchedAt;
    public List<Point> points = new ArrayList<>();
    public Point peak;
    public boolean missingAod, cached, replay;
    public double[] window;
  }

  public static double[] solar(double t, double lat, double lon) {
    double T = (t / 86400 + 2440587.5 - 2451545) / 36525,
        L = norm(280.46646 + T * (36000.76983 + T * .0003032)),
        M = norm(357.52911 + T * (35999.05029 - .0001537 * T));
    double e = .016708634 - T * (.000042037 + .0000001267 * T),
        C =
            Math.sin(rad(M)) * (1.914602 - T * (.004817 + .000014 * T))
                + Math.sin(rad(2 * M)) * (.019993 - .000101 * T)
                + Math.sin(rad(3 * M)) * .000289;
    double omega = 125.04 - 1934.136 * T,
        lambda = L + C - .00569 - .00478 * Math.sin(rad(omega)),
        eps =
            23
                + (26 + (21.448 - T * (46.815 + T * (.00059 - T * .001813))) / 60) / 60
                + .00256 * Math.cos(rad(omega));
    double dec = Math.asin(Math.sin(rad(eps)) * Math.sin(rad(lambda))),
        y = Math.pow(Math.tan(rad(eps) / 2), 2);
    double eq =
        4
            * Math.toDegrees(
                y * Math.sin(2 * rad(L))
                    - 2 * e * Math.sin(rad(M))
                    + 4 * e * y * Math.sin(rad(M)) * Math.cos(2 * rad(L))
                    - .5 * y * y * Math.sin(4 * rad(L))
                    - 1.25 * e * e * Math.sin(2 * rad(M)));
    double mins = ((t % 86400 + 86400) % 86400) / 60,
        ha = rad(norm((mins + eq + 4 * lon) / 4) - 180),
        phi = rad(lat);
    return new double[] {
      Math.toDegrees(
          Math.asin(
              clamp(
                  Math.sin(phi) * Math.sin(dec) + Math.cos(phi) * Math.cos(dec) * Math.cos(ha),
                  -1,
                  1))),
      norm(
          Math.toDegrees(
                  Math.atan2(
                      Math.sin(ha), Math.cos(ha) * Math.sin(phi) - Math.tan(dec) * Math.cos(phi)))
              + 180)
    };
  }

  public static double[] destination(double lat, double lon, double bearing, double km) {
    double a = rad(lat),
        l = rad(lon),
        b = rad(bearing),
        d = km / 6371,
        p = Math.asin(Math.sin(a) * Math.cos(d) + Math.cos(a) * Math.sin(d) * Math.cos(b));
    return new double[] {
      Math.toDegrees(p),
      wrap(
          Math.toDegrees(
              l
                  + Math.atan2(
                      Math.sin(b) * Math.sin(d) * Math.cos(a),
                      Math.cos(d) - Math.sin(a) * Math.sin(p))))
    };
  }

  public static double distance(double a, double b, double c, double d) {
    double x = rad(c - a),
        y = rad(d - b),
        v =
            Math.sin(x / 2) * Math.sin(x / 2)
                + Math.cos(rad(a)) * Math.cos(rad(c)) * Math.sin(y / 2) * Math.sin(y / 2);
    return 12742 * Math.asin(Math.sqrt(clamp(v, 0, 1)));
  }

  public static double interpolate(JSONObject h, String key, double t) {
    if (h == null) return Double.NaN;
    JSONArray ts = h.optJSONArray("time"), vs = h.optJSONArray(key);
    if (ts == null || vs == null || ts.length() < 2 || vs.length() != ts.length())
      return Double.NaN;
    int lo = 0, hi = ts.length() - 1;
    if (t < ts.optDouble(0) || t > ts.optDouble(hi)) return Double.NaN;
    while (lo + 1 < hi) {
      int m = (lo + hi) / 2;
      if (ts.optDouble(m) <= t) lo = m;
      else hi = m;
    }
    double x = ts.optDouble(lo),
        y = ts.optDouble(hi),
        a = vs.optDouble(lo, Double.NaN),
        b = vs.optDouble(hi, Double.NaN);
    if (t == x) return a;
    if (t == y) return b;
    if (!Double.isFinite(a) || !Double.isFinite(b) || y - x > 14400) return Double.NaN;
    return a + (b - a) * (t - x) / (y - x);
  }

  public static Sample sample(JSONObject hourly, double t) {
    double l = interpolate(hourly, "cloud_cover_low", t),
        m = interpolate(hourly, "cloud_cover_mid", t),
        h = interpolate(hourly, "cloud_cover_high", t),
        v = interpolate(hourly, "visibility", t);
    return new Sample(
        Double.isFinite(l + m + h)
            ? new Cloud(clamp(l / 100, 0, 1), clamp(m / 100, 0, 1), clamp(h / 100, 0, 1))
            : null,
        v);
  }

  public static double transmission(double height, double alpha, Cloud[] clouds) {
    double slope = Math.tan(rad(alpha)), xmin = clamp(-slope * 6371, 0, 1000);
    if (height + xmin * slope + xmin * xmin / (2 * 6371) <= 0) return 0;
    double tau = 0, prev = 0;
    for (int i = 0; i < 5; i++) {
      double x = (prev + DISTANCES[i]) / 2, z = height + x * slope + x * x / (2 * 6371);
      Cloud c = clouds[i];
      double cover = z < 0 ? 1 : z < 2 ? c.low : z < 6 ? c.mid : z < 12 ? c.high : 0;
      tau += -Math.log(1 - .97 * cover) * (DISTANCES[i] - prev) / 100 * .18;
      prev = DISTANCES[i];
    }
    return Math.exp(-tau);
  }

  public static Point evaluate(
      double t, double lat, double lon, Sample local, Cloud[] far, double aod) {
    if (local == null
        || local.cloud == null
        || !Double.isFinite(local.visibility)
        || local.visibility < 0
        || far.length != 5) return null;
    for (Cloud c : far) if (c == null) return null;
    Point p = new Point();
    p.t = t;
    p.cloud = local.cloud;
    p.far = far;
    p.visibility = local.visibility;
    p.alt = solar(t, lat, lon)[0];
    p.tm = transmission(4, p.alt, far);
    p.th = transmission(9, p.alt, far);
    double
        paint =
            clamp(
                .70 * Math.sqrt(p.cloud.mid) * p.tm
                    + .80 * Math.sqrt(p.cloud.high) * (1 - .60 * p.cloud.mid) * p.th,
                0,
                1),
        low = Math.exp(-2.4 * p.cloud.low),
        vis = Math.sqrt(clamp(p.visibility / 25000, 0, 1)),
        phase = Math.exp(-.5 * Math.pow((p.alt + 1.2) / 2.6, 2)),
        without = 100 * paint * low * vis * phase;
    p.aod = Double.isFinite(aod) && aod >= 0 ? aod : Double.NaN;
    p.min = without * Math.exp(-.96);
    p.max = without;
    p.score = Double.isFinite(p.aod) ? without * Math.exp(-1.6 * p.aod) : Double.NaN;
    return p;
  }

  private static void validateHourly(JSONObject h, String... keys) throws Exception {
    if (h == null) throw new Exception("缺少逐小时数据");
    JSONArray times = h.optJSONArray("time");
    if (times == null || times.length() < 2 || times.length() > 1000) throw new Exception("时间轴无效");
    double previous = Double.NEGATIVE_INFINITY;
    for (int i = 0; i < times.length(); i++) {
      double t = times.optDouble(i, Double.NaN);
      if (!Double.isFinite(t) || t <= previous) throw new Exception("时间轴无效");
      previous = t;
    }
    for (String key : keys) {
      JSONArray a = h.optJSONArray(key);
      if (a == null || a.length() != times.length()) throw new Exception("数据字段无效：" + key);
      for (int i = 0; i < a.length(); i++) {
        if (a.isNull(i)) continue;
        double v = a.optDouble(i, Double.NaN);
        if (!Double.isFinite(v) || v < 0 || key.startsWith("cloud_cover_") && v > 100)
          throw new Exception("数据值无效：" + key);
      }
    }
  }

  public static Forecast compute(JSONObject bundle) throws Exception {
    if (!Arrays.asList("gfs_seamless", "ecmwf_ifs").contains(bundle.optString("model"))
        || !Arrays.asList("set_0", "set_1", "rise_0", "rise_1").contains(bundle.optString("event")))
      throw new Exception("预报参数无效");
    Forecast f = new Forecast();
    f.raw = bundle;
    f.place = Place.from(bundle.getJSONObject("place"));
    f.zone = bundle.getString("timezone");
    ZoneId.of(f.zone);
    f.place.zone = f.zone;
    f.model = bundle.getString("model");
    f.event = bundle.getString("event");
    f.eventTime = bundle.getDouble("eventTime");
    f.bearing = bundle.getDouble("bearing");
    f.fetchedAt = bundle.getLong("fetchedAt");
    f.replay = false;
    JSONObject local = bundle.getJSONObject("local").getJSONObject("hourly"),
        air = bundle.optJSONObject("air");
    JSONArray transect = bundle.getJSONArray("transect");
    if (transect.length() != 5) throw new Exception("缺少五个方向采样点");
    validateHourly(local, "cloud_cover_low", "cloud_cover_mid", "cloud_cover_high", "visibility");
    if (air != null) validateHourly(air.optJSONObject("hourly"), "aerosol_optical_depth");
    for (int i = 0; i < 5; i++)
      validateHourly(
          transect.getJSONObject(i).optJSONObject("hourly"),
          "cloud_cover_low",
          "cloud_cover_mid",
          "cloud_cover_high");
    for (int d = -50; d <= 50; d += 5) {
      double t = f.eventTime + d * 60;
      Cloud[] far = new Cloud[5];
      for (int i = 0; i < 5; i++) {
        JSONObject h = transect.getJSONObject(i);
        if (h.getInt("km") != DISTANCES[i]) throw new Exception("方向采样距离无效");
        far[i] = sample(h.getJSONObject("hourly"), t).cloud;
      }
      Point p =
          evaluate(
              t,
              f.place.lat,
              f.place.lon,
              sample(local, t),
              far,
              air == null
                  ? Double.NaN
                  : interpolate(air.optJSONObject("hourly"), "aerosol_optical_depth", t));
      if (p == null) throw new Exception("云量或能见度缺测，请更换数据源或稍后重试");
      f.points.add(p);
      if (!Double.isFinite(p.aod)) f.missingAod = true;
    }
    for (Point p : f.points) {
      if (f.missingAod) p.score = Double.NaN;
      if (f.peak == null || (f.missingAod ? p.max > f.peak.max : p.score > f.peak.score))
        f.peak = p;
    }
    double key = f.missingAod ? f.peak.max : f.peak.score, threshold = Math.max(20, .65 * key);
    int a = f.points.indexOf(f.peak), b = a;
    if (key >= threshold) {
      while (a > 0
          && (f.missingAod ? f.points.get(a - 1).max : f.points.get(a - 1).score) >= threshold) a--;
      while (b < 20
          && (f.missingAod ? f.points.get(b + 1).max : f.points.get(b + 1).score) >= threshold) b++;
      f.window = new double[] {f.points.get(a).t, f.points.get(b).t};
    }
    return f;
  }

  public static double eventAt(
      double lat, double lon, LocalDate date, boolean sunset, ZoneId zone) {
    long start = date.atStartOfDay(zone).toEpochSecond(),
        end = date.plusDays(1).atStartOfDay(zone).toEpochSecond();
    double prev = solar(start, lat, lon)[0] + .833;
    for (long t = start + 600; t <= end; t += 600) {
      double value = solar(t, lat, lon)[0] + .833;
      if (sunset ? prev > 0 && value <= 0 : prev < 0 && value >= 0) {
        double a = t - 600, b = t;
        for (int j = 0; j < 24; j++) {
          double m = (a + b) / 2, v = solar(m, lat, lon)[0] + .833;
          if (sunset ? v > 0 : v < 0) a = m;
          else b = m;
        }
        return (a + b) / 2;
      }
      prev = value;
    }
    return Double.NaN;
  }

  public static String quality(double v) {
    return !Double.isFinite(v)
        ? "数据缺测"
        : v < 15 ? "较难出现" : v < 35 ? "可能小烧" : v < 60 ? "可能中烧" : v < 80 ? "中烧到大烧" : "可能大烧";
  }

  public static String clarity(double a, double v) {
    return !Double.isFinite(a)
        ? "缺测"
        : a <= .05 && v >= 30000
            ? "高级水晶天"
            : a <= .1 && v >= 25000
                ? "水晶天"
                : a < .15 ? "通透" : a < .3 ? "还不错" : a < .5 ? "一般" : a < .8 ? "偏浑浊" : "浑浊";
  }
}
