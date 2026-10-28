package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.json.*;

public final class ForecastRepository {
  public static final String WEATHER = "https://api.open-meteo.com/v1/forecast",
      AIR = "https://air-quality-api.open-meteo.com/v1/air-quality";
  private final Context context;
  private final SharedPreferences prefs;

  public ForecastRepository(Context c) {
    context = c;
    prefs = c.getSharedPreferences("native_forecast", 0);
  }

  private String hash(String s) throws Exception {
    byte[] b = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
    StringBuilder o = new StringBuilder();
    for (byte x : b) o.append(String.format(Locale.ROOT, "%02x", x));
    return o.toString();
  }

  private File cacheFile(String key) throws Exception {
    return new File(context.getCacheDir(), "forecast-" + hash(key) + ".json");
  }

  public Forecast fetch(Place p, String model, String event, Network.Job job) throws Exception {
    if (!Double.isFinite(p.lat + p.lon) || Math.abs(p.lat) > 72 || Math.abs(p.lon) > 180)
      throw new Exception("模型只适用于南北纬 72° 以内的地点");
    String key =
        String.format(
            Locale.ROOT,
            "%.4f:%.4f:%s:%s:%s",
            p.lat,
            p.lon,
            model,
            event,
            LocalDate.now(ZoneId.of(p.zone)));
    File cached = cacheFile(key);
    if (cached.exists()
        && System.currentTimeMillis() - cached.lastModified() < 1800000
        && System.currentTimeMillis() >= cached.lastModified())
      try {
        JSONObject j = new JSONObject(read(cached));
        Forecast f = compute(j);
        LocalDate wanted = LocalDate.now(ZoneId.of(f.zone)).plusDays(event.endsWith("1") ? 1 : 0);
        if (Instant.ofEpochSecond((long) f.eventTime)
            .atZone(ZoneId.of(f.zone))
            .toLocalDate()
            .equals(wanted)) {
          f.place = p;
          f.place.zone = f.zone;
          f.raw.put("place", p.json());
          f.cached = true;
          return f;
        }
      } catch (Exception ignored) {
      }
    JSONObject local =
        (JSONObject)
            Network.json(
                Network.url(
                    WEATHER,
                    "latitude",
                    p.lat,
                    "longitude",
                    p.lon,
                    "hourly",
                    "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility",
                    "daily",
                    "sunrise,sunset",
                    "models",
                    model,
                    "timezone",
                    "auto",
                    "timeformat",
                    "unixtime",
                    "forecast_days",
                    3,
                    "past_days",
                    1),
                job);
    String zone = local.getString("timezone");
    ZoneId zid = ZoneId.of(zone);
    LocalDate date = LocalDate.now(zid).plusDays(event.endsWith("1") ? 1 : 0);
    JSONArray ev =
        local.getJSONObject("daily").getJSONArray(event.startsWith("set") ? "sunset" : "sunrise");
    double eventTime = Double.NaN;
    for (int i = 0; i < ev.length(); i++) {
      double t = ev.optDouble(i, Double.NaN);
      if (Double.isFinite(t)
          && t > 0
          && Instant.ofEpochSecond((long) t).atZone(zid).toLocalDate().equals(date)) {
        eventTime = t;
        break;
      }
    }
    if (!Double.isFinite(eventTime)) throw new Exception("当天无可用日出日落时刻，可能处于极昼极夜或数据缺测");
    double bearing = solar(eventTime, p.lat, p.lon)[1];
    StringBuilder lats = new StringBuilder(), lons = new StringBuilder();
    JSONArray points = new JSONArray();
    for (int i = 0; i < 5; i++) {
      double[] q = destination(p.lat, p.lon, bearing, DISTANCES[i]);
      if (i > 0) {
        lats.append(',');
        lons.append(',');
      }
      lats.append(q[0]);
      lons.append(q[1]);
      points.put(new JSONObject().put("lat", q[0]).put("lon", q[1]).put("km", DISTANCES[i]));
    }
    Object far =
        Network.json(
            Network.url(
                WEATHER,
                "latitude",
                lats,
                "longitude",
                lons,
                "hourly",
                "cloud_cover_low,cloud_cover_mid,cloud_cover_high",
                "models",
                model,
                "timezone",
                "GMT",
                "timeformat",
                "unixtime",
                "forecast_days",
                3,
                "past_days",
                1),
            job);
    if (!(far instanceof JSONArray) || ((JSONArray) far).length() != 5)
      throw new Exception("方向采样数据不完整");
    for (int i = 0; i < 5; i++)
      points
          .getJSONObject(i)
          .put("hourly", ((JSONArray) far).getJSONObject(i).getJSONObject("hourly"));
    JSONObject air = null;
    try {
      air =
          (JSONObject)
              Network.json(
                  Network.url(
                      AIR,
                      "latitude",
                      p.lat,
                      "longitude",
                      p.lon,
                      "hourly",
                      "aerosol_optical_depth",
                      "domains",
                      "cams_global",
                      "timeformat",
                      "unixtime",
                      "forecast_days",
                      3,
                      "past_days",
                      1),
                  job);
    } catch (Exception e) {
      job.check();
    }
    p.zone = zone;
    JSONObject b =
        new JSONObject()
            .put("version", "afterglow-1.0")
            .put("kind", "live")
            .put("place", p.json())
            .put("model", model)
            .put("event", event)
            .put("eventTime", eventTime)
            .put("bearing", bearing)
            .put("timezone", zone)
            .put("fetchedAt", System.currentTimeMillis())
            .put("local", local)
            .put("transect", points)
            .put("air", air == null ? JSONObject.NULL : air);
    Forecast f = compute(b);
    job.check();
    try {
      write(cached, b.toString());
      File[] files =
          context
              .getCacheDir()
              .listFiles((d, n) -> n.startsWith("forecast-") && n.endsWith(".json"));
      if (files != null && files.length > 8) {
        Arrays.sort(files, Comparator.comparingLong(File::lastModified));
        for (int i = 0; i < files.length - 8; i++) files[i].delete();
      }
    } catch (Exception ignored) {
    }
    return f;
  }

