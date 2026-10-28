# Third-party notices

The MIT license in `LICENSE` applies only to this project's original Android code and documentation. Existing rights, licenses and service conditions for the following content continue to apply. They have not been replaced with MIT.

## Bundled data and assets

| Content | Source / file | Rights and attribution |
| --- | --- | --- |
| City, district, pinyin and coordinate index | [AreaCity-JsSpider-StatsGov](https://github.com/xiangyuecn/AreaCity-JsSpider-StatsGov), `app/src/main/assets/chinaPlacesData.json` | MIT, copyright (c) 2019 xiangyuecn. Full license: [AreaCity-MIT.txt](app/src/main/assets/licenses/AreaCity-MIT.txt). Administrative centers are approximate weather sampling locations. |
| Simplified world outlines | [Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/), `app/src/main/assets/offlineMapData.json` | Public Domain according to Natural Earth's terms. Source attribution is retained here. |
| Province outlines | [DataV.GeoAtlas](https://help.aliyun.com/zh/datav/datav-atlas/developer-reference/atlas-sdk-introduction), `app/src/main/assets/provinceMapData.json` | Rights remain with the original rights holders and provider conditions apply. This project does not license these data under MIT. Weather overview only, not navigation or boundary determination. |
| Reference website logo | Sunsetbot reference material, `app/src/main/assets/site-logo.png` | Logo, name and related marks remain the property of their respective rights holders. No MIT or trademark license for these marks is granted. No affiliation or partnership is implied. |

## Online data and maps

- Weather is retrieved through [Open-Meteo](https://open-meteo.com/en/docs) using NOAA GFS / ECMWF IFS forecasts. API access and data use are subject to the provider's [terms](https://open-meteo.com/en/terms); data attribution follows its stated [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/) license.
- Aerosol forecasts use CAMS Global through [Open-Meteo Air Quality API](https://open-meteo.com/en/docs/air-quality-api), with CAMS / Open-Meteo attribution retained.
- Optional street maps use [OpenStreetMap](https://www.openstreetmap.org/copyright) data, copyright OpenStreetMap contributors, under ODbL. Standard tile access is also subject to the [tile usage policy](https://operations.osmfoundation.org/policies/tiles/). Attribution is displayed on the map.
- IP location and place lookup use IPIP, GeoJS, IP.SB and Open-Meteo geocoding as implemented. These providers receive the information needed for the request, including public network IP or query coordinates. Their services and databases are not covered by this project's MIT license.

Publishing source does not grant unlimited third-party API use. The APK does not bundle offline weather forecasts.

## Build and test dependencies

| Component | Purpose | License source |
| --- | --- | --- |
| [Gradle](https://github.com/gradle/gradle) and Wrapper | Build | Apache License 2.0 and bundled third-party notices |
| [Android Gradle Plugin / Android SDK](https://developer.android.com/studio) | Compile and package | Each component's accompanying license; SDK terms apply separately |
| [JUnit 4](https://github.com/junit-team/junit4) | Local tests only | Eclipse Public License 1.0 |
| [JSON-java 20240303](https://github.com/stleary/JSON-java/tree/20240303) | JSON implementation for JVM tests only | Public Domain, as stated in this version's LICENSE |

Runtime JSON uses Android's system `org.json`. Bootstrap and Leaflet from the website are not packaged as native runtime libraries. The website's own notices remain in effect.

## Model and interface references

The interface and feature set reference [Sunsetbot](https://www.sunsetbot.top/). Solar position calculations reference [NOAA](https://gml.noaa.gov/grad/solcalc/calcdetails.html). The afterglow and cloud-sea judgments are independent empirical approximations and do not claim the original site's full formulas, accuracy or map data.
