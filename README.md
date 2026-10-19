# Ce autobuz iau? for Wear OS

The [Ce autobuz iau?](https://github.com/bmarian/conexiuni-cluj) bus and tram tracker as a Wear OS app. The website ran in the watch browser, but it couldn't get my location and froze every time I lowered my wrist, so I built this.

**Join the beta:** [join the testers group](https://groups.google.com/g/ce-autobuz-iau-beta), then install “Ce autobuz iau?” from Google Play on your watch. Needs Wear OS 2 or newer.

<p align="center">
  <img src="readme/home.png" width="180" alt="Favorite metro stations in București">
  <img src="readme/stop.png" width="180" alt="A metro station with its next departure">
  <img src="readme/departure.png" width="180" alt="One departure, with the buttons to follow it and save the line">
  <img src="readme/route-map.png" width="180" alt="A line on the map with the next arrival above each stop">
</p>

## What it does

- Uses the city picked on the phone
- Favorite stops on the first screen
- Search for a stop or a line by voice or keyboard
- Departures for any stop. Tracked buses get a live countdown, the rest show the timetable
- Stations with a metro, tram, trolleybus or bus icon
- Follow a bus. The countdown stays on the watch face and it buzzes when the bus is close
- Lines on the map with the next arrival at each stop, and their timetables
- The stops around you, as a list or on a map
- A tile and a watch face complication with the next bus
- Turn the bezel to scroll and zoom
- Works on its own over Wi-Fi or LTE
- Works with the [Android app](https://github.com/bmarian/conexiuni-cluj-mobile): followed buses, trips, reminders and favorites show on both
- Copy your favorites from the website by scanning a code

<p align="center">
  <img src="readme/ambient.png" width="180" alt="A followed bus in always-on mode">
  <img src="readme/timetable.png" width="180" alt="A line's timetable from the current hour on">
</p>

---

## Running it yourself

You'll need Android Studio with the Android SDK, and a Wear OS emulator or a watch with Wireless debugging on. The map needs no key.

```bash
./gradlew installRelease
```

```bash
./gradlew testDebugUnitTest
```

**The release build** is much faster on a watch than the debug one. Without a key it's signed with the debug key, so it still installs.

**The logic** is the website's, ported to Kotlin in [`logic/`](app/src/main/java/online/bmarian/bus/logic). [`ParityTest`](app/src/test/java/online/bmarian/bus/ParityTest.kt) runs both on the same real data and fails if a single minute differs.

**Releasing** needs a `keystore.properties` next to this README, with the same key as the Android app. Git ignores it.

```properties
storeFile=../keys/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Watch version codes are 2xxxx and the phone's 1xxxx. `./gradlew bundleRelease` builds the bundle, which goes on the Android app's Google Play listing.

## Credits

| Source                                                                             | Used for                                                                  |
| ---------------------------------------------------------------------------------- | ------------------------------------------------------------------------- |
| [Tranzy.ai](https://tranzy.ai/)                                                    | Live positions, lines and stops for Cluj-Napoca, Iași, Oradea and Constanța |
| [CTP Cluj-Napoca](https://ctpcj.ro/)                                               | Cluj-Napoca's timetables                                                  |
| [gtfs.ro](https://gtfs.ro/)                                                        | Timetables for Iași, Oradea and Constanța                                 |
| [TPBI](https://tpbi.ro/)                                                           | Lines, timetables and live positions for București                        |
| [OpenStreetMap](https://www.openstreetmap.org/) contributors                       | Map data                                                                  |
| [OpenFreeMap](https://openfreemap.org/), [OpenMapTiles](https://openmaptiles.org/) | Map tiles                                                                 |
| [MapLibre](https://maplibre.org/)                                                  | The map                                                                   |
| [ZXing](https://github.com/zxing/zxing)                                            | QR codes                                                                  |
