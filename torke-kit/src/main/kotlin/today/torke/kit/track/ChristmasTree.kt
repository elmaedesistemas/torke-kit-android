package today.torke.kit.track

import kotlin.math.abs
import kotlin.random.Random
import kotlinx.serialization.Serializable
import today.torke.kit.engine.DragDistance
import today.torke.kit.engine.RunAnalyzer
import today.torke.kit.engine.RunResult

/**
 * How the tree counts down: Pro lights the three ambers at once and green 0.4 s later;
 * Sportsman lights them one by one, 0.5 s apart, and green 0.5 s after the last.
 */
@Serializable
public enum class TreeType {
    pro, sportsman;

    /** Seconds from one amber to the next (0: all at once). */
    public val amberGap: Double get() = if (this == pro) 0.0 else 0.5
    /** Seconds from the last amber to green. */
    public val greenDelay: Double get() = if (this == pro) 0.4 else 0.5
}

/** One light of the tree, at a time on the samples' clock. */
@Serializable
public data class TreeLight(val kind: Kind, val time: Double) {
    @Serializable
    public enum class Kind { amber1, amber2, amber3, green }
}

/**
 * The driver's reaction: from green to the car leaving the stage beam (after the 1-foot
 * rollout), like a dragstrip. Negative is a red light.
 */
@Serializable
public data class Reaction(val time: Double) {
    val isRedLight: Boolean get() = time < 0
    /** Within half a millisecond of green: a "perfect light". */
    val isPerfect: Boolean get() = abs(time) < 0.0005
}

/**
 * A Christmas tree on the phone's screen. Its lights are times on the same monotonic clock as
 * the sensor samples (`elapsedRealtime` on Android), so the app draws them on schedule and
 * TorkeKit measures the reaction from the run.
 *
 * ```kotlin
 * // When the car is armed (staged):
 * val tree = ChristmasTree.start(TreeType.pro, stagedAt = SystemClock.elapsedRealtimeNanos() / 1e9)
 * // Draw tree.lights on schedule; after the run:
 * val reaction = result.reaction(tree)      // .time, .isRedLight
 * ```
 */
@Serializable
public data class ChristmasTree(val type: TreeType, val activatedAt: Double) {
    /** Every light in order. */
    val lights: List<TreeLight>
        get() {
            val g = type.amberGap
            return listOf(
                TreeLight(TreeLight.Kind.amber1, activatedAt),
                TreeLight(TreeLight.Kind.amber2, activatedAt + g),
                TreeLight(TreeLight.Kind.amber3, activatedAt + 2 * g),
                TreeLight(TreeLight.Kind.green, greenAt),
            )
        }

    val greenAt: Double get() = activatedAt + 2 * type.amberGap + type.greenDelay

    /** The lights that are on at [time] (ambers stay on until green). */
    public fun lit(time: Double): List<TreeLight.Kind> {
        if (time >= greenAt) return listOf(TreeLight.Kind.green)
        return lights.filter { it.kind != TreeLight.Kind.green && it.time <= time }.map { it.kind }
    }

    /** Reaction from the moment the car left the stage beam. */
    public fun reaction(leftStageAt: Double): Reaction = Reaction(leftStageAt - greenAt)

    /**
     * A live estimate from [today.torke.kit.session.RunEvent.Launched], before the run is
     * analyzed: adds a typical rollout time. The exact reaction comes from [RunResult.reaction].
     */
    public fun estimatedReaction(launchedAt: Double, rolloutTime: Double = 0.15): Reaction = reaction(launchedAt + rolloutTime)

    public companion object {
        /**
         * A tree that activates after a random delay from the moment the car is staged, like a
         * track's auto-start. Give a [seed] for a repeatable delay (tests, replays).
         */
        public fun start(type: TreeType, stagedAt: Double, delayMin: Double = 0.6, delayMax: Double = 1.1, seed: Long? = null): ChristmasTree {
            val unit = if (seed != null) {
                val state = seed.toULong() * 6364136223846793005UL + 1442695040888963407UL
                (state shr 11).toDouble() / (1L shl 53).toDouble()
            } else Random.nextDouble()
            return ChristmasTree(type, stagedAt + delayMin + (delayMax - delayMin) * unit)
        }
    }
}

/** When the car left the stage beam: the launch plus the time to cover the 1-foot rollout. */
public val RunResult.leftStageAt: Double?
    get() = RunAnalyzer.timeAtDistance(DragDistance.ROLLOUT, trace)?.let { launchTime + it }

/** The reaction on [tree]. Null without the run's trace. */
public fun RunResult.reaction(tree: ChristmasTree): Reaction? = leftStageAt?.let(tree::reaction)

/** Reaction plus elapsed time to the quarter mile: the "package" of bracket racing. */
public fun RunResult.packageTime(tree: ChristmasTree): Double? {
    val r = reaction(tree) ?: return null
    val et = quarterMile ?: return null
    return r.time + et
}