  public synchronized void record(Forecast f) {
    try {
      JSONArray old = new JSONArray(prefs.getString("records", "[]")), next = new JSONArray();
      next.put(f.raw);
      for (int i = 0; i < old.length() && next.length() < 10; i++) {
        JSONObject j = old.getJSONObject(i);
        JSONObject p = j.getJSONObject("place");
        if (p.optDouble("lat") == f.place.lat
            && p.optDouble("lon") == f.place.lon
            && j.optString("event").equals(f.event)
            && j.optString("model").equals(f.model)
            && j.optDouble("eventTime") == f.eventTime) continue;
        next.put(j);
      }
      prefs
          .edit()
          .putString("records", next.toString())
          .putString("lastPlace", f.place.json().toString())
          .apply();
    } catch (Exception ignored) {
    }
  }

  public JSONArray records() {
    try {
      return new JSONArray(prefs.getString("records", "[]"));
    } catch (Exception e) {
      return new JSONArray();
    }
  }

  public void clearRecords() {
    prefs.edit().putString("records", "[]").apply();
  }

  public Place lastPlace() {
    try {
      Place p = Place.from(new JSONObject(prefs.getString("lastPlace", "")));
      p.source = "saved";
      return p;
    } catch (Exception e) {
      return null;
    }
  }

