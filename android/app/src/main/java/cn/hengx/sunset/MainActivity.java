package cn.hengx.sunset;

import static cn.hengx.sunset.ForecastEngine.*;
import static cn.hengx.sunset.ForecastRepository.*;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.location.Location;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Entire app UI is Android Views. There is no WebView or HTML asset in this application. */
// Chinese UI strings are composed from native forecast values; localization is not enabled.
@android.annotation.SuppressLint("SetTextI18n")
public final class MainActivity extends Activity {
  private static final int BG = 0xff0f1a29,
      CARD = 0xff16273d,
      INK = 0xffeeeeee,
      MUTED = 0xffb5c3d2,
      PINK = 0xfff0a4e4,
      BLUE = 0xff66d8f3,
      ACCENT = 0xff1d3452;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService workers = Executors.newFixedThreadPool(4);
  private final ExecutorService ipPool = Executors.newFixedThreadPool(3);
  private CityStore cities;
  private ForecastRepository repository;
  private CloudSeaRepository seaRepository;
  private CloudSeaForecast seaForecast;
  private LocalDate seaDay;
  private TextView seaStatus, seaPlaceLabel;
  private Button seaRefresh;
  private LinearLayout seaResults;
  private Network.Job seaJob;
  private int seaSerial;
  private String seaRequestKey = "", seaMessage = "";
  private DeviceLocator locator;
  private LinearLayout root, body, tabs;
  private TextView locationLabel, statusLabel, qualityLabel, aodLabel, eventLabel;
  private ProgressBar progress;
  private AutoCompleteTextView search;
  private ForecastMapView[] minis = new ForecastMapView[2];
  private TextView[] homeMapChips = new TextView[2];
  private ForecastMapView bigMap;
  private TextView mapStatus;
  private Region[] homeRegions, bigRegions;
  private Forecast forecast;
  private Place place;
  private int page = 0, event = 1;
  private boolean ec = true, agreed, ready, foreground, replay, sectionMode;
  private String locationMessage = "同意后获取当前位置", statusMessage = "", mapMessage = "拖动或缩放后自动更新当前视野";
  private String locationError = "";
  private Network.Job forecastJob, ipJob, homeJob, mapJob;
  private int pointSerial, locationSerial, homeSerial, mapSerial;
  private Runnable mapTimer, homeRetry, mapRetry;
  private long mapCooldown;
  private boolean destroyed;
  private boolean interruptedPermission;
  private boolean locationAttempted;

  private int dp(float v) {
    return Math.round(v * getResources().getDisplayMetrics().density);
  }

  private String model() {
    return ec ? "ecmwf_ifs" : "gfs_seamless";
  }

  private String eventKey() {
    return (event % 2 == 1 ? "set_" : "rise_") + event / 2;
  }

  private String eventLabel(int e) {
    return new String[] {"今天朝霞", "今天晚霞", "明天朝霞", "明天晚霞"}[e];
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    agreed = state != null && state.getBoolean("agreed", false);
    page = state == null ? 0 : state.getInt("page", 0);
    repository = new ForecastRepository(this);
    seaRepository = new CloudSeaRepository();
    locator = new DeviceLocator(this);
    cities = new CityStore();
    event = getPreferences(0).getInt("event", 1);
    ec = getPreferences(0).getBoolean("ec", true);
    buildRoot();
    showPage(page);
    workers.submit(
        () -> {
          try {
            CityStore store = new CityStore(this);
            main.post(
                () -> {
                  if (destroyed) return;
                  cities = store;
                  ready = true;
                  showPage(page);
                });
          } catch (Exception | LinkageError e) {
            android.util.Log.e("SunsetStartup", "City index unavailable", e);
            main.post(
                () -> {
                  if (destroyed) return;
                  ready = true;
                  showPage(page);
                  if (forecastJob == null) status("城市索引暂不可用，仍可使用定位或经纬度查询。", false);
                });
          }
          try {
            ForecastMapView.loadGeography(this);
            main.post(
                () -> {
                  if (destroyed) return;
                  for (ForecastMapView mini : minis) if (mini != null) mini.invalidate();
                  if (bigMap != null) bigMap.invalidate();
                });
          } catch (Exception | LinkageError e) {
            android.util.Log.e("SunsetStartup", "Map outline unavailable", e);
          }
        });
  }

