package cn.hengx.sunset;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;

/** Android runtime permission and foreground GPS. No JavaScript dependency. */
public final class DeviceLocator {
  public interface Listener {
    void location(Location fix);

    void error(String code, String message);
  }

  public static final int PERMISSION = 4107;
  private final Activity activity;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LocationManager manager;
  private Listener callback;
  private LocationListener listener;
  private Location best;
  private AlertDialog prompt;
  private Runnable timeout;
  public boolean permissionDialogOpen;
  private boolean permissionBlocked;
  private int generation;

  public DeviceLocator(Activity a) {
    activity = a;
    manager = (LocationManager) a.getSystemService(Context.LOCATION_SERVICE);
  }

  public boolean fine() {
    return activity.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        == PackageManager.PERMISSION_GRANTED;
  }

  public void locate(Listener l) {
    stop();
    callback = l;
    if (!fine()) reminder();
    else {
      permissionBlocked = false;
      gps();
    }
  }

  private void reminder() {
    // A flag saved by an older APK does not describe the current system permission state.
    // Only an actual denied response in this session can send the next attempt to settings.
    boolean settings = permissionBlocked;
    prompt =
        new AlertDialog.Builder(activity)
            .setTitle("请允许访问位置信息")
            .setMessage(
                Build.VERSION.SDK_INT >= 31
                    ? "应用通过 GPS 查询您所在地的朝霞、晚霞。请允许位置访问，并选择“精确位置”。只获取前台单次位置。"
                    : "应用通过 GPS 查询您所在地的朝霞、晚霞，请允许访问位置信息。只获取前台单次位置，不在后台追踪。")
            .setPositiveButton(
                settings ? "打开应用设置" : "去允许",
                (d, w) -> {
                  prompt = null;
                  if (callback == null) return;
                  if (settings) {
                    fail("permission_denied", "请在系统应用权限中允许访问位置");
                    openSettings();
                  } else {
                    permissionDialogOpen = true;
                    try {
                      activity.requestPermissions(
                          new String[] {
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                          },
                          PERMISSION);
                    } catch (RuntimeException error) {
                      permissionDialogOpen = false;
                      permissionBlocked = true;
                      fail("permission_denied", "系统未能打开位置授权，请在应用设置中允许访问位置");
                    }
                  }
                })
            .setNegativeButton(
                "暂不开启",
                (d, w) -> {
                  prompt = null;
                  fail("permission_denied", "未允许位置权限，自动使用大致位置");
                })
            .setOnCancelListener(
                d -> {
                  prompt = null;
                  fail("permission_denied", "未允许位置权限，自动使用大致位置");
                })
            .create();
    prompt.show();
  }

  public void onPermissions(int code, int[] results) {
    if (code != PERMISSION) return;
    permissionDialogOpen = false;
    permissionBlocked =
        results.length > 0
            && !fine()
            && !activity.shouldShowRequestPermissionRationale(
                Manifest.permission.ACCESS_FINE_LOCATION);
    if (callback == null) return;
    if (fine()) gps();
    else fail("permission_denied", "未允许精确位置，自动使用大致位置");
  }

  @SuppressWarnings("deprecation")
  private void gps() {
    if (!fine()) {
      fail("permission_denied", "请允许精确位置");
      return;
    }
    if (manager == null) {
      fail("unavailable", "设备不支持定位");
      return;
    }
    final int token = generation;
    try {
      if (Build.VERSION.SDK_INT >= 28 && !manager.isLocationEnabled()
          || !manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
        fail("disabled", "系统定位已关闭，自动使用大致位置");
        return;
      }
      Location cached = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
      if (usable(cached)) {
        best = cached;
        if (age(cached) <= 30000 && cached.getAccuracy() <= 100) {
          success(cached);
          return;
        }
      }
      listener =
          new LocationListener() {
            public void onLocationChanged(Location l) {
              if (token != generation || !usable(l)) return;
              if (best == null
                  || l.getAccuracy() < best.getAccuracy()
                  || age(best) - age(l) > 60000) best = l;
              if (l.getAccuracy() <= 250) success(best);
            }

            public void onProviderDisabled(String p) {
              if (token == generation && p.equals(LocationManager.GPS_PROVIDER))
                fail("disabled", "定位服务已关闭");
            }

            public void onProviderEnabled(String p) {}

            public void onStatusChanged(String p, int status, Bundle extras) {}
          };
      manager.requestLocationUpdates(
          LocationManager.GPS_PROVIDER, 1000, 0, listener, Looper.getMainLooper());
      timeout =
          () -> {
            if (token != generation) return;
            if (usable(best)) success(best);
            else fail("timeout", "GPS 定位超时，自动使用大致位置");
          };
      main.postDelayed(timeout, 25000);
    } catch (SecurityException e) {
      fail("permission_denied", "位置权限已关闭");
    } catch (RuntimeException e) {
      fail("unavailable", "GPS 暂不可用，自动使用大致位置");
    }
  }

  private static long age(Location l) {
    long age = (SystemClock.elapsedRealtimeNanos() - l.getElapsedRealtimeNanos()) / 1000000;
    return age < 0 ? Long.MAX_VALUE : age;
  }

  private static boolean usable(Location l) {
    return l != null
        && "gps".equals(l.getProvider())
        && l.hasAccuracy()
        && l.getAccuracy() > 0
        && l.getAccuracy() < 50000
        && age(l) <= 120000
        && Double.isFinite(l.getLatitude() + l.getLongitude())
        && Math.abs(l.getLatitude()) <= 90
        && Math.abs(l.getLongitude()) <= 180;
  }

  private void success(Location l) {
    Listener c = callback;
    stop();
    if (c != null) c.location(l);
  }

  private void fail(String code, String message) {
    Listener c = callback;
    stop();
    if (c != null) c.error(code, message);
  }

  public void stop() {
    generation++;
    callback = null;
    best = null;
    if (timeout != null) main.removeCallbacks(timeout);
    timeout = null;
    if (manager != null && listener != null)
      try {
        manager.removeUpdates(listener);
      } catch (RuntimeException ignored) {
      }
    listener = null;
    if (prompt != null) {
      prompt.setOnCancelListener(null);
      prompt.dismiss();
      prompt = null;
    }
  }

  public void openSettings() {
    try {
      Intent i =
          fine()
              ? new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
              : new Intent(
                  Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                  Uri.parse("package:" + activity.getPackageName()));
      activity.startActivity(i);
    } catch (ActivityNotFoundException ignored) {
    }
  }
}