  private static String read(File f) throws IOException {
    try (FileInputStream in = new FileInputStream(f);
        ByteArrayOutputStream o = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) o.write(b, 0, n);
      return new String(o.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  private static void write(File f, String s) throws IOException {
    try (FileOutputStream out = new FileOutputStream(f)) {
      out.write(s.getBytes(StandardCharsets.UTF_8));
    }
  }

  public static final class Bounds {
    public double west, east, south, north;

    public Bounds(double w, double e, double s, double n) {
      west = w;
      east = e;
      south = Math.max(-72, s);
      north = Math.min(72, n);
    }

    public String key() {
      return String.format(Locale.ROOT, "%.2f:%.2f:%.2f:%.2f", west, east, south, north);
    }
  }

  public static final class Region {
    public Bounds bounds;
    public String event, model, date, zone;
    public double[] scores = new double[49], aod = new double[49];
    public int valid;
    public long fetchedAt;
    public String note = "";

    public Region() {
      Arrays.fill(scores, Double.NaN);
      Arrays.fill(aod, Double.NaN);
    }
  }

  public static double merc(double lat) {
    return Math.log(Math.tan(Math.PI / 4 + rad(clamp(lat, -85, 85)) / 2));
  }

  public static double invMerc(double y) {
    return Math.toDegrees(2 * Math.atan(Math.exp(y)) - Math.PI / 2);
  }

  private static double unwrap(double lon, double center) {
    while (lon - center > 180) lon -= 360;
    while (lon - center < -180) lon += 360;
    return lon;
  }

  private static final class Grid {
    Bounds b;
    JSONObject[] hourly;
    int n;

    Sample sampleAt(double lat, double lon, double t) {
      double
          x =
              clamp(
                  (unwrap(lon, (b.west + b.east) / 2) - b.west) / (b.east - b.west) * (n - 1),
                  0,
                  n - 1),
          y =
              clamp(
                  (merc(lat) - merc(b.south)) / (merc(b.north) - merc(b.south)) * (n - 1),
                  0,
                  n - 1);
      int i = Math.min(n - 2, (int) x), j = Math.min(n - 2, (int) y);
      double fx = x - i, fy = y - j;
      double[] weights = {(1 - fx) * (1 - fy), fx * (1 - fy), (1 - fx) * fy, fx * fy};
      int[] ids = {j * n + i, j * n + i + 1, (j + 1) * n + i, (j + 1) * n + i + 1};
      double l = 0, m = 0, h = 0, v = 0;
      for (int q = 0; q < 4; q++) {
        if (weights[q] < 1e-8) continue;
        Sample p = ForecastEngine.sample(hourly[ids[q]], t);
        if (p.cloud == null || !Double.isFinite(p.visibility)) return new Sample(null, Double.NaN);
        l += weights[q] * p.cloud.low;
        m += weights[q] * p.cloud.mid;
        h += weights[q] * p.cloud.high;
        v += weights[q] * p.visibility;
      }
      return new Sample(new Cloud(l, m, h), v);
    }
  }

  private final Map<String, JSONObject> sampleCache =
      Collections.synchronizedMap(new LinkedHashMap<>());
  private final Map<String, Long> sampleTimes = Collections.synchronizedMap(new HashMap<>());
  private final Map<String, Region[]> regionCache =
      Collections.synchronizedMap(new LinkedHashMap<>());

  private JSONObject[] fetchGrid(double[][] coords, boolean air, String model, Network.Job job)
      throws Exception {
    JSONObject[] out = new JSONObject[coords.length];
    List<Integer> missing = new ArrayList<>();
    String day = LocalDate.now(ZoneOffset.UTC).toString();
    for (int i = 0; i < coords.length; i++) {
      String key = sampleKey(coords[i], air, model, day);
      JSONObject value = sampleCache.get(key);
      Long time = sampleTimes.get(key);
      if (value != null
          && time != null
          && System.currentTimeMillis() - time < 1800000
          && System.currentTimeMillis() >= time) out[i] = value;
      else missing.add(i);
    }
    for (int start = 0; start < missing.size(); start += 32) {
      job.check();
      List<Integer> ids = missing.subList(start, Math.min(start + 32, missing.size()));
      StringBuilder a = new StringBuilder(), o = new StringBuilder();
      for (int index : ids) {
        if (a.length() > 0) {
          a.append(',');
          o.append(',');
        }
        a.append(coords[index][0]);
        o.append(wrap(coords[index][1]));
      }
      try {
        Object data =
            Network.json(
                Network.url(
                    air ? AIR : WEATHER,
                    "latitude",
                    a,
                    "longitude",
                    o,
                    "hourly",
                    air
                        ? "aerosol_optical_depth"
                        : "cloud_cover_low,cloud_cover_mid,cloud_cover_high,visibility",
                    air ? "domains" : "models",
                    air ? "cams_global" : model,
                    "timeformat",
                    "unixtime",
                    "forecast_days",
                    3,
                    "past_days",
                    1),
                job);
        JSONArray list = data instanceof JSONArray ? (JSONArray) data : new JSONArray().put(data);
        if (list.length() != ids.size()) throw new Exception("采样数量不完整");
        for (int k = 0; k < ids.size(); k++) {
          JSONObject hour = list.getJSONObject(k).getJSONObject("hourly");
          int index = ids.get(k);
          out[index] = hour;
          String key = sampleKey(coords[index], air, model, day);
          sampleCache.put(key, hour);
          sampleTimes.put(key, System.currentTimeMillis());
        }
      } catch (Network.HttpError e) {
        job.check();
        if (e.status == 429) throw e;
      } catch (Exception e) {
        job.check();
      }
    }
    synchronized (sampleCache) {
      while (sampleCache.size() > 1600) {
        String key = sampleCache.keySet().iterator().next();
        sampleCache.remove(key);
        sampleTimes.remove(key);
      }
    }
    return out;
  }

  private String sampleKey(double[] p, boolean air, String model, String day) {
    return String.format(
        Locale.ROOT, "%s:%s:%.4f:%.4f:%s", air ? "air" : "weather", model, p[0], wrap(p[1]), day);
  }

  public Region[] region(Bounds b, String model, int day, String zone, Network.Job job)
      throws Exception {
    ZoneId zid = ZoneId.of(zone);
    LocalDate date = LocalDate.now(zid).plusDays(day);
    String key = b.key() + model + date + zone;
    Region[] cached = regionCache.get(key);
    if (cached != null
        && cached[0].valid == 49
        && cached[1].valid == 49
        && System.currentTimeMillis() - cached[0].fetchedAt < 1800000) return cached;
    double[][] obs = new double[49][2];
    double minLat = b.south,
        maxLat = b.north,
        minLon = b.west,
        maxLon = b.east,
        center = (b.west + b.east) / 2;
    double[][] eventTimes = new double[2][49];
    for (int j = 0; j < 7; j++)
      for (int i = 0; i < 7; i++) {
        int k = j * 7 + i;
        double lat = invMerc(merc(b.south) + (merc(b.north) - merc(b.south)) * j / 6),
            lon = b.west + (b.east - b.west) * i / 6;
        obs[k] = new double[] {lat, lon};
        for (int e = 0; e < 2; e++) {
          double t = eventAt(lat, wrap(lon), date, e == 1, zid);
          eventTimes[e][k] = t;
          if (!Double.isFinite(t)) continue;
          double bearing = solar(t, lat, lon)[1];
          for (int km : DISTANCES) {
            double[] p = destination(lat, lon, bearing, km);
            minLat = Math.min(minLat, p[0]);
            maxLat = Math.max(maxLat, p[0]);
            minLon = Math.min(minLon, unwrap(p[1], center));
            maxLon = Math.max(maxLon, unwrap(p[1], center));
          }
        }
      }
    Bounds field =
        new Bounds(minLon - .1, maxLon + .1, Math.max(-72, minLat - .1), Math.min(72, maxLat + .1));
    double[][] grid = new double[121][2];
    for (int j = 0; j < 11; j++)
      for (int i = 0; i < 11; i++)
        grid[j * 11 + i] =
            new double[] {
              invMerc(merc(field.south) + (merc(field.north) - merc(field.south)) * j / 10),
              field.west + (field.east - field.west) * i / 10
            };
    Grid g = new Grid();
    g.b = field;
    g.n = 11;
    g.hourly = fetchGrid(grid, false, model, job);
    JSONObject[] air = fetchGrid(obs, true, model, job);
    Region[] result = new Region[2];
    for (int e = 0; e < 2; e++) {
      Region r = new Region();
      r.bounds = b;
      r.model = model;
      r.zone = zone;
      r.date = date.toString();
      r.event = (e == 0 ? "rise_" : "set_") + day;
      r.fetchedAt = System.currentTimeMillis();
      for (int k = 0; k < 49; k++) {
        job.check();
        double t = eventTimes[e][k];
        if (!Double.isFinite(t)) continue;
        double lat = obs[k][0],
            lon = obs[k][1],
            bearing = solar(t, lat, lon)[1],
            best = -1,
            bestAod = Double.NaN;
        boolean missing = false;
        for (int d = -50; d <= 50; d += 5) {
          double at = t + d * 60;
          Sample local = g.sampleAt(lat, lon, at);
          Cloud[] far = new Cloud[5];
          for (int z = 0; z < 5; z++) {
            double[] p = destination(lat, lon, bearing, DISTANCES[z]);
            far[z] = g.sampleAt(p[0], p[1], at).cloud;
          }
          double aod = interpolate(air[k], "aerosol_optical_depth", at);
          Point p = evaluate(at, lat, lon, local, far, aod);
          if (p == null || !Double.isFinite(p.score)) {
            missing = true;
            break;
          }
          if (p.score > best) {
            best = p.score;
            bestAod = p.aod;
          }
        }
        if (!missing && best >= 0) {
          r.scores[k] = best;
          r.aod[k] = bestAod;
          r.valid++;
        }
      }
      r.note = r.valid == 49 ? "当前视野已更新" : "部分区域缺测，将自动重试";
      result[e] = r;
    }
    if (result[0].valid == 0 && result[1].valid == 0) throw new Exception("区域气象数据缺测，请稍后重试");
    synchronized (regionCache) {
      regionCache.put(key, result);
      while (regionCache.size() > 8) regionCache.remove(regionCache.keySet().iterator().next());
    }
    return result;
  }
}