  private void buildRoot() {
    root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(BG);
    if (Build.VERSION.SDK_INT >= 30) getWindow().setDecorFitsSystemWindows(false);
    else
      getWindow()
          .getDecorView()
          .setSystemUiVisibility(
              View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                  | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                  | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    root.setOnApplyWindowInsetsListener(
        (v, i) -> {
          if (Build.VERSION.SDK_INT >= 30) {
            android.graphics.Insets
                bars =
                    i.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout()),
                ime = i.getInsets(WindowInsets.Type.ime());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, ime.bottom));
          } else
            v.setPadding(
                i.getSystemWindowInsetLeft(),
                i.getSystemWindowInsetTop(),
                i.getSystemWindowInsetRight(),
                i.getSystemWindowInsetBottom());
          return i;
        });
    setContentView(root);
    root.requestApplyInsets();
    LinearLayout heading = row();
    heading.setPadding(dp(10), dp(10), dp(10), dp(10));
    heading.setBackground(headerBackground());
    ImageView logo = new ImageView(this);
    try (java.io.InputStream image = getAssets().open("site-logo.png")) {
      logo.setImageBitmap(BitmapFactory.decodeStream(image));
    } catch (Exception ignored) {
    }
    logo.setContentDescription("晚霞");
    heading.addView(logo, new LinearLayout.LayoutParams(dp(32), dp(32)));
    TextView title =
        text(
            "火烧云分析与记录",
            getResources().getDisplayMetrics().widthPixels
                        / getResources().getDisplayMetrics().density
                    < 360
                ? 16
                : 17,
            0xfff5eec9);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(dp(8), 0, 0, 0);
    heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    Button menu =
        button(
            "☰",
            () ->
                tabs.setVisibility(
                    tabs.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    menu.setContentDescription("导航菜单");
    menu.setTextSize(25);
    menu.setTextColor(0xffb5c3d2);
    GradientDrawable outline = new GradientDrawable();
    outline.setColor(0x00000000);
    outline.setCornerRadius(dp(5));
    outline.setStroke(dp(1), 0xff52647b);
    menu.setBackground(outline);
    heading.addView(menu, new LinearLayout.LayoutParams(dp(44), dp(40)));
    root.addView(heading);
    tabs = column();
    tabs.setBackground(headerBackground());
    tabs.setPadding(dp(12), 0, dp(12), dp(6));
    tabs.setVisibility(View.GONE);
    root.addView(tabs);
    int[] destinations = {0, 2, 1, 7, 3, 5, 6, 4};
    String[] labels = {"主页", "详情页", "地图", "云海预测", "记录", "准确率", "知识", "关于"};
    for (int i = 0; i < labels.length; i++) {
      final int target = destinations[i];
      Button link =
          button(
              labels[i],
              () -> {
                hideKeyboard();
                tabs.setVisibility(View.GONE);
                if (target == 2) sectionMode = false;
                showPage(target);
              });
      link.setTag(target);
      link.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
      link.setTextColor(INK);
      link.setBackgroundColor(0x00000000);
      tabs.addView(link, new LinearLayout.LayoutParams(-1, dp(40)));
    }
    body = column();
    root.addView(body, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  @Override
  protected void onStart() {
    super.onStart();
    foreground = true;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!agreed) {
      if (agreement == null) showAgreement();
      return;
    }
    if (!locator.permissionDialogOpen) {
      if (interruptedPermission) recoverInterruptedPermission();
      else if (!locationAttempted) {
        showPage(page);
        locate();
      }
    }
  }

  @Override
  protected void onStop() {
    foreground = false;
    locationAttempted = false;
    if (locator.permissionDialogOpen) interruptedPermission = true;
    locationSerial++;
    pointSerial++;
    homeSerial++;
    mapSerial++;
    locator.stop();
    cancel(forecastJob);
    cancel(ipJob);
    cancel(homeJob);
    cancel(mapJob);
    stopCloudSea();
    if (mapTimer != null) main.removeCallbacks(mapTimer);
    if (homeRetry != null) main.removeCallbacks(homeRetry);
    if (mapRetry != null) main.removeCallbacks(mapRetry);
    disposeMaps();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    locator.stop();
    cancel(forecastJob);
    cancel(ipJob);
    cancel(homeJob);
    cancel(mapJob);
    stopCloudSea();
    workers.shutdownNow();
    ipPool.shutdownNow();
    main.removeCallbacksAndMessages(null);
    disposeMaps();
    if (agreement != null) agreement.dismiss();
    super.onDestroy();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    state.putBoolean("agreed", agreed);
    state.putInt("page", page);
    super.onSaveInstanceState(state);
  }

  @Override
  public void onBackPressed() {
    if (page != 0) {
      showPage(0);
      return;
    }
    super.onBackPressed();
  }

  @Override
  public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(code, permissions, results);
    locator.onPermissions(code, results);
    if (code == DeviceLocator.PERMISSION && interruptedPermission && foreground && agreed)
      recoverInterruptedPermission();
  }

  private void recoverInterruptedPermission() {
    interruptedPermission = false;
    locationAttempted = true;
    showPage(page);
    if (locator.fine()) {
      locate();
      return;
    }
    locationError = "permission_denied";
    ipFallback(++locationSerial, "未允许位置权限");
  }

  private AlertDialog agreement;

  private void showAgreement() {
    final boolean firstEntry = !agreed;
    LinearLayout box = column();
    box.setBackground(rounded(CARD));
    TextView title = text("免责声明", 20, 0xffffffff);
    title.setTypeface(null, Typeface.BOLD);
    title.setPadding(dp(18), dp(14), dp(18), dp(14));
    title.setBackground(headerBackground());
    box.addView(title);
    ScrollView scroll = new ScrollView(this);
    LinearLayout content = column();
    content.setPadding(dp(18), dp(16), dp(18), dp(16));
    content.addView(
        text(
            "本应用由 HengX 公益制作，旨在为摄影爱好者提供便捷的朝霞、晚霞预测参考。\n\n"
                + "因原网站已下架，为方便大家继续查询朝霞、晚霞信息，作者制作了本项目，免费提供使用，不以本应用进行盈利。\n\n"
                + "本应用为个人独立制作，与原网站及其作者无隶属或合作关系。预报结果仅供参考，请结合现场情况判断。\n\n"
                + "定位说明：点击“同意”后，应用会检查并申请前台位置权限，优先 GPS，失败后自动 IP 大致定位。只获取单次位置，不后台追踪；查询坐标会发送至"
                + " Open-Meteo，记录和缓存保存在本设备。\n\n"
                + "如有内容涉嫌侵权，请通过作者主页联系，我们将在核实后及时处理。",
            14,
            INK));
    scroll.addView(content);
    box.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    Button accept =
        button(
            "同意",
            () -> {
              if (agreement != null) agreement.dismiss();
              agreement = null;
              agreed = true;
              if (firstEntry) locate();
            });
    LinearLayout.LayoutParams acceptParams = new LinearLayout.LayoutParams(-1, dp(48));
    acceptParams.setMargins(dp(18), dp(6), dp(18), dp(16));
    box.addView(accept, acceptParams);
    agreement =
        new AlertDialog.Builder(this)
            .setView(box)
            .setOnCancelListener(
                dialog -> {
                  agreement = null;
                  if (!agreed) finish();
                })
            .create();
    agreement.show();
    android.view.Window window = agreement.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setLayout(
          getResources().getDisplayMetrics().widthPixels - dp(20),
          (int) (getResources().getDisplayMetrics().heightPixels * .88));
    }
  }

  private void cancel(Network.Job j) {
    if (j != null) j.cancel();
  }

  private void disposeMaps() {
    for (ForecastMapView v : minis) if (v != null) v.dispose();
    if (bigMap != null) bigMap.dispose();
    minis = new ForecastMapView[2];
    bigMap = null;
  }

  private TextView text(String s, float size, int color) {
    TextView t = new TextView(this);
    t.setText(s);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setLineSpacing(dp(3), 1.12f);
    return t;
  }

  private LinearLayout row() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  private LinearLayout column() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  private GradientDrawable rounded(int color) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(7));
    return d;
  }

  private Button button(String label, Runnable action) {
    Button b = new Button(this);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setMinHeight(dp(44));
    b.setMinimumHeight(dp(44));
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(8), dp(6), dp(8), dp(6));
    b.setStateListAnimator(null);
    styleButton(
        b,
        label.equals("清除查询记录") ? 0xfff5eec9 : 0xffa5d8ff,
        label.equals("清除查询记录") ? 0xff261605 : 0xff0a0a0a);
    b.setOnClickListener(v -> action.run());
    return b;
  }

  private void styleButton(Button button, int background, int foreground) {
    GradientDrawable shape = new GradientDrawable();
    shape.setColor(background);
    shape.setCornerRadius(dp(4));
    button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x22000000), shape, null));
    button.setTextColor(foreground);
  }

  private GradientDrawable headerBackground() {
    return new GradientDrawable(
        GradientDrawable.Orientation.LEFT_RIGHT, new int[] {0xff263c57, 0xff1d3049});
  }

  private LinearLayout legacyPanel(LinearLayout parent, String title) {
    LinearLayout panel = column();
    panel.setBackground(rounded(CARD));
    LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
    params.bottomMargin = dp(16);
    parent.addView(panel, params);
    TextView heading = text(title, 16, 0xfff5eec9);
    heading.setTypeface(null, Typeface.BOLD);
    heading.setPadding(dp(12), dp(10), dp(12), dp(10));
    heading.setBackground(headerBackground());
    panel.addView(heading);
    LinearLayout content = column();
    content.setPadding(dp(12), dp(12), dp(12), dp(12));
    panel.addView(content);
    return content;
  }

  private void divider(LinearLayout parent) {
    View line = new View(this);
    line.setBackgroundColor(0x886c757d);
    parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
  }

  private TextView resultRow(
      LinearLayout parent, String name, String value, int color, boolean border) {
    LinearLayout row = row();
    row.setPadding(dp(2), dp(10), dp(2), dp(10));
    TextView label = text(name, 14, INK);
    label.setTypeface(null, Typeface.BOLD);
    TextView result = text(value, 14, color);
    result.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    row.addView(label, new LinearLayout.LayoutParams(0, -2, .43f));
    row.addView(result, new LinearLayout.LayoutParams(0, -2, .57f));
    parent.addView(row);
    if (border) divider(parent);
    return result;
  }

  private void fold(LinearLayout parent, String heading, String value) {
    TextView trigger = text("▸ " + heading, 14, INK);
    trigger.setPadding(0, dp(10), 0, dp(10));
    TextView details = text(value, 14, INK);
    details.setVisibility(View.GONE);
    trigger.setOnClickListener(
        v -> {
          boolean open = details.getVisibility() == View.VISIBLE;
          details.setVisibility(open ? View.GONE : View.VISIBLE);
          trigger.setText((open ? "▸ " : "▾ ") + heading);
        });
    parent.addView(trigger);
    parent.addView(details);
  }

  private void footer(LinearLayout parent) {
    gap(parent, 24);
    LinearLayout bottom = row();
    bottom.setPadding(dp(8), dp(16), dp(8), dp(16));
    TextView left = text("火烧云预测与记录\n界面参考 Sunsetbot", 12, INK);
    left.setGravity(Gravity.CENTER);
    TextView right = text("本地复刻版 · 非官方网站\n关于本站 / 使用说明", 12, INK);
    right.setGravity(Gravity.CENTER);
    right.setOnClickListener(v -> showPage(4));
    bottom.addView(left, new LinearLayout.LayoutParams(0, -2, 1));
    bottom.addView(right, new LinearLayout.LayoutParams(0, -2, 1));
    parent.addView(bottom);
  }

  private void gap(LinearLayout p, int height) {
    Space s = new Space(this);
    p.addView(s, new LinearLayout.LayoutParams(1, dp(height)));
  }

  private LinearLayout card(LinearLayout parent) {
    LinearLayout c = column();
    c.setPadding(dp(12), dp(12), dp(12), dp(12));
    c.setBackground(rounded(CARD));
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
    p.bottomMargin = dp(16);
    parent.addView(c, p);
    return c;
  }

  private LinearLayout scrollPage() {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    LinearLayout l = column();
    l.setPadding(dp(12), dp(12), dp(12), dp(18));
    scroll.addView(l);
    body.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
    return l;
  }

  private void showPage(int value) {
    if (destroyed) return;
    page = value;
    disposeMaps();
    body.removeAllViews();
    locationLabel = statusLabel = qualityLabel = aodLabel = eventLabel = null;
    progress = null;
    search = null;
    mapStatus = null;
    seaStatus = null;
    seaPlaceLabel = null;
    seaRefresh = null;
    seaResults = null;
    if (page != 7) stopCloudSea();
    if (mapTimer != null) main.removeCallbacks(mapTimer);
    if (mapRetry != null) main.removeCallbacks(mapRetry);
    if (homeRetry != null) main.removeCallbacks(homeRetry);
    cancel(mapJob);
    mapSerial++;
    if (page != 0) {
      cancel(homeJob);
      homeSerial++;
    }
    for (int i = 0; i < tabs.getChildCount(); i++) {
      Button link = (Button) tabs.getChildAt(i);
      link.setTextColor(((Integer) link.getTag()) == page ? 0xffffffff : 0xffadb5bd);
    }
    if (page == 0) home();
    else if (page == 1) mapPage();
    else if (page == 2) details();
    else if (page == 3) records();
    else if (page == 5) accuracyPage();
    else if (page == 6) knowledgePage();
    else if (page == 7) cloudSeaPage();
    else about();
  }

  private String selectorTime() {
    return new String[] {"今天日出", "今天日落", "明天日出", "明天日落"}[event];
  }

  private void periods(LinearLayout parent, boolean map) {
    LinearLayout line = row();
    Button time =
        button(
            selectorTime() + "  ▾",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("选择时段")
                    .setSingleChoiceItems(
                        new String[] {"今天日出", "今天日落", "明天日出", "明天日落"},
                        event,
                        (dialog, index) -> {
                          dialog.dismiss();
                          hideKeyboard();
                          event = index;
                          getPreferences(0).edit().putInt("event", event).apply();
                          homeRegions = null;
                          showPage(map ? 1 : 0);
                          if (!map) fetchForecast();
                        })
                    .setNegativeButton("取消", null)
                    .show());
    styleButton(time, ACCENT, INK);
    time.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    line.addView(time, new LinearLayout.LayoutParams(0, dp(44), 1));
    Space spacer = new Space(this);
    line.addView(spacer, new LinearLayout.LayoutParams(dp(8), 1));
    Button source =
        button(
            "数据源: " + (ec ? "EC" : "GFS") + "  ▾",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("数据源")
                    .setSingleChoiceItems(
                        new String[] {"GFS", "EC"},
                        ec ? 1 : 0,
                        (dialog, index) -> {
                          dialog.dismiss();
                          hideKeyboard();
                          ec = index == 1;
                          getPreferences(0).edit().putBoolean("ec", ec).apply();
                          homeRegions = null;
                          showPage(map ? 1 : 0);
                          if (!map) fetchForecast();
                        })
                    .setNegativeButton("取消", null)
                    .show());
    styleButton(source, ACCENT, INK);
    source.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    line.addView(source, new LinearLayout.LayoutParams(0, dp(44), 1));
    if (!map) {
      Space gap = new Space(this);
      line.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
      Button submit = button("搜索", this::searchNow);
      styleButton(submit, 0xff0d6efd, 0xffffffff);
      line.addView(submit, new LinearLayout.LayoutParams(dp(60), dp(44)));
    }
    parent.addView(line);
  }

  private void home() {
    LinearLayout l = scrollPage();
    LinearLayout intro = card(l);
    intro.addView(
        text(
            "查看当地及周边的朝霞、晚霞条件。选择时段和数据源，搜索城市或区县即可查询。基于 GFS / ECMWF 与 CAMS 数据，本地模型估算，仅供参考。", 14, INK));
    gap(intro, 12);
    intro.addView(button("查看云海预测 →", () -> showPage(7)));
    periods(l, false);
    gap(l, 8);
    LinearLayout searchRow = row();
    search = new AutoCompleteTextView(this);
    search.setTextSize(16);
    search.setSingleLine(true);
    search.setTextColor(INK);
    search.setHintTextColor(0xff8291a0);
    search.setHint("输入城市或区县");
    search.setThreshold(1);
    search.setPadding(dp(10), 0, dp(10), 0);
    GradientDrawable input = rounded(ACCENT);
    input.setCornerRadius(dp(4));
    search.setBackground(input);
    search.setImeOptions(3);
    searchRow.addView(search, new LinearLayout.LayoutParams(0, dp(44), 1));
    Space searchGap = new Space(this);
    searchRow.addView(searchGap, new LinearLayout.LayoutParams(dp(8), 1));
    Button clear =
        button(
            "清除",
            () -> {
              if (search != null) {
                search.setText("");
                search.requestFocus();
              }
            });
    styleButton(clear, 0xfff5eec9, 0xff261605);
    searchRow.addView(clear, new LinearLayout.LayoutParams(dp(60), dp(44)));
    l.addView(searchRow);
    gap(l, 12);
    if (cities != null) {
      CityAdapter a = new CityAdapter(search);
      search.setAdapter(a);
      search.setOnItemClickListener(
          (p, v, pos, id) -> {
            CityStore.City city = a.getItem(pos);
            search.setText("", false);
            search.dismissDropDown();
            hideKeyboard();
            chooseCity(city);
          });
      search.setOnEditorActionListener(
          (v, id, e) -> {
            searchNow();
            return true;
          });
    }
    LinearLayout loc = column();
    GradientDrawable locBg = rounded(0xff18232f);
    locBg.setStroke(dp(1), 0xff3d4958);
    loc.setBackground(locBg);
    loc.setPadding(dp(10), dp(10), dp(10), dp(10));
    locationLabel = text(locationMessage, 13, INK);
    loc.addView(locationLabel);
    gap(loc, 8);
    LinearLayout actions = row();
    Button relocate = button(locator.fine() ? "重新定位" : "允许访问位置", this::locate);
    relocate.setTextSize(13);
    actions.addView(relocate, new LinearLayout.LayoutParams(-2, dp(42)));
    Space space = new Space(this);
    actions.addView(space, new LinearLayout.LayoutParams(dp(8), 1));
    Button cityIndexButton = button("城市索引", this::cityIndex);
    cityIndexButton.setTextSize(13);
    actions.addView(cityIndexButton, new LinearLayout.LayoutParams(-2, dp(42)));
    if (!locationError.isEmpty()) {
      Space sp = new Space(this);
      actions.addView(sp, new LinearLayout.LayoutParams(dp(8), 1));
      Button settings = button("开启精确位置", () -> locator.openSettings());
      settings.setTextSize(13);
      styleButton(settings, 0xfff5eec9, 0xff261605);
      actions.addView(settings, new LinearLayout.LayoutParams(-2, dp(42)));
    }
    loc.addView(actions);
    l.addView(loc);
    gap(l, 12);
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setIndeterminate(true);
    progress.setVisibility(
        forecastJob != null && !forecastJob.cancelled && statusMessage.startsWith("正在")
            ? View.VISIBLE
            : View.GONE);
    l.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
    statusLabel = text(statusMessage, 12, 0xff9cd0f3);
    statusLabel.setVisibility(statusMessage.isEmpty() ? View.GONE : View.VISIBLE);
    l.addView(statusLabel);
    gap(l, 16);
    String location =
        forecast == null
            ? "当地"
            : forecast.place.source.equals("gps")
                ? "设备位置（" + forecast.place.name + "）"
                : forecast.place.name;
    LinearLayout result =
        legacyPanel(
            l,
            location
                + " "
                + (event % 2 == 1 ? "日落" : "日出")
                + (forecast == null ? "" : "\n" + stamp(forecast.eventTime, forecast.zone)));
    resultRow(
        result,
        "数据获取时间",
        forecast == null
            ? "-"
            : stamp(forecast.fetchedAt / 1000.0, forecast.zone)
                + (forecast.replay ? "（回放）" : forecast.cached ? "（缓存）" : ""),
        INK,
        true);
    resultRow(
        result,
        "预报模型",
        forecast == null ? "-" : forecast.model.startsWith("ec") ? "ECMWF IFS 9 km" : "GFS",
        INK,
        true);
    qualityLabel =
        resultRow(
            result,
            "火烧云鲜艳度",
            forecast == null
                ? "-"
                : forecast.missingAod ? "气溶胶缺测，暂不判断" : quality(forecast.peak.score) + "（本地估算）",
            forecast != null && !forecast.missingAod && forecast.peak.score < 15
                ? 0xffd3b8dd
                : PINK,
            true);
    aodLabel =
        resultRow(
            result,
            "气溶胶(AOD)",
            forecast == null
                ? "-"
                : Double.isFinite(forecast.peak.aod)
                    ? String.format(Locale.ROOT, "%.3f", forecast.peak.aod)
                        + "（"
                        + clarity(forecast.peak.aod, forecast.peak.visibility)
                        + "）"
                    : "缺测",
            BLUE,
            true);
    resultRow(result, "不确定性", forecast == null ? "本地近似，仅供参考" : "本地近似，未经实况回测", MUTED, false);
    LinearLayout mapCard = card(l);
    GradientDrawable mapCardBorder = rounded(0xff22364c);
    mapCardBorder.setCornerRadius(dp(9));
    mapCardBorder.setStroke(dp(1), 0xff31485f);
    mapCard.setBackground(mapCardBorder);
    LinearLayout maps = row();
    for (int i = 0; i < 2; i++) {
      final int index = i;
      LinearLayout group = column();
      FrameLayout frame =
          new FrameLayout(this) {
            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
              int size = MeasureSpec.getSize(widthMeasureSpec);
              super.onMeasure(
                  widthMeasureSpec, MeasureSpec.makeMeasureSpec(size, MeasureSpec.EXACTLY));
            }
          };
      GradientDrawable mapBorder = rounded(0xfff8f7f5);
      mapBorder.setCornerRadius(dp(8));
      mapBorder.setStroke(dp(1), 0xff9aabbc);
      frame.setBackground(mapBorder);
      frame.setClipToOutline(true);
      ForecastMapView m = new ForecastMapView(this);
      m.interactive = false;
      m.cities = cities;
      if (replay) m.emptyMessage = "历史快照无区域地图";
      if (place != null) m.setCenter(place.lat, place.lon, 5.8);
      if (homeRegions != null) m.setRegion(homeRegions[i]);
      View.OnClickListener enlarge =
          v -> {
            if (replay) return;
            event = (event / 2) * 2 + index;
            showPage(1);
          };
      m.setOnClickListener(enlarge);
      frame.setOnClickListener(enlarge);
      minis[i] = m;
      FrameLayout.LayoutParams mapParams = new FrameLayout.LayoutParams(-1, -1);
      mapParams.setMargins(dp(1), dp(1), dp(1), dp(1));
      frame.addView(m, mapParams);
      TextView chip = text(previewChip(i), 10, 0xff3e5165);
      chip.setIncludeFontPadding(false);
      chip.setLineSpacing(0, 1);
      chip.setPadding(dp(4), dp(2), dp(4), dp(2));
      GradientDrawable chipBackground = rounded(0xf0ffffff);
      chipBackground.setCornerRadius(dp(4));
      chip.setBackground(chipBackground);
      FrameLayout.LayoutParams chipParams =
          new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
      chipParams.setMargins(dp(5), dp(5), dp(5), 0);
      frame.addView(chip, chipParams);
      homeMapChips[i] = chip;
      group.addView(frame, new LinearLayout.LayoutParams(-1, -2));
      TextView caption = text(i == 0 ? "朝霞地图" : "晚霞地图", 14, 0xffcedae8);
      caption.setGravity(Gravity.CENTER);
      caption.setPadding(0, dp(8), 0, dp(2));
      group.addView(caption);
      LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1);
      if (i == 0) params.rightMargin = dp(13);
      maps.addView(group, params);
    }
    mapCard.addView(maps);
    gap(mapCard, 6);
    TextView mapNote =
        text(replay ? "返回实时查询后显示当地周边的朝霞与晚霞地图" : "同一天的朝霞与晚霞 · 粉紫色越深，火烧云条件越好 · 点击放大", 11, 0xffb8c9da);
    mapNote.setGravity(Gravity.CENTER);
    mapCard.addView(mapNote);
    String[] labels = {"详细预报", "大气截面", "悬日测试"};
    for (int i = 0; i < 3; i++) {
      final int which = i;
      l.addView(
          button(
              labels[i],
              () -> {
                if (which == 2) sunTest();
                else {
                  sectionMode = which == 1;
                  showPage(2);
                }
              }),
          new LinearLayout.LayoutParams(-1, dp(44)));
      if (i < 2) gap(l, 8);
    }
    gap(l, 16);
    LinearLayout accuracy = card(l);
    LinearLayout accuracyHeader = row();
    TextView accuracyTitle = text("准确率（Disclaimer）", 16, 0xfff5eec9);
    accuracyTitle.setTypeface(null, Typeface.BOLD);
    accuracyHeader.addView(accuracyTitle, new LinearLayout.LayoutParams(0, -2, 1));
    TextView accuracyDetails =
        text(
            "本地近似模型还没有实况回测，无法给出准确率。原站的验证结果不适用于本地模型。鲜艳度一栏显示的是本地文字估算，不是 Sunsetbot 原版鲜艳度数值。", 14, INK);
    accuracyDetails.setPadding(0, dp(16), 0, 0);
    accuracyDetails.setVisibility(View.GONE);
    Button toggleAccuracy = button("展开", () -> {});
    toggleAccuracy.setMinHeight(dp(36));
    toggleAccuracy.setMinimumHeight(dp(36));
    toggleAccuracy.setOnClickListener(
        v -> {
          boolean expanded = accuracyDetails.getVisibility() == View.VISIBLE;
          accuracyDetails.setVisibility(expanded ? View.GONE : View.VISIBLE);
          toggleAccuracy.setText(expanded ? "展开" : "收起");
        });
    accuracyHeader.addView(toggleAccuracy, new LinearLayout.LayoutParams(-2, dp(36)));
    accuracy.addView(accuracyHeader);
    accuracy.addView(accuracyDetails);
    LinearLayout fire = legacyPanel(l, "火烧云鲜艳度");
    fire.addView(
        text(
            "云要足够多，才有被染红的“画布”；太阳方向的云和雾霾不能太厚，光才能照到云上。本地版结合中高云云量、低云遮挡、沿线受光和气溶胶，给出“较难出现”“可能小烧”“可能中烧”等文字判断。原版鲜艳度公式尚未取得，不显示伪造的原版数值。",
            14,
            INK));
    LinearLayout aod = legacyPanel(l, "AOD（气溶胶光学厚度/天空浑浊度）");
    aod.addView(
        text(
            "AOD 表示空气中气溶胶对光线的遮挡和散射程度，通常越低越通透。它不是空气质量指数 AQI。水晶天标签是结合 AOD"
                + " 与能见度的本地参考，不是原站标准；空气通透不代表一定有火烧云。",
            14,
            INK));
    LinearLayout support = legacyPanel(l, "支持原作者");
    TextView supportText = text("", 14, INK);
    String authorLink = "Sunsetbot 火烧云分析与记录";
    SpannableString supportParagraph =
        new SpannableString("界面参考 " + authorLink + "。本应用为个人独立制作，与原站无关联。");
    supportParagraph.setSpan(
        new android.text.style.ClickableSpan() {
          @Override
          public void onClick(View widget) {
            open("https://www.sunsetbot.top/");
          }

          @Override
          public void updateDrawState(TextPaint drawState) {
            drawState.setColor(0xffa5d8ff);
            drawState.setUnderlineText(true);
          }
        },
        5,
        5 + authorLink.length(),
        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    supportText.setText(supportParagraph);
    supportText.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    support.addView(supportText);
    footer(l);
    if (place != null && forecast != null && !replay) {
      if (!forecast.event.equals(eventKey()) || !forecast.model.equals(model()))
        main.post(
            () -> {
              if (forecast != null
                  && (!forecast.event.equals(eventKey()) || !forecast.model.equals(model())))
                fetchForecast();
            });
      else if (homeRegions == null || homeRegions[0].valid < 49 || homeRegions[1].valid < 49)
        main.post(this::loadHomeMaps);
      else scheduleHomeRefresh(300000);
    }
  }

  private void status(String text, boolean busy) {
    statusMessage = text;
    if (statusLabel != null) {
      statusLabel.setText(text);
      statusLabel.setVisibility(text == null || text.isEmpty() ? View.GONE : View.VISIBLE);
    }
    if (progress != null) progress.setVisibility(busy ? View.VISIBLE : View.GONE);
  }

  private void updateLocation(String text) {
    locationMessage = text;
    if (locationLabel != null) locationLabel.setText(text);
  }

  private void locate() {
    if (!foreground || !agreed) return;
    locationAttempted = true;
    cancel(ipJob);
    cancel(forecastJob);
    pointSerial++;
    final int token = ++locationSerial;
    locationError = "";
    updateLocation("正在获取当前位置…");
    locator.locate(
        new DeviceLocator.Listener() {
          public void location(Location l) {
            if (token != locationSerial || !foreground) return;
            if (Math.abs(l.getLatitude()) > 72) {
              ipFallback(token, "设备位置超出模型范围");
              return;
            }
            place = cities.gps(l.getLatitude(), l.getLongitude(), l.getAccuracy());
            updateLocation("当前位置：" + place.name);
            fetchForecast();
          }

          public void error(String code, String message) {
            if (token != locationSerial || !foreground) return;
            locationError = code;
            ipFallback(token, message);
          }
        });
  }

  private void ipFallback(int token, String reason) {
    updateLocation(reason + "，正在获取大致位置…");
    cancel(ipJob);
    Network.Job job = ipJob = new Network.Job();
    workers.submit(
        () -> {
          Place found = null;
          CompletionService<Place> race = new ExecutorCompletionService<>(ipPool);
          List<Future<Place>> pending = new ArrayList<>();
          for (String address :
              new String[] {
                "https://myip.ipip.net/json",
                "https://get.geojs.io/v1/ip/geo.json",
                "https://api.ip.sb/geoip"
              })
            pending.add(
                race.submit(() -> cities.normalizeIp((JSONObject) Network.json(address, job))));
          for (int i = 0; i < pending.size(); i++) {
            if (job.cancelled) break;
            try {
              Place p = race.take().get();
              if (p != null) {
                found = p;
                break;
              }
            } catch (Exception ignored) {
            }
          }
          for (Future<Place> f : pending) f.cancel(true);
          job.disconnect();
          if (job.cancelled) return;
          Place result = found;
          if (result == null) {
            result = repository.lastPlace();
            if (result == null) {
              CityStore.City c = cities.byId.get("3101");
              result =
                  c == null ? new Place("上海市 - 全市", 31.2304, 121.4737, "default") : cities.place(c);
              result.source = "default";
            }
          }
          final Place answer = result;
          main.post(
              () -> {
                if (token != locationSerial || job.cancelled || !foreground) return;
                place = answer;
                updateLocation(
                    (place.source.equals("ip")
                            ? "当前位置（大致）："
                            : place.source.equals("saved") ? "上次查询地区：" : "默认地区：")
                        + place.name);
                fetchForecast();
              });
        });
  }

  private void fetchForecast() {
    if (place == null) return;
    if (page == 7) loadCloudSea(false);
    cancel(forecastJob);
    cancel(homeJob);
    homeSerial++;
    homeRegions = null;
    replay = false;
    final int serial = ++pointSerial;
    Network.Job job = forecastJob = new Network.Job();
    final Place target = place;
    final String m = model(), e = eventKey();
    forecast = null;
    statusMessage = "正在查询 " + target.name + " 的" + eventLabel(event) + "…";
    if (page == 0 || page == 2) showPage(page);
    status(statusMessage, true);
    workers.submit(
        () -> {
          try {
            Forecast f = repository.fetch(target, m, e, job);
            main.post(
                () -> {
                  if (serial != pointSerial || job.cancelled || !foreground) return;
                  forecast = f;
                  place = f.place;
                  repository.record(f);
                  statusMessage =
                      "查询完成 · "
                          + f.zone
                          + " 当地时间"
                          + (f.eventTime < System.currentTimeMillis() / 1000.0
                              ? " · 本次日出/日落已过去"
                              : "");
                  forecastJob = null;
                  if (page == 0 && search != null && search.hasFocus() && search.length() > 0)
                    status(statusMessage, false);
                  else if (page == 0 || page == 2) showPage(page);
                  else status(statusMessage, false);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (serial == pointSerial && !job.cancelled) {
                    forecastJob = null;
                    status(error.getMessage() == null ? "天气接口连接失败，请重试" : error.getMessage(), false);
                  }
                });
          }
        });
  }

  private void chooseCity(CityStore.City city) {
    if (city == null) return;
    final int selection = ++locationSerial;
    locator.stop();
    cancel(ipJob);
    locationError = "";
    hideKeyboard();
    if (Double.isFinite(city.lat + city.lon)) {
      selectPlace(cities.place(city));
      return;
    }
    status("正在查找 " + city.name + " 的坐标…", true);
    Network.Job job = ipJob = new Network.Job();
    workers.submit(
        () -> {
          try {
            JSONObject data =
                (JSONObject)
                    Network.json(
                        Network.url(
                            "https://geocoding-api.open-meteo.com/v1/search",
                            "name",
                            city.name,
                            "count",
                            30,
                            "language",
                            "zh",
                            "format",
                            "json"),
                        job);
            JSONArray list = data.optJSONArray("results");
            JSONObject best = null;
            double rank = Double.POSITIVE_INFINITY;
            CityStore.City parent = cities.byId.get(city.parent);
            for (int i = 0; list != null && i < list.length(); i++) {
              JSONObject p = list.getJSONObject(i);
              String country = p.optString("country_code"),
                  name = CityStore.key(p.optString("name"));
              if (!Arrays.asList("CN", "TW", "HK", "MO").contains(country)
                  || !(name.equals(CityStore.key(city.name))
                      || name.equals(CityStore.key(city.shortName)))) continue;
              double lat = p.optDouble("latitude", Double.NaN),
                  lon = p.optDouble("longitude", Double.NaN);
              if (!Double.isFinite(lat + lon) || Math.abs(lat) > 72) continue;
              double r =
                  parent != null && Double.isFinite(parent.lat + parent.lon)
                      ? distance(lat, lon, parent.lat, parent.lon)
                      : i;
              if (r < rank) {
                rank = r;
                best = p;
              }
            }
            if (best == null) throw new Exception("暂无该地区的准确坐标，请选择上级城市或输入经纬度查询");
            Place result =
                new Place(
                    city.label, best.getDouble("latitude"), best.getDouble("longitude"), "manual");
            result.zone = best.optString("timezone", "Asia/Shanghai");
            main.post(
                () -> {
                  if (selection == locationSerial && !job.cancelled && foreground)
                    selectPlace(result);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (selection == locationSerial && !job.cancelled)
                    status(error.getMessage(), false);
                });
          }
        });
  }

  private void selectPlace(Place p) {
    place = p;
    locationError = "";
    updateLocation("当前地区：" + place.name);
    fetchForecast();
  }

  private void searchNow() {
    if (search == null || cities == null) return;
    String q = search.getText().toString().trim();
    if (q.isEmpty() && place != null) {
      hideKeyboard();
      fetchForecast();
      return;
    }
    java.util.regex.Matcher coordinates =
        java.util.regex.Pattern.compile(
                "^([-+]?\\d+(?:\\.\\d+)?)\\s*[,，]\\s*([-+]?\\d+(?:\\.\\d+)?)$")
            .matcher(q);
    if (coordinates.matches()) {
      double lat = Double.parseDouble(coordinates.group(1)),
          lon = Double.parseDouble(coordinates.group(2));
      if (Math.abs(lat) > 72 || Math.abs(lon) > 180) {
        alert("坐标范围无效", "纬度 −72 到 72，经度 −180 到 180。");
        return;
      }
      locationSerial++;
      locator.stop();
      cancel(ipJob);
      hideKeyboard();
      selectPlace(
          new Place(String.format(Locale.ROOT, "%.4f, %.4f", lat, lon), lat, lon, "manual"));
      return;
    }
    List<CityStore.City> found = cities.search(q);
    if (found.isEmpty()) {
      alert(
          cities.all.isEmpty() ? "城市索引暂不可用" : "未找到地区",
          cities.all.isEmpty()
              ? ready ? "本地城市索引未能加载，仍可使用定位或输入纬度,经度查询。" : "城市索引正在载入，仍可使用定位或输入纬度,经度查询。"
              : "请补充城市或区县名称，或使用城市索引。");
      return;
    }
    if (found.size() == 1) {
      chooseCity(found.get(0));
      search.setText("");
      return;
    }
    String[] labels = new String[found.size()];
    for (int i = 0; i < labels.length; i++) labels[i] = found.get(i).label;
    new AlertDialog.Builder(this)
        .setTitle("选择地区")
        .setItems(
            labels,
            (d, w) -> {
              chooseCity(found.get(w));
              if (search != null) search.setText("");
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private final class CityAdapter extends ArrayAdapter<CityStore.City> {
    private final AutoCompleteTextView field;

    CityAdapter(AutoCompleteTextView owner) {
      super(MainActivity.this, android.R.layout.simple_dropdown_item_1line, new ArrayList<>());
      field = owner;
    }

    @Override
    public Filter getFilter() {
      return new Filter() {
        protected FilterResults performFiltering(CharSequence raw) {
          List<CityStore.City> r = cities.search(raw == null ? "" : raw.toString());
          FilterResults f = new FilterResults();
          f.values = r;
          f.count = r.size();
          return f;
        }

        @SuppressWarnings("unchecked")
        protected void publishResults(CharSequence q, FilterResults r) {
          setNotifyOnChange(false);
          clear();
          if (r.values != null) addAll((List<CityStore.City>) r.values);
          notifyDataSetChanged();
          if (field.isAttachedToWindow()
              && field.hasFocus()
              && r.count > 0
              && q != null
              && field.getText().toString().equals(q.toString())) field.showDropDown();
        }

        public CharSequence convertResultToString(Object v) {
          return ((CityStore.City) v).label;
        }
      };
    }
  }

  private void cityIndex() {
    if (cities.all.isEmpty()) {
      alert("城市索引暂不可用", ready ? "本地城市索引未能加载，仍可使用定位或经纬度查询。" : "城市索引正在载入，请稍候再试。定位不受影响。");
      return;
    }
    LinearLayout box = column();
    box.setPadding(dp(18), dp(8), dp(18), dp(8));
    Spinner province = new Spinner(this), city = new Spinner(this), district = new Spinner(this);
    box.addView(text("省份", 13, MUTED));
    box.addView(province);
    box.addView(text("城市", 13, MUTED));
    box.addView(city);
    box.addView(text("区县", 13, MUTED));
    box.addView(district);
    List<CityStore.City> ps = cities.provinces();
    List<CityStore.City> cs = new ArrayList<>(), ds = new ArrayList<>();
    province.setAdapter(
        new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, ps));
    province.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            cs.clear();
            cs.addAll(cities.children(ps.get(pos).id));
            city.setAdapter(
                new ArrayAdapter<>(
                    MainActivity.this, android.R.layout.simple_spinner_dropdown_item, cs));
          }
        });
    city.setOnItemSelectedListener(
        new AdapterView.OnItemSelectedListener() {
          public void onNothingSelected(AdapterView<?> p) {}

          public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
            ds.clear();
            ds.add(cs.get(pos));
            ds.addAll(cities.children(cs.get(pos).id));
            List<String> names = new ArrayList<>();
            for (int i = 0; i < ds.size(); i++) names.add(i == 0 ? "全市" : ds.get(i).name);
            district.setAdapter(
                new ArrayAdapter<>(
                    MainActivity.this, android.R.layout.simple_spinner_dropdown_item, names));
          }
        });
    new AlertDialog.Builder(this)
        .setTitle("省市区县索引")
        .setView(box)
        .setPositiveButton(
            "查看预报",
            (d, w) -> {
              int index = district.getSelectedItemPosition();
              if (index >= 0 && index < ds.size()) chooseCity(ds.get(index));
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private String previewChip(int index) {
    if (replay || forecast == null) return index == 0 ? "朝霞" : "晚霞";
    Region region = homeRegions == null ? null : homeRegions[index];
    String date =
        region == null
            ? Instant.ofEpochSecond((long) forecast.eventTime)
                .atZone(ZoneId.of(forecast.zone))
                .toLocalDate()
                .toString()
            : region.date;
    String source = region == null ? forecast.model : region.model;
    return date + " " + (source.startsWith("ec") ? "EC" : "GFS");
  }

  private void scheduleHomeRefresh(long delay) {
    if (homeRetry != null) main.removeCallbacks(homeRetry);
    homeRetry =
        () -> {
          if (page == 0 && foreground && !replay) loadHomeMaps();
        };
    main.postDelayed(homeRetry, delay);
  }

  private void loadHomeMaps() {
    if (homeRetry != null) main.removeCallbacks(homeRetry);
    if (page != 0 || place == null || replay || !foreground) return;
    cancel(homeJob);
    final int serial = ++homeSerial;
    Network.Job job = homeJob = new Network.Job();
    double delta = 4.8, lat = place.lat, lon = place.lon;
    Bounds bounds = new Bounds(lon - delta, lon + delta, lat - 3.2, lat + 3.2);
    final String model = model(), zone = place.zone;
    final int day = event / 2;
    workers.submit(
        () -> {
          try {
            Region[] data = repository.region(bounds, model, day, zone, job);
            main.post(
                () -> {
                  if (serial != homeSerial || job.cancelled || page != 0) return;
                  homeRegions = data;
                  for (int i = 0; i < 2; i++)
                    if (minis[i] != null) {
                      minis[i].setRegion(data[i]);
                      if (homeMapChips[i] != null) homeMapChips[i].setText(previewChip(i));
                    }
                  scheduleHomeRefresh(data[0].valid < 49 || data[1].valid < 49 ? 60000 : 300000);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (serial != homeSerial || job.cancelled) return;
                  for (ForecastMapView v : minis)
                    if (v != null) v.setContentDescription("小地图暂不可用：" + error.getMessage());
                  scheduleHomeRefresh(60000);
                });
          }
        });
  }

  private void mapPage() {
    LinearLayout outer = scrollPage();
    LinearLayout controls = legacyPanel(outer, "朝霞 / 晚霞地图");
    periods(controls, true);
    gap(controls, 8);
    LinearLayout update = row();
    update.addView(text("● 当前区域自动更新", 12, MUTED), new LinearLayout.LayoutParams(0, -2, 1));
    update.addView(
        button(
            "回到查询城市",
            () -> {
              if (place != null && bigMap != null) {
                bigMap.setCenter(place.lat, place.lon, 6);
                bigMap.notifyBounds();
              }
            }));
    controls.addView(update);
    gap(controls, 6);
    LinearLayout options = row();
    CheckBox colors = new CheckBox(this);
    colors.setText("显示预报色块");
    colors.setTextColor(INK);
    colors.setTextSize(12);
    colors.setChecked(true);
    options.addView(colors, new LinearLayout.LayoutParams(0, dp(44), 1));
    options.addView(text("色块透明度", 12, MUTED));
    SeekBar opacity = new SeekBar(this);
    opacity.setMax(75);
    opacity.setProgress(50);
    opacity.setContentDescription("色块透明度");
    options.addView(opacity, new LinearLayout.LayoutParams(dp(110), dp(44)));
    controls.addView(options);
    CheckBox roads = new CheckBox(this);
    roads.setText("街道底图");
    roads.setTextColor(INK);
    roads.setTextSize(12);
    controls.addView(roads, new LinearLayout.LayoutParams(-2, dp(40)));
    mapStatus = text("优先加载当前视野，拖动、缩放后自动更新。点击地图可查看当地预报。", 12, 0xff9cd0f3);
    controls.addView(mapStatus);
    gap(controls, 8);
    FrameLayout frame = new FrameLayout(this);
    bigMap = new ForecastMapView(this);
    bigMap.cities = cities;
    bigMap.overlayOpacity = .65f;
    if (place != null) bigMap.setCenter(place.lat, place.lon, 6);
    else bigMap.setCenter(35, 105, 4);
    frame.addView(bigMap, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout zoom = column();
    Button plus = button("+", () -> bigMap.zoomBy(1));
    styleButton(plus, 0xfff8f9fa, 0xff333333);
    plus.setTextSize(20);
    zoom.addView(plus, new LinearLayout.LayoutParams(dp(34), dp(34)));
    Button minus = button("−", () -> bigMap.zoomBy(-1));
    styleButton(minus, 0xfff8f9fa, 0xff333333);
    minus.setTextSize(20);
    zoom.addView(minus, new LinearLayout.LayoutParams(dp(34), dp(34)));
    FrameLayout.LayoutParams zoomPos =
        new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
    zoomPos.setMargins(dp(8), dp(8), 0, 0);
    frame.addView(zoom, zoomPos);
    controls.addView(frame, new LinearLayout.LayoutParams(-1, dp(470)));
    bigMap.listener =
        new ForecastMapView.Listener() {
          public void changed(Bounds b) {
            scheduleMap(b);
          }

          public void point(double lat, double lon) {
            showMapPoint(lat, lon);
          }
        };
    colors.setOnCheckedChangeListener(
        (b, checked) -> {
          bigMap.showColors = checked;
          bigMap.invalidate();
        });
    roads.setOnCheckedChangeListener(
        (b, checked) -> {
          bigMap.roads = checked;
          bigMap.invalidate();
        });
    opacity.setOnSeekBarChangeListener(
        new SeekBar.OnSeekBarChangeListener() {
          public void onProgressChanged(SeekBar bar, int value, boolean fromUser) {
            bigMap.overlayOpacity = (15 + value) / 100f;
            bigMap.invalidate();
          }

          public void onStartTrackingTouch(SeekBar bar) {}

          public void onStopTrackingTouch(SeekBar bar) {}
        });
    gap(controls, 10);
    View gradient =
        new View(this) {
          private final android.graphics.Paint paint =
              new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
          private final android.graphics.Paint border =
              new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

          @Override
          protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            paint.setShader(
                new android.graphics.LinearGradient(
                    0,
                    0,
                    w,
                    0,
                    new int[] {
                      0xfffcfbfc,
                      0xffe8e0f4,
                      0xffc7a4e0,
                      0xffe1a2d6,
                      0xfff189be,
                      0xffdd57a8,
                      0xffa53495
                    },
                    new float[] {0, .15f, .35f, .60f, .80f, .90f, 1},
                    android.graphics.Shader.TileMode.CLAMP));
          }

          @Override
          protected void onDraw(android.graphics.Canvas canvas) {
            float halfBorder = dp(1) / 2f;
            canvas.drawRoundRect(
                halfBorder,
                halfBorder,
                getWidth() - halfBorder,
                getHeight() - halfBorder,
                dp(3),
                dp(3),
                paint);
            border.setColor(0xff52647b);
            border.setStyle(android.graphics.Paint.Style.STROKE);
            border.setStrokeWidth(dp(1));
            canvas.drawRoundRect(
                halfBorder,
                halfBorder,
                getWidth() - halfBorder,
                getHeight() - halfBorder,
                dp(3),
                dp(3),
                border);
          }
        };
    controls.addView(gradient, new LinearLayout.LayoutParams(-1, dp(13)));
    gap(controls, 3);
    LinearLayout legend = row();
    String[] names = {"较难出现", "小烧", "中烧", "中烧到大烧", "大烧"};
    for (int i = 0; i < names.length; i++) {
      if (i > 0) legend.addView(new Space(this), new LinearLayout.LayoutParams(0, 1, 1));
      legend.addView(text(names[i], 10, 0xffcfdae7), new LinearLayout.LayoutParams(-2, -2));
    }
    controls.addView(legend);
    gap(controls, 10);
    controls.addView(text("区域色块为稀疏采样及空间插值，本地近似，非原站地图数据。放大不会增加原始气象数据精度，缺测区域留空。", 12, MUTED));
    fold(
        controls,
        "查看当前城市沿光路的采样点",
        forecast == null ? "请先在主页查询城市。" : "沿太阳方向 80 / 180 / 300 / 450 / 600 km 采样。详细分层云量见详情页。");
    footer(outer);
    bigMap.post(
        () -> {
          if (bigMap != null) bigMap.notifyBounds();
        });
  }

  private void scheduleMap(Bounds b) {
    if (mapRetry != null) main.removeCallbacks(mapRetry);
    if (page != 1 || !foreground) return;
    if (mapTimer != null) main.removeCallbacks(mapTimer);
    cancel(mapJob);
    mapSerial++;
    mapTimer = () -> loadMap(b);
    main.postDelayed(mapTimer, 550);
  }

  private void loadMap(Bounds b) {
    if (mapRetry != null) main.removeCallbacks(mapRetry);
    if (page != 1 || !foreground) return;
    long wait = mapCooldown - System.currentTimeMillis();
    if (wait > 0) {
      if (mapStatus != null) mapStatus.setText("接口限流，" + (wait / 1000 + 1) + " 秒后自动重试");
      mapRetry =
          () -> {
            if (page == 1 && bigMap != null) loadMap(bigMap.bounds());
          };
      main.postDelayed(mapRetry, wait);
      return;
    }
    cancel(mapJob);
    Network.Job job = mapJob = new Network.Job();
    final int serial = ++mapSerial;
    final String m = model(), z = place == null ? "Asia/Shanghai" : place.zone;
    final int day = event / 2;
    if (mapStatus != null) mapStatus.setText("正在加载当前视野的云况与气溶胶…");
    workers.submit(
        () -> {
          try {
            Region[] data = repository.region(b, m, day, z, job);
            main.post(
                () -> {
                  if (serial != mapSerial || job.cancelled || page != 1) return;
                  bigRegions = data;
                  Region r = data[event % 2];
                  bigMap.setRegion(r);
                  mapMessage = r.note + " · " + r.valid + "/49 · " + r.date;
                  if (mapStatus != null) mapStatus.setText(mapMessage);
                  mapRetry =
                      () -> {
                        if (page == 1 && bigMap != null) loadMap(bigMap.bounds());
                      };
                  main.postDelayed(mapRetry, r.valid == 49 ? 300000 : 60000);
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (serial != mapSerial || job.cancelled || page != 1) return;
                  if (error instanceof Network.HttpError
                      && ((Network.HttpError) error).status == 429)
                    mapCooldown =
                        System.currentTimeMillis()
                            + Math.max(61000, ((Network.HttpError) error).retry);
                  if (mapStatus != null) mapStatus.setText(error.getMessage() + " · 稍后自动重试");
                  mapRetry =
                      () -> {
                        if (page == 1 && bigMap != null) loadMap(bigMap.bounds());
                      };
                  main.postDelayed(mapRetry, 60000);
                });
          }
        });
  }

  private void showMapPoint(double lat, double lon) {
    if (bigMap == null) return;
    Region r = bigMap.region;
    String msg = String.format(Locale.ROOT, "纬度 %.4f，经度 %.4f", lat, lon);
    if (r != null) {
      double unwrapped = lon;
      while (unwrapped - (r.bounds.west + r.bounds.east) / 2 > 180) unwrapped -= 360;
      while (unwrapped - (r.bounds.west + r.bounds.east) / 2 < -180) unwrapped += 360;
      double x = (unwrapped - r.bounds.west) / (r.bounds.east - r.bounds.west),
          y = (merc(lat) - merc(r.bounds.south)) / (merc(r.bounds.north) - merc(r.bounds.south)),
          s = ForecastMapView.value(r.scores, x, y);
      msg += "\n" + (Double.isFinite(s) ? quality(s) + "（区域插值）" : "此处暂缺数据");
    }
    new AlertDialog.Builder(this)
        .setTitle("地图位置")
        .setMessage(msg)
        .setPositiveButton(
            "查询此点",
            (d, w) -> {
              locationSerial++;
              locator.stop();
              cancel(ipJob);
              place =
                  new Place(String.format(Locale.ROOT, "%.3f, %.3f", lat, lon), lat, lon, "manual");
              locationMessage = "地图选择：" + place.name;
              showPage(0);
              fetchForecast();
            })
        .setNegativeButton("关闭", null)
        .show();
  }

  private void stopCloudSea() {
    cancel(seaJob);
    seaJob = null;
    seaRequestKey = "";
    seaSerial++;
  }

  private String cloudSeaKey() {
    return place == null ? "" : place.lat + "," + place.lon + "," + model();
  }

  private boolean currentCloudSea() {
    if (seaForecast == null || place == null) return false;
    return seaForecast.lat == place.lat
        && seaForecast.lon == place.lon
        && seaForecast.model.equals(model())
        && System.currentTimeMillis() >= seaForecast.fetchedAtMillis
        && System.currentTimeMillis() - seaForecast.fetchedAtMillis < 30 * 60 * 1000L
        && Instant.ofEpochMilli(seaForecast.fetchedAtMillis)
            .atZone(seaForecast.zoneId())
            .toLocalDate()
            .equals(LocalDate.now(seaForecast.zoneId()));
  }

  private void cloudSeaPage() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, "云海条件预测");
    TextView change = text("更换查询地点 →", 14, 0xffa5d8ff);
    change.setPadding(0, dp(6), 0, dp(12));
    change.setOnClickListener(v -> showPage(0));
    l.addView(change);
    l.addView(text("根据低云、湿度、风和降水估算未来五天的云海条件。请先在主页选择靠近山区观景点的地点；城市中心或 IP 定位不能代表山顶天气。", 14, INK));
    gap(l, 12);
    seaPlaceLabel =
        text(
            place == null
                ? "尚未选择地点"
                : place.name + String.format(Locale.ROOT, "（%.3f, %.3f）", place.lat, place.lon),
            14,
            INK);
    l.addView(seaPlaceLabel);
    gap(l, 8);
    seaRefresh = button("刷新预测", () -> loadCloudSea(true));
    l.addView(seaRefresh);
    gap(l, 12);
    seaStatus = text(seaMessage, 13, MUTED);
    l.addView(seaStatus);
    seaResults = column();
    l.addView(seaResults);
    gap(l, 16);
    l.addView(text("云海条件为未经实况验证的气象条件估算，不代表云海一定出现。山谷地形、观景点高度和实际云层位置都会影响结果，请结合当地实况判断。", 12, MUTED));
    footer(outer);
    if (currentCloudSea()) renderCloudSea();
    loadCloudSea(false);
  }

  private void loadCloudSea(boolean force) {
    if (page != 7 || !agreed || !foreground) return;
    if (seaPlaceLabel != null)
      seaPlaceLabel.setText(
          place == null
              ? "尚未选择地点"
              : place.name + String.format(Locale.ROOT, "（%.3f, %.3f）", place.lat, place.lon));
    if (place == null) {
      stopCloudSea();
      seaForecast = null;
      seaMessage = "请先在主页搜索或选择靠近观景点的地点。";
      if (seaStatus != null) seaStatus.setText(seaMessage);
      if (seaResults != null) seaResults.removeAllViews();
      return;
    }
    if (!force && currentCloudSea()) {
      seaMessage = cloudSeaDataMessage(seaForecast);
      if (seaStatus != null) seaStatus.setText(seaMessage);
      return;
    }
    String key = cloudSeaKey();
    if (!force && seaJob != null && !seaJob.cancelled && seaRequestKey.equals(key)) {
      if (seaRefresh != null) seaRefresh.setEnabled(false);
      return;
    }
    stopCloudSea();
    final int serial = seaSerial;
    final Place target = place;
    final String selectedModel = model();
    Network.Job job = seaJob = new Network.Job();
    seaRequestKey = key;
    seaMessage = "正在读取云海相关天气预报…";
    if (seaStatus != null) seaStatus.setText(seaMessage);
    if (seaRefresh != null) seaRefresh.setEnabled(false);
    if (seaResults != null) seaResults.removeAllViews();
    workers.submit(
        () -> {
          try {
            CloudSeaForecast data = seaRepository.fetch(target, selectedModel, job, force);
            main.post(
                () -> {
                  if (destroyed
                      || !foreground
                      || page != 7
                      || serial != seaSerial
                      || job.cancelled
                      || !key.equals(cloudSeaKey())) return;
                  seaJob = null;
                  seaForecast = data;
                  if (seaDay == null || !data.days.contains(seaDay)) seaDay = data.days.get(0);
                  seaMessage = cloudSeaDataMessage(data);
                  if (seaStatus != null) seaStatus.setText(seaMessage);
                  if (seaRefresh != null) seaRefresh.setEnabled(true);
                  renderCloudSea();
                });
          } catch (Exception error) {
            main.post(
                () -> {
                  if (destroyed
                      || !foreground
                      || page != 7
                      || serial != seaSerial
                      || job.cancelled
                      || !key.equals(cloudSeaKey())) return;
                  seaJob = null;
                  seaForecast = null;
                  seaMessage =
                      "读取失败："
                          + (error.getMessage() == null ? "天气接口连接失败" : error.getMessage())
                          + " 请检查网络后重试。";
                  if (seaStatus != null) seaStatus.setText(seaMessage);
                  if (seaRefresh != null) seaRefresh.setEnabled(true);
                });
          }
        });
  }

  private String cloudSeaDataMessage(CloudSeaForecast data) {
    return "数据源："
        + (data.model.startsWith("ec") ? "ECMWF IFS" : "GFS")
        + " · 时间："
        + data.zone
        + " · "
        + Instant.ofEpochMilli(data.fetchedAtMillis)
            .atZone(data.zoneId())
            .format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
        + " 更新";
  }

  private void renderCloudSea() {
    if (seaResults == null || seaForecast == null || page != 7) return;
    CloudSeaForecast data = seaForecast;
    if (seaDay == null || !data.days.contains(seaDay)) seaDay = data.days.get(0);
    long now = System.currentTimeMillis() / 1000;
    seaResults.removeAllViews();
    gap(seaResults, 12);
    HorizontalScrollView dates = new HorizontalScrollView(this);
    LinearLayout buttons = row();
    for (LocalDate day : data.days) {
      Button b =
          button(
              day + "\n" + data.dayAssessment(day, now),
              () -> {
                seaDay = day;
                renderCloudSea();
              });
      b.setContentDescription(day + " " + data.dayAssessment(day, now));
      b.setSelected(day.equals(seaDay));
      if (!day.equals(seaDay)) styleButton(b, ACCENT, INK);
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(124), dp(62));
      p.rightMargin = dp(8);
      buttons.addView(b, p);
    }
    dates.addView(buttons);
    seaResults.addView(dates);
    int selectedIndex = data.days.indexOf(seaDay);
    dates.post(
        () -> dates.scrollTo(Math.max(0, dp(selectedIndex * 132) - dates.getWidth() / 3), 0));
    gap(seaResults, 12);
    seaResults.addView(text("清晨参考：" + data.morningReference(seaDay, now), 14, PINK));
    gap(seaResults, 12);
    List<CloudSeaForecast.Hour> rows = data.hoursFor(seaDay);
    HorizontalScrollView charts = new HorizontalScrollView(this);
    CloudSeaChartView chart = new CloudSeaChartView(this);
    chart.setRows(rows, data.zoneId());
    charts.addView(
        chart,
        new android.view.ViewGroup.LayoutParams(dp(Math.max(720, rows.size() * 34)), dp(410)));
    seaResults.addView(charts);
    gap(seaResults, 8);
    seaResults.addView(
        text("红色：气温　紫色：低云量　青色：湿度　蓝色柱：降水　蓝色线：风速。图表可左右滑动；云量是覆盖比例，不能表示云层高度。", 12, MUTED));
    gap(seaResults, 12);
    seaResults.addView(text("以下为 " + seaDay + " 的逐小时预报，时间为当地时间。左右滑动可查看完整表格。", 12, MUTED));
    gap(seaResults, 8);
    HorizontalScrollView horizontal = new HorizontalScrollView(this);
    TableLayout table = new TableLayout(this);
    addTableRow(table, new String[] {"时间", "低云量", "湿度", "气温", "风速", "降水", "条件判断"}, true);
    for (CloudSeaForecast.Hour hour : rows) {
      addTableRow(
          table,
          new String[] {
            hour.timeLabel(data.zoneId()),
            seaValue(hour.low, "%", 0),
            seaValue(hour.humidity, "%", 0),
            seaValue(hour.temperature, "°C", 1),
            seaValue(hour.wind, " m/s", 1),
            seaValue(hour.rain, " mm", 1),
            hour.assessment() == null ? "数据缺测" : hour.assessment()
          },
          false);
    }
    horizontal.addView(table);
    seaResults.addView(horizontal);
  }

  private String seaValue(double value, String unit, int digits) {
    return Double.isFinite(value)
        ? String.format(Locale.ROOT, "%." + digits + "f", value) + unit
        : "—";
  }

  private void details() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, sectionMode ? "大气截面" : "详细预报");
    if (forecast == null) {
      l.addView(text("请先在主页搜索城市。", 14, INK));
    } else if (sectionMode) {
      l.addView(
          text(
              forecast.place.name
                  + " · "
                  + stamp(forecast.peak.t, forecast.zone)
                  + " · 沿太阳方向 "
                  + Math.round(forecast.bearing)
                  + "° 的分层云量示意",
              14,
              INK));
      gap(l, 12);
      l.addView(new LegacyCloudSection(forecast), new LinearLayout.LayoutParams(-1, -2));
    } else {
      String summary =
          forecast.place.name
              + " "
              + stamp(forecast.eventTime, forecast.zone)
              + " "
              + (forecast.event.startsWith("set") ? "晚霞" : "朝霞")
              + "："
              + (forecast.missingAod ? "AOD 缺测，暂不判断。" : quality(forecast.peak.score) + "（本地估算）。");
      if (forecast.window != null && !forecast.missingAod)
        summary +=
            " 参考观赏时段 "
                + clock(forecast.window[0], forecast.zone)
                + "–"
                + clock(forecast.window[1], forecast.zone)
                + "。";
      summary += " 每小时气象数据插值，时间仅供参考。";
      l.addView(text(summary, 14, INK));
      gap(l, 16);
      HorizontalScrollView horizontal = new HorizontalScrollView(this);
      TableLayout table = new TableLayout(this);
      addTableRow(table, new String[] {"时间", "低云", "中云", "高云", "AOD", "能见度", "本地估算"}, true);
      for (int index : new int[] {2, 5, 8, 10, 12, 15, 18}) {
        Point p = forecast.points.get(index);
        addTableRow(
            table,
            new String[] {
              clock(p.t, forecast.zone),
              pct(p.cloud.low),
              pct(p.cloud.mid),
              pct(p.cloud.high),
              Double.isFinite(p.aod) ? String.format(Locale.ROOT, "%.3f", p.aod) : "缺测",
              String.format(Locale.ROOT, "%.1f km", p.visibility / 1000),
              forecast.missingAod ? "缺测" : quality(p.score)
            },
            false);
      }
      horizontal.addView(table);
      l.addView(horizontal);
    }
    if (sectionMode) {
      gap(l, 12);
      l.addView(text("本地版只能显示分层云量示意，不能复原原站的温湿廓线截面。固定高度光路为近似，不是真实云底。", 12, MUTED));
    }
    gap(l, 16);
    l.addView(button("返回主页", () -> showPage(0)));
    footer(outer);
  }

  private final class LegacyCloudSection extends View {
    private final Forecast data;
    private final android.graphics.Paint paint =
        new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final int[] distances = {0, 80, 180, 300, 450, 600};
    private final float[] xs = new float[6];
    private final Cloud[] clouds = new Cloud[6];
    private final String[] labels = {"高云", "中云", "低云"};

    LegacyCloudSection(Forecast data) {
      super(MainActivity.this);
      this.data = data;
      setBackgroundColor(BG);
      setContentDescription("沿太阳方向的低中高三层云量示意，颜色越亮云量越多");
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
      int width = MeasureSpec.getSize(widthMeasureSpec);
      setMeasuredDimension(width, resolveSize(Math.round(width * .37f), heightMeasureSpec));
    }

    @Override
    protected void onDraw(android.graphics.Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.scale(getWidth() / 1000f, getHeight() / 370f);
      clouds[0] = data.peak.cloud;
      for (int i = 0; i < 6; i++) {
        xs[i] = 85 + distances[i] / 600f * 880;
        if (i > 0) clouds[i] = data.peak.far[i - 1];
      }
      for (int layer = 0; layer < 3; layer++) {
        float y = 35 + layer * 100;
        paint.setColor(0xffb9c8d8);
        paint.setTextSize(23);
        paint.setTextAlign(android.graphics.Paint.Align.LEFT);
        canvas.drawText(labels[layer], 12, y + 44, paint);
        for (int i = 0; i < 6; i++) {
          double value = layer == 0 ? clouds[i].high : layer == 1 ? clouds[i].mid : clouds[i].low;
          float left = i == 0 ? 85 : (xs[i - 1] + xs[i]) / 2;
          float right = i == 5 ? 965 : (xs[i] + xs[i + 1]) / 2;
          paint.setColor(0xffb9defb);
          paint.setAlpha((int) Math.round((.08 + .8 * value) * 255));
          canvas.drawRoundRect(left + 2, y, right - 2, y + 74, 3, 3, paint);
          paint.setColor(value > .55 ? 0xff10273c : 0xffdfebf5);
          paint.setTextAlign(android.graphics.Paint.Align.CENTER);
          canvas.drawText(pct(value), (left + right) / 2, y + 45, paint);
        }
      }
      paint.setColor(0xffb9c8d8);
      paint.setTextSize(21);
      for (int i = 0; i < 6; i++) {
        paint.setTextAlign(
            i == 0
                ? android.graphics.Paint.Align.LEFT
                : i == 5
                    ? android.graphics.Paint.Align.RIGHT
                    : android.graphics.Paint.Align.CENTER);
        canvas.drawText(i == 0 ? "本地" : distances[i] + " km", xs[i], 348, paint);
      }
      canvas.restore();
    }
  }

  private void addTableRow(TableLayout table, String[] values, boolean header) {
    TableRow row = new TableRow(this);
    int background = header ? ACCENT : table.getChildCount() % 2 == 0 ? 0xff1b304b : CARD;
    for (String s : values) {
      TextView t = text(s, 13, 0xffe0e0e0);
      t.setSingleLine(true);
      t.setGravity(Gravity.CENTER);
      t.setPadding(dp(8), dp(8), dp(8), dp(8));
      if (header) t.setTypeface(null, Typeface.BOLD);
      GradientDrawable cell = new GradientDrawable();
      cell.setColor(background);
      cell.setStroke(dp(1), 0xff4a5c72);
      t.setBackground(cell);
      row.addView(t);
    }
    table.addView(row);
  }

  private String pct(double v) {
    return Math.round(v * 100) + "%";
  }

  private void records() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, "记录");
    l.addView(text("这里保存本设备最近的查询快照，最多 10 条。不是原站的观测记录。", 14, INK));
    gap(l, 12);
    JSONArray array = repository.records();
    if (array.length() == 0) l.addView(text("暂无查询记录。", 15, MUTED));
    for (int i = 0; i < array.length(); i++) {
      JSONObject raw = array.optJSONObject(i);
      if (raw == null) continue;
      try {
        String name = raw.getJSONObject("place").getString("name"), z = raw.getString("timezone");
        LinearLayout record = row();
        record.setPadding(0, dp(11), 0, dp(11));
        TextView label =
            text(
                name
                    + " "
                    + stamp(raw.getDouble("eventTime"), z)
                    + " "
                    + (raw.getString("model").startsWith("ec") ? "ECMWF IFS 9 km" : "GFS"),
                14,
                INK);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.rightMargin = dp(16);
        record.addView(label, labelParams);
        Button snapshot =
            button(
                "查看快照",
                () -> {
                  try {
                    locationSerial++;
                    locator.stop();
                    cancel(ipJob);
                    cancel(forecastJob);
                    pointSerial++;
                    forecast = compute(raw);
                    forecast.replay = true;
                    place = forecast.place;
                    event =
                        (forecast.event.endsWith("1") ? 2 : 0)
                            + (forecast.event.startsWith("set") ? 1 : 0);
                    ec = forecast.model.startsWith("ec");
                    replay = true;
                    homeRegions = null;
                    locationMessage = "回放地区：" + place.name;
                    statusMessage = "历史快照，不是最新预报";
                    showPage(0);
                  } catch (Exception e) {
                    alert("记录无法打开", e.getMessage());
                  }
                });
        snapshot.setPadding(dp(12), dp(6), dp(12), dp(6));
        record.addView(snapshot, new LinearLayout.LayoutParams(-2, dp(44)));
        l.addView(record);
        divider(l);
      } catch (Exception ignored) {
      }
    }
    l.addView(
        button(
            "清除查询记录",
            () ->
                new AlertDialog.Builder(this)
                    .setTitle("清除本机查询快照？")
                    .setPositiveButton(
                        "清除",
                        (d, w) -> {
                          repository.clearRecords();
                          showPage(3);
                        })
                    .setNegativeButton("取消", null)
                    .show()));
  }

  private void accuracyPage() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, "准确率");
    l.addView(text("原版算法的回测不能用于本地版本。本地近似模型尚未进行实况回测，因此暂不显示准确率和混淆矩阵。", 14, INK));
    gap(l, 10);
    l.addView(text("本站只检查了数据读取、光路计算、缺测处理和界面运行，不代表已验证实际火烧云预测准确率。", 14, INK));
    footer(outer);
  }

  private void knowledgePage() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, "知识");
    l.addView(
        text(
            "判断朝晚霞，先看三件事：\n"
                + "1. 本地有没有中高云：没有云，就缺少被照亮的画布。\n"
                + "2. 太阳方向有没有厚云挡光：朝霞看日出方向，晚霞看日落方向。\n"
                + "3. 空气是否通透：雾霾、低云和雾可能削弱或遮挡霞光。",
            14,
            INK));
    gap(l, 12);
    l.addView(text("天气晴并不等于有火烧云。出门前再看看天空与卫星云图，尤其留意短时变化的云洞和对流云。", 14, INK));
    l.addView(
        button(
            "阅读原作者的火烧云分析文章",
            () -> open("https://www.weibo.com/ttarticle/p/show?id=2309404806042994475312")));
    footer(outer);
  }

  private void about() {
    LinearLayout outer = scrollPage();
    LinearLayout l = legacyPanel(outer, "关于本应用");
    TextView author = text("本应用由 HengX 公益制作，免费为摄影爱好者提供朝霞、晚霞预测参考，项目公开源码，不以本应用进行盈利。", 14, INK);
    author.setOnClickListener(v -> open("https://v.douyin.com/hP6SQvaK4UQ/"));
    l.addView(author);
    gap(l, 12);
    l.addView(button("查看免责声明", this::showReadOnlyAgreement));
    gap(l, 12);
    l.addView(
        text(
            "原版完整算法尚未取得，所以无法做到预测数值的完全一致。鲜艳度只显示本地近似的文字判断，气溶胶显示真实 CAMS AOD；区域色块由本地近似模型独立计算，不是原站地图数据。",
            14,
            INK));
    gap(l, 12);
    l.addView(
        text(
            "应用优先使用前台 GPS，未允许位置权限时提醒授权，GPS 不可用时自动 IP"
                + " 大致定位；只获取单次位置，不进行后台追踪。查询坐标会发送至天气服务，偏好、最近记录与缓存保存在本机，不保存原始 IP。",
            14,
            INK));
    gap(l, 12);
    l.addView(
        text(
            "云况：Open-Meteo 提供的 NOAA GFS 或 ECMWF IFS 9 km；气溶胶：CAMS Global。所有结果按观测地点当地时间显示。",
            14,
            INK));
    fold(l, "城市索引来源", "省市区县名称和拼音来自 AreaCity（MIT），区划中心点作 WGS84 近似转换后用于天气采样，不作为用户精确位置。数据与许可随应用内置。");
    fold(
        l,
        "小地图与区域自动更新",
        "主页两张地图分别计算朝霞、晚霞，点击可放大。大地图只读取当前视野，拖动或缩放后自动更新。区域使用 7×7 观测点与 11×11 云况场，缺测处留空并自动补取；完整缓存有效期 30"
            + " 分钟。简化底图来自 Natural Earth / DataV.GeoAtlas，街道图 © OpenStreetMap contributors。");
    fold(
        l,
        "算法、公式与限制",
        "在太阳方向 80 / 180 / 300 / 450 / 600 km 处采样，固定 4 / 9 km 云层，以地球曲率近似光路，再结合低云、AOD、能见度和太阳高度计算。\n"
            + "z = h + x·tan(α) + x²/(2R)\n"
            + "缺少真实三维云、云光学厚度、地形和折射，不应宣称与原站一致。没有实况准确率回测。");
    gap(l, 12);
    l.addView(button("Open-Meteo 使用条件", () -> open("https://open-meteo.com/en/terms")));
    l.addView(text("版本 2.1.0 · 记录和缓存仅在本机。原生记录不自动迁移旧 WebView 记录。", 12, MUTED));
    footer(outer);
  }

  private void showReadOnlyAgreement() {
    showAgreement();
  }

  private void sunTest() {
    if (forecast == null) {
      alert("请先查询", "请先选择地区并取得一次预报。");
      return;
    }
    EditText input = new EditText(this);
    input.setHint("朝向角度：0°北，90°东");
    input.setInputType(8194);
    input.setText("270");
    new AlertDialog.Builder(this)
        .setTitle("悬日方向测试")
        .setView(input)
        .setPositiveButton(
            "计算",
            (d, w) -> {
              try {
                double v = Double.parseDouble(input.getText().toString());
                if (!Double.isFinite(v) || v < 0 || v >= 360) throw new NumberFormatException();
                double diff = Math.abs(norm(forecast.bearing - v + 180) - 180);
                alert(
                    "方向比较",
                    String.format(
                        Locale.ROOT,
                        "太阳方位 %.1f°\n街道朝向 %.1f°\n方向相差 %.1f°\n\n只比较方向，不含建筑、地形遮挡，不能代替机位勘察。",
                        forecast.bearing,
                        v,
                        diff));
              } catch (Exception e) {
                alert("角度无效", "请输入 0 到 359.9 之间的角度。");
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private String stamp(double t, String zone) {
    return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
        .format(Instant.ofEpochSecond((long) t).atZone(ZoneId.of(zone)));
  }

  private String clock(double t, String zone) {
    return DateTimeFormatter.ofPattern("HH:mm", Locale.CHINA)
        .format(Instant.ofEpochSecond((long) t).atZone(ZoneId.of(zone)));
  }

  private void alert(String title, String message) {
    if (isFinishing() || destroyed) return;
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(message == null ? "暂不可用" : message)
        .setPositiveButton("知道了", null)
        .show();
  }

  private void open(String url) {
    try {
      startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    } catch (ActivityNotFoundException e) {
      alert("无法打开", "未找到可用浏览器。");
    }
  }

  private void hideKeyboard() {
    View v = getCurrentFocus();
    if (v != null) {
      ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
          .hideSoftInputFromWindow(v.getWindowToken(), 0);
      v.clearFocus();
    }
  }
}
