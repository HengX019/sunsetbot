package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;

import cn.hengx.sunset.ForecastEngine.*;
import java.nio.file.*;
import java.time.*;
import org.json.*;

public final class ForecastEngineTest {
  static int count;

  static void check(boolean v, String msg) {
    count++;
    if (!v) throw new AssertionError(msg);
  }

  static JSONObject copy(JSONObject j) throws Exception {
    return new JSONObject(j.toString());
  }

  @org.junit.Test
  public void originalModelParity() throws Exception {
    JSONObject fixture = new JSONObject(resource("weather-fixture.json"));
    Forecast f = compute(fixture);
    check(Math.abs(f.peak.score - 50.33025822233193) < 1e-9, "Original JavaScript model parity");
    check(f.points.size() == 21, "21 time samples");
    check(Math.abs(f.peak.alt - (-.8999536539278882)) < 1e-9, "Solar altitude parity");
    Cloud[] clear = new Cloud[5], opaque = new Cloud[5];
    for (int i = 0; i < 5; i++) {
      clear[i] = new Cloud(0, 0, 0);
      opaque[i] = new Cloud(1, 1, 1);
    }
    check(transmission(4, 0, clear) == 1, "Clear light path");
    check(transmission(4, -2.7, clear) == 0, "Earth shadow");
    check(transmission(9, -2.7, clear) > 0, "High cloud lit");
    check(transmission(4, 0, opaque) < 1, "Opaque cloud blockage");
    double[] sun = solar(Instant.parse("2026-03-20T12:07:00Z").getEpochSecond(), 0, 0);
    check(sun[0] > 89, "Equinox zenith");
    double[] east = destination(0, 179, 90, 300);
    check(east[1] < 0 && Math.abs(east[0]) < 1e-8, "Antimeridian crossing");
    JSONObject h = new JSONObject("{\"time\":[100,200],\"v\":[0,100]}");
    check(interpolate(h, "v", 150) == 50, "Temporal interpolation");
    check(Double.isNaN(interpolate(h, "v", 300)), "No extrapolation");
    h.getJSONArray("v").put(0, JSONObject.NULL);
    check(Double.isNaN(interpolate(h, "v", 150)), "Missing sample stays missing");
    JSONObject j = copy(fixture);
    j.put("air", JSONObject.NULL);
    Forecast missing = compute(j);
    check(
        missing.missingAod && missing.points.stream().allMatch(p -> Double.isNaN(p.score)),
        "AOD not fabricated");
    j = copy(fixture);
    JSONArray low =
        j.getJSONObject("local").getJSONObject("hourly").getJSONArray("cloud_cover_low");
    for (int i = 0; i < low.length(); i++) low.put(i, 100);
    check(compute(j).peak.score < f.peak.score, "Low cloud attenuation");
    j = copy(fixture);
    JSONArray aod =
        j.getJSONObject("air").getJSONObject("hourly").getJSONArray("aerosol_optical_depth");
    for (int i = 0; i < aod.length(); i++) aod.put(i, 1);
    check(compute(j).peak.score < f.peak.score, "Aerosol attenuation");
    j = copy(fixture);
    j.getJSONObject("local").getJSONObject("hourly").getJSONArray("cloud_cover_low").put(0, 101);
    boolean rejected = false;
    try {
      compute(j);
    } catch (Exception e) {
      rejected = true;
    }
    check(rejected, "Invalid cloud rejected");
    check(clarity(.03, 35000).equals("高级水晶天"), "Crystal threshold");
    check(!clarity(.03, 1000).contains("水晶"), "Visibility threshold");
    double ev =
        eventAt(31.2304, 121.4737, LocalDate.of(2026, 7, 15), true, ZoneId.of("Asia/Shanghai"));
    check(
        Double.isFinite(ev) && solar(ev, 31.2304, 121.4737)[0] < -.832,
        "Native region event solve");
    System.out.println(
        "Native Java engine: " + count + " checks passed; exact fixture peak=" + f.peak.score);
  }

  private static String resource(String name) throws Exception {
    try (java.io.InputStream in = ForecastEngineTest.class.getResourceAsStream("/" + name);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
      byte[] b = new byte[8192];
      int n;
      while ((n = in.read(b)) != -1) out.write(b, 0, n);
      return new String(out.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
    }
  }

  @org.junit.Test
  public void nativeCityIndex() throws Exception {
    CityStore store = new CityStore(resource("city-index.json"));
    org.junit.Assert.assertEquals("1101", store.search("beijing").get(0).id);
    org.junit.Assert.assertEquals("3101", store.search("shanghai").get(0).id);
    org.junit.Assert.assertTrue(store.search("朝阳").stream().anyMatch(c -> c.label.contains("北京市")));
    org.junit.Assert.assertTrue(store.provinces().size() > 30);
    org.junit.Assert.assertTrue(store.children("3101").size() > 10);
    ForecastEngine.Place p = store.gps(31.234567, 121.456789, 7);
    org.junit.Assert.assertEquals(31.234567, p.lat, 0);
    org.junit.Assert.assertEquals(121.456789, p.lon, 0);
    org.junit.Assert.assertEquals("gps", p.source);
    ForecastEngine.Place ip =
        store.normalizeIp(new JSONObject("{\"data\":{\"location\":[\"中国\",\"上海\",\"上海\"]}}"));
    org.junit.Assert.assertEquals("ip", ip.source);
    org.junit.Assert.assertTrue(ip.name.contains("区县未知"));
    org.junit.Assert.assertFalse(ip.json().has("ip"));
  }
}
