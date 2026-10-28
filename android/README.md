# SunsetHengX Android 2.1.0

安卓原生版：保留原有界面，新增五天云海预测、逐小时图表和数据表，继续使用 GPS 优先、失败自动 IP 定位。[下载安装包](https://github.com/HengX019/sunsetbot/releases/tag/android-v2.1.0)。

Native Android companion to [HengX019/sunsetbot](https://github.com/HengX019/sunsetbot), made by HengX. It preserves the original dark blue interface and top menu, with native buttons, dialogs, tables and Canvas charts. It does not require WebView, HTML or JavaScript.

Android 8.0 / API 26 or newer. Application ID: `cn.hengx.sunset`. Version: **2.1.0**, versionCode **6**. Official releases use the existing release signature and can update older official installations. Debug builds use `cn.hengx.sunset.debug` and can be installed alongside them.

## New in 2.1.0

- Five days of cloud-sea conditions using the homepage location and GFS / EC selection.
- Native date buttons, three horizontally scrollable hourly charts, and an hourly table showing low clouds, humidity, temperature, wind and precipitation.
- Morning guidance considers only future hours from 04:00 through 09:59 in the forecast location's time zone. Missing required data is shown explicitly.

The 2.0.2 location fixes are retained: agreeing immediately starts the permission check without waiting for the city index or map. Every foreground entry checks location permission. GPS is preferred; failure or a roughly 25-second timeout triggers IP fallback. The existing location button offers permission access when permission is absent. A system settings link is used when Android will no longer display a permission prompt. Android 10 does not have the precise/approximate selector introduced in Android 12.

## Features

| Feature | Native implementation |
| --- | --- |
| Today / tomorrow, sunrise / sunset | Four-option selection dialog in the existing button position |
| GFS / EC data source | Selection dialog |
| City, district, pinyin and coordinate search | Autocomplete, candidates and province/city/district index |
| Consent and location | App dialog, system permission, GPS-first / IP fallback |
| Afterglow, AOD and clarity | Original five-row result card and explanations |
| Preview maps and large map | Canvas, contours, pan/pinch, automatic viewport updates |
| Optional street map and opacity | Native controls and OSM tiles |
| Detailed forecast, cloud section, sun alignment | Tables, layered cloud illustration and calculation dialog |
| Five-day cloud sea and hourly weather | Date buttons, three charts and horizontally scrollable table |
| Last ten query snapshots | App-private storage and records page |
| Knowledge, sources and limitations | Native pages and dialogs |

The city index, simplified world outlines and province outlines are bundled. Missing place coordinates may be resolved online; `latitude,longitude` input is supported. The afterglow model is limited to latitudes within 72 degrees of the equator. No background location, camera or shared-storage permission is requested.

## Build

From the repository root, enter `android/`. Use JDK 17, Android SDK Platform 35 and Build Tools 35.0.0. Gradle Wrapper 8.11.1 and Android Gradle Plugin 8.9.2 are included. Set `ANDROID_HOME` or a local `sdk.dir` in `local.properties`; do not commit local configuration.

```sh
cd android
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug
```

The Debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Android CI runs unit tests, Release Lint and a Debug build, and uploads the APK and reports. CI has no production signing key and does not automatically publish official releases.

For a signed Release, supply your own private signing configuration:

```sh
export SUNSET_KEYSTORE=/absolute/path/your-release.p12
export SUNSET_KEY_ALIAS=your-alias
export SUNSET_STORE_PASSWORD='your-password'
export SUNSET_KEY_PASSWORD="$SUNSET_STORE_PASSWORD"
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

Release output is under `app/build/outputs/apk/release/`. Without signing variables, Release output is unsigned. Private keys, credentials and signing backups are excluded from the repository and source archive. A build signed with a different key cannot update the official installed package.

Google Play services, map API keys, a web server, Node.js and a database are not required. Live weather, IP fallback and optional street tiles need internet access.

## Models and data

The afterglow model is this project's independent approximation, not the complete original Sunsetbot algorithm. It samples 80 / 180 / 300 / 450 / 600 km toward the sun, approximates light paths using Earth curvature and fixed 4 / 9 km cloud layers, and combines cloud cover, low-cloud obstruction, visibility, AOD and solar altitude. Hourly data is interpolated every five minutes around the event; this does not give the weather forecast five-minute accuracy. Regional maps use sparse samples and interpolation; zooming does not improve weather resolution.

Cloud-sea labels use the website's unvalidated empirical rules for low clouds, humidity, wind and precipitation. They are conditions, not probabilities. Missing required values prevent assessment. Terrain, actual cloud height, inversions and whether a summit is above the clouds are not modeled. City centers and IP locations do not represent summit weather; low cloud and high humidity can also mean fog or rain. Neither model has been validated against observations.

- Weather: Open-Meteo, NOAA GFS / ECMWF IFS. Aerosols: CAMS Global.
- City index: AreaCity (MIT), with its original license retained.
- World outlines: Natural Earth (Public Domain). Province outlines: DataV.GeoAtlas, with source and original rights retained.
- Optional street map: OpenStreetMap contributors (ODbL).

See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for sources and third-party rights. Coordinates are sent to weather services; IP services receive the request's public IP. The app does not save raw IP addresses. Records and cache stay on the device and can be cleared in Android settings.

## Verification and release checklist

Version 2.0.2 previously passed unit tests, Lint, a signed build and Android 15 emulator checks for permission, GPS, IP fallback, queries and maps. Its tests compare the model with the original JavaScript fixture and check city search and GPS coordinate preservation. Those historical results do not establish that every 2.1.0 release check has passed.

The local 2.1.0 release passed 9 JUnit tests, Release Lint, signed APK verification and an upgrade installation. Android 15 emulator checks confirmed live cloud-sea data, date switching including the fifth day, native charts and hourly table, GPS and the Android system location permission dialog. W2019 / Android 10 has not been tested on physical hardware.

Additional checks for future changes:

- Unit tests, Release Lint, APK build and production signature verification.
- Cloud-sea thresholds, missing values, local dates, future morning filtering and all five date choices.
- Three charts, hourly table, horizontal scrolling and failure/retry behavior.
- Old-request cancellation when changing location/model, leaving the page or backgrounding.
- Fresh-install consent, system location permission, denial/IP fallback and an upgrade installation.

Samsung W2019 / Android 10 physical-device verification remains outstanding. Other emulator versions do not replace it. Records from the old WebView app have a different format and are not automatically migrated.

## License

Original Android code in this directory is licensed under the [MIT License](LICENSE), copyright 2026 HengX. This does not relicense third-party data, maps, logos, names or other assets, or assign a new license to the website elsewhere in this repository. This project is independent of Sunsetbot and has no affiliation or partnership with it.

Author: [HengX](https://v.douyin.com/hP6SQvaK4UQ/).
