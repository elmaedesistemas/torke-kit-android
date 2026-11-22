# Changelog

## 0.1.0 · 2026-11-21

First open release, the same as TorkeKit for iOS 0.1.0.

- Engine: `AxisCalibration`, `SpeedFilter`, `RunAnalyzer`, `DemoRun`.
- `RunSession` with `RunSnapshot`, `RunEvent` and `RunSessionOptions`.
- Launch detection catches a launch that ramps up over a few tenths of a second; a bump while
  armed no longer disarms, rolling or creeping does.
- `torke.run` JSON (`TorkeRun`) with the reference fixture `Fixtures/demo-42.torkerun.json`.
- `RunDataProvider` (a `Flow` of `SensorSample`) and `RecordedRunProvider`.
- `torke-kit-sensors`: `PhoneSensorProvider` for Android (SensorManager + LocationManager).
