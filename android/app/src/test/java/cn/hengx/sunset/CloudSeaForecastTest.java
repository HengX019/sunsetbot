package cn.hengx.sunset;

import static org.junit.Assert.*;

import cn.hengx.sunset.ForecastEngine.Place;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public final class CloudSeaForecastTest {
  private static final Place PLACE = new Place("测试观景点", 31.234567, 121.456789, "gps");
  private static final String MODEL = "gfs_seamless";
  private static final LocalDate DATE = LocalDate.of(2026, 10, 28);

  private JSONObject response(String zone, String... stamps) throws Exception {
    JSONObject hourly = new JSONObject();
    JSONArray time = new JSONArray();
    for (String stamp : stamps) time.put(Instant.parse(stamp).getEpochSecond());
    hourly.put("time", time);
    for (String field :
        Arrays.asList(
            "cloud_cover_low",
            "relative_humidity_2m",
            "temperature_2m",
            "wind_speed_10m",
            "precipitation")) {
      JSONArray values = new JSONArray();
      for (int i = 0; i < stamps.length; i++) {
        values.put(
            field.equals("cloud_cover_low")
                ? 50
                : field.equals("relative_humidity_2m")
                    ? 85
                    : field.equals("temperature_2m")
                        ? -3.5
                        : field.equals("wind_speed_10m") ? 1 : 0);
      }
      hourly.put(field, values);
    }
    return new JSONObject().put("timezone", zone).put("hourly", hourly);
  }

  private CloudSeaForecast parse(JSONObject json) throws Exception {
    return CloudSeaForecast.parse(json, PLACE, MODEL, 1000);
  }

  @Test
  public void assessmentMatchesWebThresholds() {
    assertEquals(CloudSeaForecast.GOOD, CloudSeaForecast.assess(50, 85, 1, 0));
    assertEquals(CloudSeaForecast.GOOD, CloudSeaForecast.assess(50, 85, 7, .2));
    assertEquals(CloudSeaForecast.POSSIBLE, CloudSeaForecast.assess(50, 85, 7, 1));
    assertEquals(CloudSeaForecast.POSSIBLE, CloudSeaForecast.assess(25, 75, .9, 0));
    assertEquals(CloudSeaForecast.WEAK, CloudSeaForecast.assess(24.9, 74.9, 7.1, 0));
    assertNull(CloudSeaForecast.assess(Double.NaN, 85, 1, 0));
    assertNull(CloudSeaForecast.assess(50, Double.NaN, 1, 0));
    assertNull(CloudSeaForecast.assess(50, 85, Double.NaN, 0));
    assertNull(CloudSeaForecast.assess(50, 85, 1, Double.NaN));
  }

  @Test
  public void preservesMissingSamplesAndNegativeTemperature() throws Exception {
    JSONObject json = response("Asia/Shanghai", "2026-10-27T20:00:00Z", "2026-10-27T21:00:00Z");
    json.getJSONObject("hourly").getJSONArray("relative_humidity_2m").put(0, JSONObject.NULL);
    json.getJSONObject("hourly").getJSONArray("precipitation").put(1, "0");
    CloudSeaForecast forecast = parse(json);
    assertEquals(-3.5, forecast.hours.get(0).temperature, 0);
    assertTrue(Double.isNaN(forecast.hours.get(0).humidity));
    assertTrue(Double.isNaN(forecast.hours.get(1).rain));
    assertNull(forecast.hours.get(0).assessment());
    assertNull(forecast.hours.get(1).assessment());
    assertEquals("暂无数据", forecast.dayAssessment(DATE, 0));
    assertEquals("暂无可用的未来时段数据", forecast.morningReference(DATE, 0));
  }

  @Test
  public void morningUsesLocalDateFutureHoursAndGoodBeforePossible() throws Exception {
    JSONObject json =
        response(
            "Asia/Shanghai",
            "2026-10-27T19:00:00Z",
            "2026-10-27T20:00:00Z",
            "2026-10-27T21:00:00Z",
            "2026-10-27T22:00:00Z",
            "2026-10-28T02:00:00Z");
    json.getJSONObject("hourly").getJSONArray("cloud_cover_low").put(2, 25);
    json.getJSONObject("hourly").getJSONArray("relative_humidity_2m").put(2, 75);
    CloudSeaForecast forecast = parse(json);
    assertEquals(Arrays.asList(DATE), forecast.days);
    assertEquals(5, forecast.hoursFor(DATE).size());
    long now = Instant.parse("2026-10-27T20:30:00Z").getEpochSecond();
    assertEquals("06:00", forecast.bestMorning(DATE, now).timeLabel(forecast.zoneId()));
    assertEquals("06:00 条件较好", forecast.morningReference(DATE, now));
    long afterMorning = Instant.parse("2026-10-28T01:30:00Z").getEpochSecond();
    assertNull(forecast.bestMorning(DATE, afterMorning));
    assertEquals("暂无数据", forecast.dayAssessment(DATE, afterMorning));
  }

  @Test
  public void timezoneControlsDayAndRejectsInvalidZone() throws Exception {
    JSONObject json =
        response("America/Los_Angeles", "2026-10-28T06:00:00Z", "2026-10-28T12:00:00Z");
    CloudSeaForecast forecast = parse(json);
    assertEquals(LocalDate.of(2026, 10, 27), forecast.days.get(0));
    assertEquals(DATE, forecast.days.get(1));
    assertEquals("05:00", forecast.bestMorning(DATE, 0).timeLabel(forecast.zoneId()));
    json.put("timezone", "invalid-timezone");
    try {
      parse(json);
      fail("Invalid timezone must fail rather than silently changing local hours");
    } catch (JSONException expected) {
      assertTrue(expected.getMessage().contains("时区"));
    }
  }

  @Test
  public void rejectsIncompleteArraysAndAllMissingTimes() throws Exception {
    JSONObject json = response("Asia/Shanghai", "2026-10-27T20:00:00Z");
    json.getJSONObject("hourly").put("wind_speed_10m", new JSONArray());
    try {
      parse(json);
      fail("Mismatched hourly arrays must fail");
    } catch (JSONException expected) {
      assertTrue(expected.getMessage().contains("不完整"));
    }
    json = response("Asia/Shanghai", "2026-10-27T20:00:00Z");
    json.getJSONObject("hourly").getJSONArray("time").put(0, JSONObject.NULL);
    try {
      parse(json);
      fail("A forecast with no valid timestamps must fail");
    } catch (JSONException expected) {
      assertTrue(expected.getMessage().contains("有效时间"));
    }
  }

  @Test
  public void cacheExpiresAtForecastLocalMidnight() throws Exception {
    JSONObject json = response("Asia/Shanghai", "2026-10-27T20:00:00Z");
    long fetched = Instant.parse("2026-10-28T15:50:00Z").toEpochMilli();
    CloudSeaForecast forecast = CloudSeaForecast.parse(json, PLACE, MODEL, fetched);
    assertTrue(CloudSeaRepository.cacheCurrent(forecast, fetched + 5 * 60 * 1000));
    assertFalse(CloudSeaRepository.cacheCurrent(forecast, fetched + 15 * 60 * 1000));
    assertFalse(CloudSeaRepository.cacheCurrent(forecast, fetched - 1));
    fetched = Instant.parse("2026-10-28T14:00:00Z").toEpochMilli();
    forecast = CloudSeaForecast.parse(json, PLACE, MODEL, fetched);
    assertFalse(CloudSeaRepository.cacheCurrent(forecast, fetched + 30 * 60 * 1000));
  }

  @Test
  public void requestPreservesCoordinatesUnitsModelAndFiveDays() throws Exception {
    URI uri = new URI(CloudSeaRepository.weatherUrl(PLACE, "ecmwf_ifs"));
    Map<String, String> params = new LinkedHashMap<>();
    for (String pair : uri.getRawQuery().split("&")) {
      String[] part = pair.split("=", 2);
      params.put(URLDecoder.decode(part[0], "UTF-8"), URLDecoder.decode(part[1], "UTF-8"));
    }
    assertEquals("https", uri.getScheme());
    assertEquals("api.open-meteo.com", uri.getHost());
    assertEquals("31.234567", params.get("latitude"));
    assertEquals("121.456789", params.get("longitude"));
    assertEquals("ecmwf_ifs", params.get("models"));
    assertEquals("ms", params.get("wind_speed_unit"));
    assertEquals("auto", params.get("timezone"));
    assertEquals("unixtime", params.get("timeformat"));
    assertEquals("5", params.get("forecast_days"));
    assertEquals(
        "cloud_cover_low,relative_humidity_2m,temperature_2m,wind_speed_10m,precipitation",
        params.get("hourly"));
    try {
      CloudSeaRepository.weatherUrl(new Place("invalid", 91, 0, "manual"), MODEL);
      fail("Invalid coordinates should not reach the network");
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("有效"));
    }
    Network.Job job = new Network.Job();
    job.cancel();
    try {
      new CloudSeaRepository().fetch(PLACE, MODEL, job, true);
      fail("Cancelled requests should not reach the network");
    } catch (java.io.InterruptedIOException expected) {
      assertTrue(expected.getMessage().contains("取消"));
    }
  }
}
