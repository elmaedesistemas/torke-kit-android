package today.torke.kit.session

import kotlinx.serialization.Serializable
import today.torke.kit.engine.Coordinate
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.DragMark
import today.torke.kit.engine.IntervalResult
import today.torke.kit.engine.RunResult

/** Where a run is. */
public sealed interface RunPhase {
    /** Not recording. Location updates still refresh the snapshot (a GPS preview). */
    public data object Idle : RunPhase
    /** Recording, waiting for the car to stand still. */
    public data object WaitingStill : RunPhase
    /** Standing still: the timer starts by itself at launch. */
    public data object Armed : RunPhase
    /** Moving. [start] is the launch on the samples' clock. */
    public data class Running(val start: Double) : RunPhase
    /** Done: [RunSnapshot.result] has the times. */
    public data object Finished : RunPhase
    public data class Failed(val failure: RunFailure) : RunPhase

    public val isRecording: Boolean get() = this is WaitingStill || this is Armed || this is Running
}

/** What a run measures. */
public sealed interface RunMode {
    /** From a standstill: 60 ft, 1/8, 1/4, 0–100. The default. */
    public data object StandingStart : RunMode
    /** Already moving: from [fromKmh] to [toKmh], such as 60–130 km/h. */
    public data class Rolling(val fromKmh: Double = 60.0, val toKmh: Double = 130.0) : RunMode
    /** From [fromKmh] to a stop, such as 100–0 km/h. */
    public data class Braking(val fromKmh: Double = 100.0) : RunMode
}

/** Why a run could not be measured. */
@Serializable
public enum class RunFailure {
    /** The run ended without a launch from a standstill. */
    noLaunch,
    /** A rolling or braking run that did not cover its speed range. */
    noInterval,
    /** The device has no motion sensors (or they are off). */
    noMotionSensors,
    /** The app is not allowed to use the location. */
    locationDenied,
}

/** What happened during a run, in order. Collect [RunSession.events]. */
public sealed interface RunEvent {
    /** The car stood still long enough: it can launch. */
    public data object Armed : RunEvent
    /** It moved before launching: waiting for it to stand still again. */
    public data object Disarmed : RunEvent
    /** The car launched, at [time] on the samples' clock, from [location] if the GPS had one. */
    public data class Launched(val time: Double, val location: Coordinate?) : RunEvent
    /** A live estimate of a mark, from the GPS alone. The official times come in [Finished]. */
    public data class Passed(val mark: DragMark, val elapsed: Double) : RunEvent
    public data class Finished(val result: RunResult) : RunEvent
    /** A rolling or braking run is done. */
    public data class IntervalFinished(val result: IntervalResult) : RunEvent
    public data class Failed(val failure: RunFailure) : RunEvent
}

/** The live state of a [RunSession], for the screen. */
public data class RunSnapshot(
    val phase: RunPhase = RunPhase.Idle,
    /** Live GPS speed, m/s. */
    val speed: Double = 0.0,
    /** Seconds since the launch. */
    val elapsed: Double = 0.0,
    /** Meters since the launch, live estimate from the GPS. */
    val distance: Double = 0.0,
    /** Horizontal accuracy of the last fix, in meters. */
    val gpsAccuracy: Double? = null,
    /** The last known position. */
    val location: Coordinate? = null,
    /** Where the run launched. Send it to your server to decide where the run counts. */
    val startLocation: Coordinate? = null,
    val mode: RunMode = RunMode.StandingStart,
    /** The official times of a standing start, once [phase] is [RunPhase.Finished]. */
    val result: RunResult? = null,
    /** The result of a rolling or braking run, once [phase] is [RunPhase.Finished]. */
    val interval: IntervalResult? = null,
    /** GPS fixes per second over the last few seconds: 1 on most phones, up to 10 on some. Null until two fixes arrive. */
    val gpsRate: Double? = null,
) {
    val speedKmh: Double get() = speed * 3.6
}

/** Thresholds of the live detection. The defaults are tuned for a phone in a rigid mount. */
public data class RunSessionOptions(
    /** Seconds the car must stand still before it is armed. */
    val stillWindow: Double = 1.0,
    /** Horizontal acceleration (m/s²) under which the car counts as still. */
    val stillAccel: Double = 0.6,
    /** GPS speed (m/s) under which the car counts as stopped. */
    val stillSpeed: Double = 1.5,
    /** Horizontal acceleration (m/s²) that marks a launch… */
    val launchAccel: Double = 2.0,
    /** …when every sample of the last [launchHold] seconds stays above this. */
    val launchHoldAccel: Double = 1.4,
    val launchHold: Double = 0.15,
    /** Movement that has not become a launch this long after [launchHold] disarms. */
    val launchGrace: Double = 0.35,
    /** The run ends by itself this far past the launch (meters). */
    val finishDistance: Double = DragDistance.QUARTER_MILE + 25,
    /** …or when the speed drops under this share of the peak (lifting off). */
    val backOffRatio: Double = 0.6,
    /** …or after this long (seconds). */
    val maxDuration: Double = 40.0,
    /** How far past the launch the analysis reads, in meters. Raise it for the 1/2 mile (together with [finishDistance]). */
    val analysisDistance: Double = DragDistance.QUARTER_MILE + 20,
)
