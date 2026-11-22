package today.torke.kit.session

import java.time.Instant
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import today.torke.kit.engine.AxisCalibration
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.DragMark
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.IntervalAnalyzer
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.engine.RunResult
import today.torke.kit.format.TorkeRun
import today.torke.kit.input.RunDataProvider
import today.torke.kit.input.SensorSample

/**
 * The SDK's front door: connect a sensor source, [arm], read [snapshot] and collect [events].
 * The session spots the standstill, the launch and the end by itself, then computes the
 * official times with [RunAnalyzer].
 *
 * ```kotlin
 * val session = RunSession()
 * session.start(PhoneSensorProvider(context), lifecycleScope)  // GPS preview from here
 * session.arm()                                                // times start at launch
 * lifecycleScope.launch {
 *     session.events.collect { if (it is RunEvent.Finished) show(it.result) }
 * }
 * ```
 *
 * Not thread-safe: feed it from one thread (the main thread in an app).
 */
public class RunSession(public var options: RunSessionOptions = RunSessionOptions()) {
    private val _snapshot = MutableStateFlow(RunSnapshot())
    public val snapshot: StateFlow<RunSnapshot> = _snapshot.asStateFlow()
    private val _events = MutableSharedFlow<RunEvent>(extraBufferCapacity = 256)
    public val events: SharedFlow<RunEvent> = _events.asSharedFlow()

    private val motion = ArrayList<RawMotionSample>(4000)
    private val gps = ArrayList<GPSSample>()
    private var peakSpeed = 0.0
    private val passed = mutableSetOf<DragMark>()
    private var recordedAt: Instant? = null
    private var movementStart: Double? = null
    private var job: Job? = null

    private var state: RunSnapshot
        get() = _snapshot.value
        set(value) { _snapshot.value = value }

    // Source

    /** Starts reading a source in [scope]. Calling it again replaces the previous one. If the source ends during a run, the run finishes. */
    public fun start(provider: RunDataProvider, scope: CoroutineScope) {
        stop()
        job = scope.launch { run(provider) }
    }

    /** Reads a source until it ends, then finishes the run. Use it when you manage the coroutine yourself, and in tests. */
    public suspend fun run(provider: RunDataProvider) {
        provider.samples().collect { process(it) }
        finish()
    }

    /** Stops reading the source. A run in progress is cancelled. */
    public fun stop() {
        job?.cancel()
        job = null
        if (state.phase.isRecording) cancel()
    }

    // Run

    /**
     * Starts recording. In a standing start the timer starts by itself when the car launches
     * from a standstill; rolling and braking runs record from now and finish by themselves once
     * the speed range is covered.
     */
    public fun arm(mode: RunMode = RunMode.StandingStart) {
        clear()
        recordedAt = Instant.now()
        state = state.copy(mode = mode, phase = if (mode == RunMode.StandingStart) RunPhase.WaitingStill else RunPhase.Armed)
    }

    /** Ends the run now and computes the times. Without a launch it fails with [RunFailure.noLaunch]. */
    public fun finish(): RunResult? {
        if (!state.phase.isRecording) return state.result
        if (state.mode != RunMode.StandingStart) {
            finishInterval()
            return null
        }
        val result = if (state.phase is RunPhase.Running) RunAnalyzer.analyze(AxisCalibration.forwardAccel(motion), gps, options.analysisDistance) else null
        if (result == null) {
            fail(RunFailure.noLaunch)
            return null
        }
        state = state.copy(result = result, phase = RunPhase.Finished)
        emit(RunEvent.Finished(result))
        return result
    }

    private fun finishInterval() {
        val result = when (val mode = state.mode) {
            is RunMode.Rolling -> IntervalAnalyzer.rolling(motion, gps, mode.fromKmh, mode.toKmh)
            is RunMode.Braking -> IntervalAnalyzer.braking(motion, gps, mode.fromKmh)
            RunMode.StandingStart -> null
        }
        if (result == null) return fail(RunFailure.noInterval)
        state = state.copy(interval = result, phase = RunPhase.Finished)
        emit(RunEvent.IntervalFinished(result))
    }

