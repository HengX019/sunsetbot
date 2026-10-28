package cn.hengx.sunset;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

public final class Network {
  public static final class Job {
    public volatile boolean cancelled;
    private final Set<HttpURLConnection> connections = ConcurrentHashMap.newKeySet();

    public void disconnect() {
      for (HttpURLConnection c : connections) c.disconnect();
      connections.clear();
    }

    public void cancel() {
      cancelled = true;
      disconnect();
    }

    public void check() throws InterruptedIOException {
      if (cancelled || Thread.currentThread().isInterrupted())
        throw new InterruptedIOException("请求已取消");
    }
  }

  public static final class HttpError extends IOException {
    public int status;
    public long retry;

    HttpError(int s, String m, long r) {
      super(m);
      status = s;
      retry = r;
    }
  }

  private static final Set<String> HOSTS =
      new HashSet<>(
          Arrays.asList(
              "api.open-meteo.com",
              "air-quality-api.open-meteo.com",
              "geocoding-api.open-meteo.com",
              "myip.ipip.net",
              "get.geojs.io",
              "api.ip.sb",
              "tile.openstreetmap.org"));

  private static String encode(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      throw new AssertionError(e);
    }
  }

  public static String url(String base, Object... args) {
    StringBuilder s = new StringBuilder(base);
    for (int i = 0; i + 1 < args.length; i += 2) {
      s.append(i == 0 ? '?' : '&');
      s.append(encode(args[i].toString())).append('=').append(encode(args[i + 1].toString()));
    }
    return s.toString();
  }

  public static byte[] bytes(String address, Job job, int max) throws Exception {
    job.check();
    URL url = new URL(address);
    if (!url.getProtocol().equals("https")
        || !HOSTS.contains(url.getHost())
        || url.getUserInfo() != null
        || url.getPort() != -1 && url.getPort() != 443) throw new IOException("不允许的接口地址");
    HttpURLConnection c = (HttpURLConnection) url.openConnection();
    job.connections.add(c);
    try {
      job.check();
      c.setConnectTimeout(12000);
      c.setReadTimeout(25000);
      c.setInstanceFollowRedirects(false);
      c.setRequestProperty("User-Agent", "HengX-Sunset-Native/2.0.0 (weather reference)");
      int code = c.getResponseCode();
      if (code != 200) {
        long retry = 0;
        try {
          retry = Long.parseLong(c.getHeaderField("Retry-After")) * 1000;
        } catch (Exception ignored) {
        }
        String reason = "接口 HTTP " + code;
        InputStream error = c.getErrorStream();
        if (error != null)
          try (InputStream in = error) {
            byte[] b = new byte[2048];
            int n = in.read(b);
            if (n > 0) {
              JSONObject j = new JSONObject(new String(b, 0, n, StandardCharsets.UTF_8));
              reason = j.optString("reason", reason);
            }
          } catch (Exception ignored) {
          }
        throw new HttpError(code, reason, retry);
      }
      try (InputStream in = c.getInputStream();
          ByteArrayOutputStream out = new ByteArrayOutputStream()) {
        byte[] b = new byte[16384];
        int n;
        while ((n = in.read(b)) != -1) {
          job.check();
          if (out.size() + n > max) throw new IOException("接口响应过大");
          out.write(b, 0, n);
        }
        return out.toByteArray();
      }
    } finally {
      job.connections.remove(c);
      c.disconnect();
    }
  }

  public static Object json(String address, Job job) throws Exception {
    return new JSONTokener(new String(bytes(address, job, 8 * 1024 * 1024), StandardCharsets.UTF_8))
        .nextValue();
  }
}
