package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;

import android.content.Context;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public final class CityStore {
  public static final class City {
    public String id, parent, name, shortName, pinyin, label;
    public int level;
    public double lat, lon;

    @Override
    public String toString() {
      return label;
    }
  }

  public final List<City> all = new ArrayList<>();
  public final Map<String, City> byId = new HashMap<>();

  /** Coordinates and IP results remain usable while the optional name index is loading. */
  public CityStore() {}

  public CityStore(Context c) throws Exception {
    this(read(c, "chinaPlacesData.json"));
  }

  public CityStore(String json) throws Exception {
    JSONArray a = new JSONArray(json);
    for (int i = 0; i < a.length(); i++) {
      JSONArray r = a.getJSONArray(i);
      City p = new City();
      p.id = r.getString(0);
      p.parent = r.optString(1, "");
      p.level = r.getInt(2);
      p.name = r.getString(3);
      p.shortName = r.optString(4, "");
      p.pinyin = r.optString(5, "");
      p.lat = r.optDouble(6, Double.NaN);
      p.lon = r.optDouble(7, Double.NaN);
      all.add(p);
      byId.put(p.id, p);
    }
    for (City p : all) {
      LinkedList<String> names = new LinkedList<>();
      City q = p;
      while (q != null) {
        names.addFirst(q.name);
        q = byId.get(q.parent);
      }
      if (p.level == 1) names.add("全市");
      p.label = String.join(" - ", names);
    }
  }

  public static String read(Context c, String name) throws IOException {
    try (InputStream in = c.getAssets().open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }

  public static String key(String s) {
    return s.toLowerCase(Locale.ROOT)
        .replace("臺", "台")
        .replace("灣", "湾")
        .replaceAll("[\\s·・,，\\-－—/'’]", "");
  }

  public List<City> children(String parent) {
    List<City> r = new ArrayList<>();
    for (City p : all) if (p.parent.equals(parent)) r.add(p);
    return r;
  }

  public List<City> provinces() {
    List<City> r = new ArrayList<>();
    for (City p : all) if (p.level == 0) r.add(p);
    return r;
  }

  public List<City> search(String raw) {
    String q = key(raw);
    List<City> result = new ArrayList<>();
    if (q.isEmpty()) {
      for (String id :
          new String[] {"3101", "1101", "4401", "4403", "3301", "5101", "3201", "4201"})
        if (byId.containsKey(id)) result.add(byId.get(id));
      return result;
    }
    List<City> partial = new ArrayList<>();
    for (City p : all) {
      if (p.level == 0) continue;
      String initials = "";
      for (String v : p.pinyin.split(" ")) if (!v.isEmpty()) initials += v.charAt(0);
      if (key(p.name).equals(q)
          || key(p.shortName).equals(q)
          || key(p.pinyin).equals(q)
          || initials.equals(q)) result.add(p);
      else if (key(p.label).contains(q) || key(p.pinyin).contains(q)) partial.add(p);
    }
    result.sort(Comparator.comparingInt(p -> p.level));
    result.addAll(partial);
    return result.subList(0, Math.min(40, result.size()));
  }

  public Place place(City c) {
    return new Place(c.label, c.lat, c.lon, "manual");
  }

  public Place gps(double lat, double lon, double accuracy) {
    City nearest = null;
    double best = 60;
    for (City c : all)
      if (c.level == 2 && Double.isFinite(c.lat + c.lon)) {
        double d = distance(lat, lon, c.lat, c.lon);
        if (d < best) {
          best = d;
          nearest = c;
        }
      }
    Place p =
        new Place(
            nearest == null
                ? String.format(Locale.ROOT, "%.4f, %.4f", lat, lon)
                : nearest.label + "附近",
            lat,
            lon,
            "gps");
    p.accuracy = accuracy;
    p.timestamp = System.currentTimeMillis();
    return p;
  }

  public Place normalizeIp(JSONObject j) throws Exception {
    double lat = j.optDouble("latitude", j.optDouble("lat", Double.NaN)),
        lon = j.optDouble("longitude", j.optDouble("lon", Double.NaN));
    String province = j.optString("region", j.optString("province", "")),
        name = j.optString("city", "");
    JSONObject data = j.optJSONObject("data");
    if (data != null && data.optJSONArray("location") != null) {
      JSONArray a = data.getJSONArray("location");
      province = a.optString(1, "");
      name = a.optString(2, "");
    }
    City match = null;
    for (City c : all)
      if (c.level == 1
          && (key(c.name).equals(key(name))
              || key(c.shortName).equals(key(name))
              || key(c.pinyin).equals(key(name)))) {
        City parent = byId.get(c.parent);
        if (!province.isEmpty()
            && parent != null
            && !key(parent.name).contains(key(province))
            && !key(province).contains(key(parent.shortName))
            && Double.isFinite(lat)
            && distance(lat, lon, c.lat, c.lon) > 150) continue;
        match = c;
        break;
      }
    if (match != null) {
      if (!Double.isFinite(lat + lon)) {
        lat = match.lat;
        lon = match.lon;
      }
      name = match.label.replace("全市", "区县未知");
    } else name = province + " - " + name + " - 区县未知";
    if (!Double.isFinite(lat + lon)
        || Math.abs(lat) > 72
        || Math.abs(lon) > 180
        || name.trim().isEmpty()) throw new Exception("无法识别 IP 地区");
    return new Place(name, lat, lon, "ip");
  }
}
