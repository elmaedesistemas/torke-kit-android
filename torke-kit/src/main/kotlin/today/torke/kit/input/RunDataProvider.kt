package today.torke.kit.input

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import today.torke.kit.engine.DemoRun
import today.torke.kit.engine.GPSSample
import today.torke.kit.engine.RawMotionSample
import today.torke.kit.format.TorkeRun

/** One reading from a sensor source. */
public sealed interface SensorSample {
    public val t: Double

    public data class Motion(val sample: RawMotionSample) : SensorSample {
        override val t: Double get() = sample.t
    }

    public data class Gps(val sample: GPSSample) : SensorSample {
        override val t: Double get() = sample.t
    }
}

/**
 * Anything that produces motion and GPS samples: the phone's sensors, an external GPS, a
 * recorded run. Motion and GPS timestamps must share one monotonic clock.
 */
public fun interface RunDataProvider {
    /** The samples, in time order. The flow completes when the source stops; cancelling its collection turns the source off. */
    public fun samples(): Flow<SensorSample>
}

/** Plays back a recorded run, for tests, demos and re-analysis. [speed] 1 plays in real time; 0 (the default) as fast as possible. */
public class RecordedRunProvider(public val run: TorkeRun, public val speed: Double = 0.0) : RunDataProvider {
    override fun samples(): Flow<SensorSample> = flow {
        var previous: Double? = null
        for (sample in merge(run)) {
            val p = previous
            if (speed > 0 && p != null && sample.t > p) delay(((sample.t - p) / speed * 1000).toLong())
            previous = sample.t
            emit(sample)
        }
    }

    public companion object {
        /** The simulated street car of [DemoRun]. */
        public fun demo(seed: Long = 42, speed: Double = 0.0): RecordedRunProvider {
            val (motion, gps) = DemoRun.generate(seed)
            return RecordedRunProvider(TorkeRun(motion = motion, gps = gps), speed)
        }

        /** Motion and GPS in one time-ordered list; at equal times, motion first. */
        public fun merge(run: TorkeRun): List<SensorSample> {
            val out = ArrayList<SensorSample>(run.motion.size + run.gps.size)
            var i = 0
            var j = 0
            while (i < run.motion.size || j < run.gps.size) {
                if (j >= run.gps.size || (i < run.motion.size && run.motion[i].t <= run.gps[j].t)) out.add(SensorSample.Motion(run.motion[i++]))
                else out.add(SensorSample.Gps(run.gps[j++]))
            }
            return out
        }
    }
}
