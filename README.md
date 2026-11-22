# TorkeKit for Android

Open-source drag racing timing with a phone: 60 ft, 1/8 mile, 1/4 mile, 0–100 km/h and trap
speeds from the accelerometer and the GPS, however the phone sits in its mount. Also rolling
and braking intervals, weather correction, power estimates, a quality grade for each run and
suspension geometry (4-link, ladder bar, torque arm).

It is the engine behind [Torke](https://torke.today), in Kotlin. [TorkeKit for iOS](https://github.com/elmaedesistemas/torke-kit-ios)
has the same API, the same results and reads the same files.

> *En español:* TorkeKit mide piques con el teléfono (60 pies, 1/8, 1/4, 0 a 100 y
> velocidades) a partir del acelerómetro y el GPS. Es el motor de Torke, abierto para quien
> quiera construir apps de cronometraje, herramientas para pistas o productos para carros.

📖 **Documentation:** [developers.torke.today](https://developers.torke.today), in Spanish: guides, the full API with Swift and Kotlin side by side, and interactive labs for the session, weather and 4-link geometry.

| Module | What it has | Depends on |
| --- | --- | --- |
| `torke-kit` | Engine, `RunSession`, `torke.run` format, `RecordedRunProvider`, `DemoRun` | Kotlin (JVM), coroutines, kotlinx.serialization |
| `torke-kit-sensors` | `PhoneSensorProvider` (SensorManager + LocationManager), `PhoneBarometer` | Android, `minSdk 26` |
| `torke-validate` | The validation command | Java 17 |

`torke-kit` has no Android code, so it also runs on a server, for example to analyze uploaded
runs again.

## Install (JitPack)

```kotlin
// settings.gradle.kts
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
    }
}

// build.gradle.kts of the app
dependencies {
    implementation("com.github.elmaedesistemas.torke-kit-android:torke-kit-sensors:0.3.0")
    // Only the core, without Android (a server, tests):
    // implementation("com.github.elmaedesistemas.torke-kit-android:torke-kit:0.3.0")
}
```

Tags have no `v`, as on iOS: `0.1.0`, `0.2.0`, `0.3.0`…

## Use

```kotlin
val phone = PhoneSensorProvider(context)       // ask for ACCESS_FINE_LOCATION first
val session = RunSession()

session.start(phone, lifecycleScope)           // GPS preview: snapshot.speed, snapshot.location
session.arm()                                  // recording; the timer starts by itself at launch

lifecycleScope.launch {
    session.events.collect { event ->
        when (event) {
            RunEvent.Armed -> say("Ready. Launch when you want.")
            is RunEvent.Passed -> show("${event.mark} ≈ ${event.elapsed} s (live)")
            is RunEvent.Finished -> show("1/4 mile: ${event.result.quarterMile} s")
            is RunEvent.Failed -> show("No run: ${event.failure}")
            else -> {}
        }
    }
}
```

- `session.snapshot` is a `StateFlow<RunSnapshot>`: collect it in Compose with `collectAsState()`.
- The run finishes by itself past the quarter mile, when the driver lifts, or after 40 s.
  `session.finish()` ends it by hand; `session.cancel()` drops it.
- `snapshot.startLocation` is where the run launched. Decide on your server whether it counts.
- Keep the screen on while recording. To record with the screen off, collect inside a
  foreground service of type `location`.
- Feed it from one thread: `RunSession` is not thread-safe.

### Recorded runs

```kotlin
val run = session.recording(device = Build.MODEL, app = "MyApp 1.0")
val json = run.encode()                        // the torke.run JSON
val back = TorkeRun.decode(json)
val result = back.analyze()                    // the times again
RunSession().run(RecordedRunProvider(back))
```

The format is documented in [docs/FORMAT.md](docs/FORMAT.md). `RecordedRunProvider.demo()`
plays a simulated street car; the same seed gives the same run as on iOS.

### More measurements

```kotlin
val r = run.analyze(stopDistance = 850.0)!!       // read past the quarter
r.thousandFeet; r.halfMile; r.zeroTo60mph
r.time(fromKmh = 100.0, toKmh = 200.0)
r.launchG; r.peakG

session.arm(RunMode.Rolling(60.0, 130.0))          // or RunMode.Braking(100.0)
// … is RunEvent.IntervalFinished -> event.result.time, event.result.distance
```

### Weather, power and quality

```kotlin
val weather = Weather(temperatureC = 31.0, humidity = 70.0, pressureHpa = 910.0)
weather.densityAltitude                             // meters
val corrected = result.corrected(weather)           // SAE J1349
PowerEstimate.fromTrapSpeed(massKg = 1350.0, quarterMileKmh = result.quarterMileKmh!!)
PowerEstimate.peakWheelHp(result, massKg = 1350.0)

val quality = run.quality()!!                       // good, fair or poor, with the issues
```

### Suspension geometry

Textbook side-view geometry for 4-link, ladder bar and torque arm: instant center, anti-squat
and bar angles. Target windows are yours: TorkeKit ships no one's values.

```kotlin
val setup = SuspensionSetup.fourLink(
    wheelbase = 106.0, cgHeight = 21.0,
    upperRear = Point2(4.0, 21.0), upperFront = Point2(30.0, 23.0),
    lowerRear = Point2(2.0, 9.0), lowerFront = Point2(32.0, 12.0),
)
val g = setup.geometry()                            // g.icLength, g.icHeight, g.antiSquat, g.warnings

val target = TargetWindow(ValueRange(60.0, 75.0), ValueRange(8.0, 12.0), ValueRange(80.0, 110.0))
val best = holes.ranked(setup, target).first()      // which holes to try
val change = SetupChange.between(before, after)     // to read next to the 60 ft
```

`x` is forward of the rear axle centerline, `y` up from the ground, in inches or centimeters.

### Validation against timeslips

How far TorkeKit is from the track's timer: each run is analyzed again and compared, mark by mark,
with its timeslip. Many runs give the bias, the typical error (RMS) and the worst case per mark.

```kotlin
val slip = Timeslip(sixtyFeet = 2.12, quarterMile = 12.91, quarterMileSpeed = 114.2, speedUnit = SpeedUnit.mph)
val comparison = run.compare(slip)                 // measured, reference and error per mark
val report = ValidationReport.of(listOf(ValidationInput("Run 1", run.analyze(), slip)))
report.summaries                                   // bias, meanAbsolute, rms, worst, passed
```

The same from the terminal, on any computer with Java 17, with a `torke.validation` JSON listing
runs and timeslips (see `Fixtures/validation.json`):

```bash
./gradlew :torke-validate:installDist
torke-validate/build/install/torke-validate/bin/torke-validate Fixtures/validation.json --runs
torke-validate/build/install/torke-validate/bin/torke-validate my-run.torkerun.json --60ft 2.12 --1/4 12.91 --1/4-speed 114.2 --mph
```

It exits with 0 when every mark is within its target (60 ft ±0.10 s, 1/4 mile ±0.15 s, trap speed
±3 km/h), so it fits in CI.

### The phone's GPS and barometer

- `session.snapshot.gpsRate` is how many fixes per second the GPS really gives: 1 on most phones,
  5–10 on some.
- On Android 12+, `PhoneSensorProvider` keeps the GNSS chip in full tracking (no power-saving gaps)
  while it records; `PhoneSensorProvider(context, fullTracking = false)` turns it off.
- `phone.gnss` (`StateFlow<GnssInfo?>`): satellites used, in view and their mean signal (C/N0).
- `PhoneBarometer(context).stationPressureHpa()` reads the station pressure for `Weather`.

TorkeKit is phone-only by design: no external receivers or other hardware.

## Accuracy

The tests simulate runs with exact physics and check the targets: ±0.15 s on the 1/4 mile,
±0.10 s on 60 ft and ±0.20 s on 0–100 km/h, with a 1 Hz phone GPS, sensor noise and a tilted
mount. They also read `Fixtures/demo-42.torkerun.json`, written by TorkeKit for iOS, and must
get the same times. To check it against real runs, use
[`torke-validate`](#validation-against-timeslips) with your timeslips.

## Development

```bash
./gradlew :torke-kit:test
./gradlew :torke-kit-sensors:assembleRelease
```

## License

Apache 2.0. See [LICENSE](LICENSE).
