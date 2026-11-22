# Changelog

## 0.2.0 · 2026-11-21

More measurements, run quality and suspension geometry. Same results as TorkeKit for iOS 0.2.0,
checked with shared fixtures.

- Marks: `elapsed(toDistance)`, `speedKmh(atDistance)`, `time(toKmh)`, `time(fromKmh, toKmh)`,
  `zeroTo60mph`, `thousandFeet`, `halfMile` (extensions on `RunResult`). `RunAnalyzer.analyze(…, stopDistance)` reads past the 1/4.
- `RunResult.launchG` and `peakG`.
- Rolling and braking: `IntervalAnalyzer`, `IntervalResult`, `RunSession.arm(mode)` with `RunMode.Rolling`
  and `RunMode.Braking`, the `RunEvent.IntervalFinished` event and `RunFailure.noInterval`.
  `AxisCalibration.forwardAccel(raw, orientedBy)` checks "forward" against the GPS.
- `Weather`: air density, density altitude, SAE J1349 correction; `RunResult.corrected(weather)`.
- `PowerEstimate`: Hale from trap speed and from ET, wheel power along the run.
- `RunQuality`: GPS rate, accuracy and dropouts, slope from GPS altitude, loose mount, phone moving,
  sensors against GPS; graded good, fair or poor.
- Suspension: `SuspensionSetup` (4-link, ladder bar, torque arm), `SuspensionGeometry` (instant center,
  anti-squat, bar angles, warnings), `TargetWindow`, `FourLinkHoles` and `SetupChange.between`.
- `GPSSample.altitude`; `TorkeRun.interval`, `weather` and `suspension`. Optional fields: format version 1.
- `PhoneSensorProvider` sends the GPS altitude.

## 0.1.0 · 2026-11-21

First open release, the same as TorkeKit for iOS 0.1.0.

- Engine: `AxisCalibration`, `SpeedFilter`, `RunAnalyzer`, `DemoRun`.
- `RunSession` with `RunSnapshot`, `RunEvent` and `RunSessionOptions`.
- Launch detection catches a launch that ramps up over a few tenths of a second; a bump while
  armed no longer disarms, rolling or creeping does.
- `torke.run` JSON (`TorkeRun`) with the reference fixture `Fixtures/demo-42.torkerun.json`.
- `RunDataProvider` (a `Flow` of `SensorSample`) and `RecordedRunProvider`.
- `torke-kit-sensors`: `PhoneSensorProvider` for Android (SensorManager + LocationManager).
