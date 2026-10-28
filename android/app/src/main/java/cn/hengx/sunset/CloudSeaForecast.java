package cn.hengx.sunset;

import cn.hengx.sunset.ForecastEngine.Place;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** The web version's cloud-sea weather-condition estimate, using local forecast time. */
public final class CloudSeaForecast {
  public static final String GOOD = "条件较好", POSSIBLE = "有一定条件", WEAK = "条件较弱";
  private static final String[] FIELDS = {
    "cloud_cover_low", "relative_humidity_2m", "temperature_2m", "wind_speed_10m", "precipitation"
  };
  private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

  public static final class Hour {
    public final long stamp;
    public final double low, humidity, temperature, wind, rain;

    public Hour(
        long stamp, double low, double humidity, double temperature, double wind, double rain) {
      this.stamp = stamp;
      this.low = low;
      this.humidity = humidity;
      this.temperature = temperature;
      this.wind = wind;
      this.rain = rain;
    }

    public String assessment() {
      return assess(low, humidity, wind, rain);
    }

    public LocalTime localTime(ZoneId zone) {
      return Instant.ofEpochSecond(stamp).atZone(zone).toLocalTime();
    }

    public String timeLabel(ZoneId zone) {
      return CLOCK.format(localTime(zone));
    }
  }

  public final String placeName, model, zone;
  public final double lat, lon;
  public final long fetchedAtMillis;
  public final List<Hour> hours;
  public final List<LocalDate> days;
  private final ZoneId zoneId;

  private CloudSeaForecast(
      Place place,
      String model,
      ZoneId zoneId,
      long fetchedAtMillis,
      List<Hour> hours,
      List<LocalDate> days) {
    this.placeName = place.name;
    this.lat = place.lat;
    this.lon = place.lon;
    this.model = model;
    this.zoneId = zoneId;
    this.zone = zoneId.getId();
    this.fetchedAtMillis = fetchedAtMillis;
    this.hours = Collections.unmodifiableList(hours);
    this.days = Collections.unmodifiableList(days);
  }

  /** Preserve missing values as NaN; a null hourly sample must never become zero. */
  public static CloudSeaForecast parse(
      JSONObject data, Place place, String model, long fetchedAtMillis) throws JSONException {
    JSONObject hourly = data.optJSONObject("hourly");
    JSONArray time = hourly == null ? null : hourly.optJSONArray("time");
    if (time == null || time.length() == 0) throw new JSONException("天气数据不完整，请稍后重试。");
    JSONArray[] values = new JSONArray[FIELDS.length];
    for (int i = 0; i < FIELDS.length; i++) {
      values[i] = hourly.optJSONArray(FIELDS[i]);
      if (values[i] == null || values[i].length() != time.length()) {
        throw new JSONException("天气数据不完整，请稍后重试。");
      }
    }
    String zone = data.optString("timezone", "Asia/Shanghai");
    if (zone.isEmpty()) zone = "Asia/Shanghai";
    ZoneId zoneId;
    try {
      zoneId = ZoneId.of(zone);
    } catch (DateTimeException e) {
      throw new JSONException("天气数据的时区无效。");
    }
    List<Hour> hours = new ArrayList<>();
    LinkedHashSet<LocalDate> dates = new LinkedHashSet<>();
    for (int i = 0; i < time.length(); i++) {
      double stamp = number(time, i);
      if (!Double.isFinite(stamp) || stamp > Long.MAX_VALUE || stamp < Long.MIN_VALUE) continue;
      long epoch = (long) stamp;
      LocalDate day;
      try {
        day = Instant.ofEpochSecond(epoch).atZone(zoneId).toLocalDate();
      } catch (DateTimeException e) {
        continue;
      }
      if (dates.size() < 5 || dates.contains(day)) {
        dates.add(day);
        hours.add(
            new Hour(
                epoch,
                number(values[0], i),
                number(values[1], i),
                number(values[2], i),
                number(values[3], i),
                number(values[4], i)));
      }
    }
    if (dates.isEmpty()) throw new JSONException("天气数据没有有效时间。");
    return new CloudSeaForecast(
        place, model, zoneId, fetchedAtMillis, hours, new ArrayList<>(dates));
  }

  private static double number(JSONArray array, int i) {
    Object value = array.opt(i);
    if (!(value instanceof Number)) return Double.NaN;
    double n = ((Number) value).doubleValue();
    return Double.isFinite(n) ? n : Double.NaN;
  }

  public static String assess(double low, double humidity, double wind, double rain) {
    if (!Double.isFinite(low)
        || !Double.isFinite(humidity)
        || !Double.isFinite(wind)
        || !Double.isFinite(rain)) return null;
    int score =
        (low >= 50 ? 2 : low >= 25 ? 1 : 0)
            + (humidity >= 85 ? 2 : humidity >= 75 ? 1 : 0)
            + (wind >= 1 && wind <= 7 ? 1 : 0);
    if (rain >= 1) score -= 2;
    else if (rain >= .2) score--;
    return score >= 4 ? GOOD : score >= 2 ? POSSIBLE : WEAK;
  }

  public ZoneId zoneId() {
    return zoneId;
  }

  public List<Hour> hoursFor(LocalDate date) {
    List<Hour> result = new ArrayList<>();
    for (Hour hour : hours) {
      if (Instant.ofEpochSecond(hour.stamp).atZone(zoneId).toLocalDate().equals(date))
        result.add(hour);
    }
    return Collections.unmodifiableList(result);
  }

  private List<Hour> futureMorning(LocalDate date, long nowEpochSeconds) {
    List<Hour> result = new ArrayList<>();
    for (Hour hour : hoursFor(date)) {
      int clockHour = hour.localTime(zoneId).getHour();
      if (hour.stamp >= nowEpochSeconds && clockHour >= 4 && clockHour < 10) result.add(hour);
    }
    return result;
  }

  /** Prefer the first good hour, then the first possible hour, as on the web page. */
  public Hour bestMorning(LocalDate date, long nowEpochSeconds) {
    List<Hour> upcoming = futureMorning(date, nowEpochSeconds);
    for (Hour hour : upcoming) if (GOOD.equals(hour.assessment())) return hour;
    for (Hour hour : upcoming) if (POSSIBLE.equals(hour.assessment())) return hour;
    return null;
  }

  public String dayAssessment(LocalDate date, long nowEpochSeconds) {
    Hour best = bestMorning(date, nowEpochSeconds);
    if (best != null) return best.assessment();
    for (Hour hour : futureMorning(date, nowEpochSeconds))
      if (hour.assessment() != null) return WEAK;
    return "暂无数据";
  }

  public String morningReference(LocalDate date, long nowEpochSeconds) {
    Hour best = bestMorning(date, nowEpochSeconds);
    if (best != null) return best.timeLabel(zoneId) + " " + best.assessment();
    for (Hour hour : futureMorning(date, nowEpochSeconds)) {
      if (hour.assessment() != null) return "条件较弱";
    }
    return "暂无可用的未来时段数据";
  }
}
