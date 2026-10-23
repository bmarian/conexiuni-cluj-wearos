# Ce autobuz iau? for Wear OS

The [Ce autobuz iau?](https://github.com/bmarian/conexiuni-cluj) bus, tram and trolleybus tracker as a Wear OS app.

**Join the beta:** [join the testers group](https://groups.google.com/g/ce-autobuz-iau-beta), then install “Ce autobuz iau?” from Google Play on your watch. Needs Wear OS 2 or newer.

<p align="center">
  <img src="readme/home.png" width="180" alt="Favorite stops on the watch, the one by the dorm first">
  <img src="readme/stop.png" width="180" alt="The stop by the dorm, with the 35 live in 3 minutes">
  <img src="readme/departure.png" width="180" alt="One departure, with the buttons to get a reminder and save the line">
  <img src="readme/route-map.png" width="180" alt="Line 35 on the map with the next arrival above each stop">
</p>

## What it does

- Uses the city picked on the phone
- Favorite stops on the first screen
- Search for a stop or a line by voice or keyboard
- Departures for any stop. Tracked buses get a live countdown, the rest show approximate times
- Follow a bus, the countdown stays on the watch face, and it buzzes when the bus is close
- Reminders for the next bus, with the walk worked out by the phone
- Lines on the map with the next arrival at each stop, and their timetables
- The surrounding stops, as a list or on a map
- A tile and a watch face complication with the next bus
- Turn the bezel to scroll and zoom
- Works on its own over Wi-Fi or LTE
- Works with the [Android app](https://github.com/bmarian/conexiuni-cluj-mobile), followed buses, trips, reminders and favorites show on both
- Copy your favorites from the website by scanning a QR

<p align="center">
  <img src="readme/timetable.png" width="180" alt="Line 35's timetable from the current hour on">
</p>

The cities and where their data comes from are listed in the [website's README](https://github.com/bmarian/conexiuni-cluj#cities).

---

## Running it yourself

You'll need Android Studio with the Android SDK, and a Wear OS emulator or a watch with Wireless debugging on. The map needs no key.

```bash
./gradlew installRelease
```

```bash
./gradlew testDebugUnitTest
```

The release build is much faster on a watch than the debug one. Without a key it's signed with the debug key, so it still installs.

<details>
<summary><b>Parity with the website</b></summary>

<br>

The timetable and departure logic is the website's, ported to Kotlin in [`logic/`](app/src/main/java/online/bmarian/bus/logic). [`ParityTest`](app/src/test/java/online/bmarian/bus/ParityTest.kt) runs both on the same real data and fails if a single minute differs.

</details>

<details>
<summary><b>Releasing</b></summary>

<br>

Releasing needs a `keystore.properties` next to this README, with the same key as the Android app. Git ignores it.

```properties
storeFile=../keys/upload.jks
storePassword=...
keyAlias=upload
keyPassword=...
```

Watch version codes are 2xxxx and the phone's 1xxxx. `./gradlew bundleRelease` builds the bundle, which goes on the Android app's Google Play listing. Release builds need NDK 28.2.13676358, which ships MapLibre's debug symbols with the bundle.

</details>

## Credits

| Source                                                                             | Used for  |
| ---------------------------------------------------------------------------------- | --------- |
| [OpenStreetMap](https://www.openstreetmap.org/copyright) contributors              | Map data  |
| [OpenFreeMap](https://openfreemap.org/), [OpenMapTiles](https://openmaptiles.org/) | Map tiles |
| [MapLibre](https://maplibre.org/)                                                  | The map   |
| [ZXing](https://github.com/zxing/zxing)                                            | QR codes  |