    /** Drops the run and goes back to [RunPhase.Idle]. */
    public fun cancel(): Unit = clear()

    /** Marks the run as failed, for errors the app finds (permissions, sensors). */
    public fun fail(failure: RunFailure) {
        state = state.copy(phase = RunPhase.Failed(failure))
        emit(RunEvent.Failed(failure))
    }

    /** Everything recorded since [arm], ready to save, share or replay with [today.torke.kit.input.RecordedRunProvider]. */
    public fun recording(device: String? = null, app: String? = null): TorkeRun =
        TorkeRun(recordedAt = recordedAt?.toString(), device = device, app = app, motion = motion.toList(), gps = gps.toList(), result = state.result, interval = state.interval)

    // Samples

    /** Feeds one sample by hand. Returns what happened on it. */
    public fun process(sample: SensorSample): List<RunEvent> = when (sample) {
        is SensorSample.Motion -> process(sample.sample)
        is SensorSample.Gps -> process(sample.sample)
    }

    public fun process(sample: RawMotionSample): List<RunEvent> {
        if (!state.phase.isRecording) return emptyList()
        motion.add(sample)
        if (state.mode != RunMode.StandingStart) return processInterval(sample)
        val events = mutableListOf<RunEvent>()
        val o = options

        when (val phase = state.phase) {
            RunPhase.WaitingStill -> if (isStill(sample.t)) {
                state = state.copy(phase = RunPhase.Armed)
                events.add(RunEvent.Armed)
            }
            RunPhase.Armed -> {
                // A launch is movement that starts strong and holds. Movement that does not
                // become a launch in time disarms; a bump that goes away does not.
                val horizontal = AxisCalibration.horizontalMagnitude(sample)
                if (horizontal >= o.stillAccel) {
                    val moved = movementStart ?: sample.t
                    movementStart = moved
                    if (horizontal > o.launchAccel && sample.t - moved >= o.launchHold && sustained(sample.t - o.launchHold)) {
                        state = state.copy(phase = RunPhase.Running(moved), startLocation = state.location)
                        movementStart = null
                        events.add(RunEvent.Launched(moved, state.location))
                    } else if (sample.t - moved > o.launchHold + o.launchGrace) {
                        disarm(events)
                    }
                } else {
                    movementStart = null
                }
            }
            is RunPhase.Running -> {
                val elapsed = sample.t - phase.start
                state = state.copy(elapsed = elapsed)
                val byDistance = state.distance > o.finishDistance
                val backedOff = peakSpeed > 11 && state.speed < peakSpeed * o.backOffRatio
                if (byDistance || backedOff || elapsed > o.maxDuration) {
                    events.forEach(::emit)
                    finish()
                    return events + (state.result?.let { RunEvent.Finished(it) } ?: RunEvent.Failed(RunFailure.noLaunch))
                }
            }
            else -> Unit
        }
        // While waiting, only the last minute of samples matters
        if (state.phase == RunPhase.WaitingStill && motion.size > 6000) motion.subList(0, 1000).clear()
        events.forEach(::emit)
        return events
    }

    /** Rolling and braking: record from the first sample, finish once the range is covered. */
    private fun processInterval(sample: RawMotionSample): List<RunEvent> {
        if (state.phase == RunPhase.Armed) state = state.copy(phase = RunPhase.Running(sample.t))
        val phase = state.phase as? RunPhase.Running ?: return emptyList()
        state = state.copy(elapsed = sample.t - phase.start)
        val kmh = state.speedKmh
        val done = when (val mode = state.mode) {
            is RunMode.Rolling -> kmh >= mode.toKmh + 3
            is RunMode.Braking -> peakSpeed * 3.6 >= mode.fromKmh && kmh < 1.5
            RunMode.StandingStart -> false
        }
        if (done || state.elapsed > maxOf(options.maxDuration, 60.0)) {
            finish()
            return listOf(state.interval?.let { RunEvent.IntervalFinished(it) } ?: RunEvent.Failed(RunFailure.noInterval))
        }
        return emptyList()
    }

