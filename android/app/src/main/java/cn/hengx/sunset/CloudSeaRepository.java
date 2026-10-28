package cn.hengx.sunset;

import cn.hengx.sunset.ForecastEngine.Place;
import java.io.IOException;
import java.time.Instant;
import org.json.JSONObject;

/** A small, cancellable foreground data source independent of the sunset approximation. */
public final class CloudSeaRepository {
  private static final long CACHE_MILLIS = 30 * 60 * 1000L;
  private CloudSeaForecast cached;

  public CloudSeaForecast fetch(Place place, String model, Network.Job job, boolean force)
      throws Exception {
    job.check();
    String address = weatherUrl(place, model);
    long now = System.currentTimeMillis();
    synchronized (this) {
      if (!force
          && cached != null
          && cached.lat == place.lat
          && cached.lon == place.lon
          && cached.placeName.equals(place.name)
          && cached.model.equals(model)
          && cacheCurrent(cached, now)) {
        return cached;
      }
    }
    Object response = Network.json(address, job);
    job.check();
    if (!(response instanceof JSONObject)) throw new IOException("天气数据不完整，请稍后重试。");
    CloudSeaForecast result =
        CloudSeaForecast.parse((JSONObject) response, place, model, System.currentTimeMillis());
    job.check();
    synchronized (this) {
      cached = result;
    }
    return result;
  }

  static boolean cacheCurrent(CloudSeaForecast forecast, long nowMillis) {
    return nowMillis >= forecast.fetchedAtMillis
        && nowMillis - forecast.fetchedAtMillis < CACHE_MILLIS
        && Instant.ofEpochMilli(nowMillis)
            .atZone(forecast.zoneId())
            .toLocalDate()
            .equals(
                Instant.ofEpochMilli(forecast.fetchedAtMillis)
                    .atZone(forecast.zoneId())
                    .toLocalDate());
  }

  static String weatherUrl(Place place, String model) throws IOException {
    if (place == null
        || !Double.isFinite(place.lat)
        || !Double.isFinite(place.lon)
        || Math.abs(place.lat) > 90
        || Math.abs(place.lon) > 180) {
      throw new IOException("请先选择有效的查询地点。");
    }
    if (!"gfs_seamless".equals(model) && !"ecmwf_ifs".equals(model)) {
      throw new IOException("不支持的预报模型。");
    }
    return Network.url(
        ForecastRepository.WEATHER,
        "latitude",
        place.lat,
        "longitude",
        place.lon,
        "hourly",
        "cloud_cover_low,relative_humidity_2m,temperature_2m,wind_speed_10m,precipitation",
        "models",
        model,
        "wind_speed_unit",
        "ms",
        "timezone",
        "auto",
        "timeformat",
        "unixtime",
        "forecast_days",
        5);
  }
}
