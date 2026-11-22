# TorkeKit for Android

Open-source drag racing timing with a phone: 60 ft, 1/8 mile, 1/4 mile, 0–100 km/h and trap
speeds from the accelerometer and the GPS, however the phone sits in its mount.

It is the engine behind [Torke](https://torke.today), in Kotlin. [TorkeKit for iOS](https://github.com/elmaedesistemas/torke-kit-ios)
has the same API, the same results and reads the same files.

> *En español:* TorkeKit mide piques con el teléfono (60 pies, 1/8, 1/4, 0 a 100 y
> velocidades) a partir del acelerómetro y el GPS. Es el motor de Torke, abierto para quien
> quiera construir apps de cronometraje, herramientas para pistas o productos para carros.

| Module | What it has | Depends on |
| --- | --- | --- |
| `torke-kit` | Engine, `RunSession`, `torke.run` format, `RecordedRunProvider`, `DemoRun` | Kotlin (JVM), coroutines, kotlinx.serialization |
| `torke-kit-sensors` | `PhoneSensorProvider` (SensorManager + LocationManager) | Android, `minSdk 26` |

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
    implementation("com.github.elmaedesistemas.torke-kit-android:torke-kit-sensors:0.1.0")
    // Only the core, without Android (a server, tests):
    // implementation("com.github.elmaedesistemas.torke-kit-android:torke-kit:0.1.0")
}
```

Tags have no `v`, as on iOS: `0.1.0`, `0.2.0`…

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

## Accuracy

The tests simulate runs with exact physics and check the targets: ±0.15 s on the 1/4 mile,
±0.10 s on 60 ft and ±0.20 s on 0–100 km/h, with a 1 Hz phone GPS, sensor noise and a tilted
mount. They also read `Fixtures/demo-42.torkerun.json`, written by TorkeKit for iOS, and must
get the same times. Validation against dragstrip timeslips is on the [roadmap](docs/ROADMAP.md).

## Development

```bash
./gradlew :torke-kit:test
./gradlew :torke-kit-sensors:assembleRelease
```

## License

Apache 2.0. See [LICENSE](LICENSE).