    public fun process(sample: GPSSample): List<RunEvent> {
        trackRate(sample.t)
        var next = state
        sample.horizontalAccuracy?.let { next = next.copy(gpsAccuracy = it) }
        sample.coordinate?.let { next = next.copy(location = it) }
        state = next
        // A fix without speed still moves the location, but nothing else
        if (!sample.hasSpeed) return emptyList()
        state = state.copy(speed = sample.speed)
        if (!state.phase.isRecording) return emptyList()

        if (state.mode != RunMode.StandingStart) {
            peakSpeed = maxOf(peakSpeed, sample.speed)
            gps.add(sample)
            return emptyList()
        }
        val events = mutableListOf<RunEvent>()
        // Rolling before the launch: wait for a standstill again
        if (state.phase == RunPhase.Armed && sample.speed >= options.stillSpeed) disarm(events)
        val phase = state.phase
        val last = gps.lastOrNull()
        if (phase is RunPhase.Running && last != null) {
            val distance = state.distance + (sample.speed + last.speed) / 2 * (sample.t - maxOf(last.t, phase.start))
            state = state.copy(distance = distance)
            peakSpeed = maxOf(peakSpeed, sample.speed)
            val elapsed = sample.t - phase.start
            listOf(
                DragMark.sixtyFeet to (distance >= DragDistance.SIXTY_FEET),
                DragMark.zeroTo100 to (sample.speed >= DragDistance.SPEED_100),
                DragMark.eighthMile to (distance >= DragDistance.EIGHTH_MILE),
                DragMark.quarterMile to (distance >= DragDistance.QUARTER_MILE),
            ).forEach { (mark, reached) ->
                if (reached && passed.add(mark)) events.add(RunEvent.Passed(mark, elapsed))
            }
        }
        gps.add(sample)
        events.forEach(::emit)
        return events
    }

    /** Fix times of the last few seconds, for [RunSnapshot.gpsRate]. */
    private val fixTimes = ArrayDeque<Double>()

    private fun trackRate(t: Double) {
        fixTimes.addLast(t)
        while (fixTimes.first() < t - 5) fixTimes.removeFirst()
        val first = fixTimes.first()
        if (fixTimes.size >= 2 && t > first) state = state.copy(gpsRate = (fixTimes.size - 1) / (t - first))
    }

    private fun emit(event: RunEvent) {
        _events.tryEmit(event)
    }

    // Detection

    /** [RunSessionOptions.stillWindow] seconds without noticeable acceleration and the GPS near zero. */
    private fun isStill(t: Double): Boolean {
        var count = 0
        for (i in motion.indices.reversed()) {
            val s = motion[i]
            if (s.t <= t - options.stillWindow) break
            if (AxisCalibration.horizontalMagnitude(s) >= options.stillAccel) return false
            count++
        }
        return count >= (options.stillWindow * 80).toInt() && (gps.lastOrNull()?.speed ?: 0.0) < options.stillSpeed
    }

    private fun sustained(from: Double): Boolean {
        var count = 0
        for (i in motion.indices.reversed()) {
            val s = motion[i]
            if (s.t < from) break
            if (AxisCalibration.horizontalMagnitude(s) <= options.launchHoldAccel) return false
            count++
        }
        return count >= 10
    }

    private fun disarm(events: MutableList<RunEvent>) {
        state = state.copy(phase = RunPhase.WaitingStill)
        movementStart = null
        events.add(RunEvent.Disarmed)
    }

    private fun clear() {
        motion.clear()
        gps.clear()
        peakSpeed = 0.0
        passed.clear()
        recordedAt = null
        movementStart = null
        state = RunSnapshot(speed = state.speed, gpsAccuracy = state.gpsAccuracy, location = state.location, gpsRate = state.gpsRate)
    }
}
