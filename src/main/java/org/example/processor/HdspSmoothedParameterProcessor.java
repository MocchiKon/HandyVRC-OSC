package org.example.processor;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.diagnostics.DeviceProfile;
import org.example.diagnostics.DiagnosticTrace;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * {@link HdspParameterProcessor} with motion extrapolation (the {@code HDSP_SMOOTHED} algorithm).
 * <p>
 * The plain HDSP processor forwards every point 1:1: the time difference of two consecutive points decides how
 * long the device should take to move between them. That follows the input closely, but it also means that an
 * input that decelerates or reverses leaves the device running at the speed of the previous, faster points (the
 * slider overshoots the next point and bounces back), and that the slider only stops where a point happened to
 * leave it when the points stop coming.
 * <p>
 * This processor decouples the device from the individual points. The values are only used to measure how fast
 * and in which direction the user moves, while the device is told to move towards the top (xp=1) or the bottom
 * (xp=0) of the stroke:
 * <ul>
 *   <li>Every move is planned as a time: the input needs {@code distance / speed} to reach the end of the stroke,
 *   and the slider gets the same time for the distance it has left. It is therefore never told to move further
 *   than the input already moved, so it cannot run past the movement of the user and the target of a move is
 *   always an endpoint of the stroke.</li>
 *   <li>A move is corrected only when it is needed: when the input reverses, when the measured input speed
 *   differs from the speed the slider is moving with by more than {@code hdspSpeedChangeThresholdPercent}, or
 *   when the slider fell behind the input by more than {@code hdspDivergenceThresholdPercent}. A constant input
 *   needs no further commands until the slider arrives at the endpoint.</li>
 *   <li>Speed is measured over the raw OSC values, not over the points that pass {@code minimalValueChange}:
 *   values that are too small to become a point still show that the user is moving - and, more importantly, when
 *   the user stopped moving. That is what allows the slider to be stopped quickly instead of waiting for the
 *   point stream to starve.</li>
 *   <li>The newest values are followed as well while they move faster than the fit of the speed window (a
 *   movement that just started, or a reversal). Without that, a fast stroke is followed only after the window has
 *   filled, and the slider starts every stroke late (which at 150 strokes per minute costs a large part of the
 *   stroke). A movement that slows down is still left to the steadier fit.</li>
 *   <li>VRChat only sends a value once it changed, so a slow movement arrives as a few values per second at
 *   irregular intervals. Every measurement uses at least a few values (a window that holds a single value would
 *   look like a stop), the speed is the least squares fit of those values (two values that arrived close together
 *   would look like a sprint), and only a gap that is clearly longer than the recent gaps counts as a pause.</li>
 *   <li>The first value of a movement, and the first value after the input stopped, are only used as the baseline
 *   of the measurement of the values that follow: a single value has no timing, so no move is started from it. The
 *   slider follows again once the input moved {@link #START_MIN_DISTANCE} away from where it rested, so the jitter
 *   of a resting hand cannot start it.</li>
 *   <li>When the input stops, the slider is stopped (a stop is a move with stopOnTarget that takes at least
 *   {@link #MIN_HOLD_DURATION_MS}, so the device does not finish the move it was on). A slider that is behind the
 *   input is stopped at the input; a slider that got past it is stopped where it is and never sent back, which
 *   would be a visible jump. The commands reach the device with a varying delay, so a stop is placed a little
 *   further along the way the slider moves, and the long duration turns what is left of that error into a slow
 *   correction instead of a jump. When the input moves again, the slider continues from there.</li>
 *   <li>A movement against the current direction only counts as a reversal once the input moved back by
 *   {@code hdspReversalMinDistancePercent}; until then the slider is stopped, so the jitter of a slow movement cannot send it
 *   the other way.</li>
 *   <li>A Handy 1 does not move slower than about 30 mm/s ({@code hdspMinSliderSpeed}), whatever time it is given
 *   for a move: told to crawl, it runs ahead of where it is believed to be, and the next stop pulls it back. An input
 *   slower than that is therefore followed in steps: the slider waits until the input is a step ahead and then moves
 *   to it at the minimum speed, stopping there by itself (stopOnTarget). A Handy 2 Pro with overclocking can have a
 *   lower minimum, which is why the value is configurable (0 disables the pulses).</li>
 *   <li>The fastest speed the device can move with ({@code hdspMaxSliderSpeed}, 0 for no limit) bounds every planned
 *   move as well: the app then does not plan a speed the device cannot execute, so the position it believes the
 *   slider is at stays realistic. This matters for a Handy 1 (about 350-420 mm/s) as much as for a faster device.</li>
 *   <li>Stops and reversals are sent as soon as they are noticed; {@code sendMessageEveryMs} only spaces out the
 *   speed corrections. The slider position is read from the device at startup, and a slider that is far from the
 *   input (further than {@code hdspDivergenceThresholdPercent}) is brought to it - at a moderate speed while the
 *   input rests, at the speed of the input while it moves (a fixed moderate speed can never catch up with a fast
 *   movement and leaves the slider behind for the whole stroke).</li>
 * </ul>
 * Because a command is always issued for the position the slider has reached by then, a corrected speed is picked
 * up by the device without a jump, and because the next command is queued while the current one is still running,
 * the slider never waits for the input.
 */
@Slf4j
public class HdspSmoothedParameterProcessor extends AbstractStreamingParameterProcessor
{
    /** Minimum interval between two commands; HDSP commands are fire and forget over BLE. */
    public static final int HDSP_MIN_SEND_INTERVAL_MS = 20;
    /**
     * Default for {@code hdspSpeedChangeThresholdPercent}: how much the measured input speed has to differ from
     * the speed the slider is moving with before the move is corrected.
     */
    public static final int DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT = 15;
    /**
     * Default for {@code hdspSpeedStopThresholdPercent}: input speed below this percentage of the fastest speed
     * of the current movement counts as "the user stopped", which stops the slider where it is.
     */
    public static final int DEFAULT_SPEED_STOP_THRESHOLD_PERCENT = 15;
    /**
     * Default for {@code hdspDivergenceThresholdPercent}: how far the slider may be behind the input (in percent
     * of the stroke) before the movement is corrected even though the measured speed did not change enough to
     * cross {@code hdspSpeedChangeThresholdPercent}.
     */
    public static final int DEFAULT_DIVERGENCE_THRESHOLD_PERCENT = 20;
    /**
     * Default for {@code hdspSpeedMeasureWindowMs}: time span of the raw values used to measure the input speed.
     * A longer window is a steadier (but slower to react) speed. 100 ms still holds several values of a 45 values
     * per second stream (the rate VRChat sends), and leaves a fast stroke enough of its short half period to be
     * measured instead of averaged away.
     */
    public static final int DEFAULT_SPEED_MEASURE_WINDOW_MS = 100;
    /**
     * Default for {@code hdspMinSliderSpeed}: the slowest speed (millimeters per second) the device moves with.
     * Measured on a Handy: asked for anything slower, it moved at about 30 mm/s anyway (so it ran ahead of where
     * it was believed to be, and the next stop pulled it back). An input slower than this is followed in steps.
     */
    public static final int DEFAULT_MIN_SLIDER_SPEED_MM_PER_SECOND = 30;
    /**
     * Default for {@code hdspReversalMinDistancePercent}: distance (in percent of the stroke) the input has to
     * move back from the furthest position of its movement before it counts as reversed. The jitter of a tracked
     * hand is easily larger than what a slow movement covers within the movement window, and without this every
     * such wobble would send the slider the other way.
     */
    public static final float DEFAULT_REVERSAL_MIN_DISTANCE_PERCENT = 2f;
    /** Stroke length assumed when the device does not report it (the full stroke of a Handy). */
    static final double DEFAULT_STROKE_MM = 100;
    /**
     * Window of the speed measurement of a slow movement. Within the normal speed window a slow movement covers
     * less distance than a tracked hand jitters, so its measured speed (and direction) would be mostly noise: the
     * movement is measured with a least squares fit over this longer window instead.
     */
    static final int SLOW_SPEED_MEASURE_WINDOW_MS = 500;
    /**
     * Speed (stroke units per second) below which a movement is measured over {@link #SLOW_SPEED_MEASURE_WINDOW_MS}.
     * Above it the movement within the normal speed window is clearly larger than the jitter.
     */
    static final double SLOW_MOVEMENT_SPEED = 0.25;
    /**
     * The values of a slow movement count as stalled when they moved less than this part of what the measured
     * speed predicts within the speed window.
     */
    private static final double SLOW_STALL_MOVEMENT_RATIO = 0.25;
    /** Part of the stop threshold a slow movement that is followed already may drop to (see followSpeedThreshold). */
    private static final double FOLLOWED_SPEED_THRESHOLD_RATIO = 0.5;
    /** Lower bound for the configured speed measurement window. */
    static final int MIN_SPEED_MEASURE_WINDOW_MS = 50;
    /**
     * Fewest values the speed measurement uses. VRChat only sends values that changed, so a slow movement arrives
     * as a few values per second: a window shorter than that would hold one value (no speed at all, which is a
     * stop) or two values that happened to arrive close together (a speed spike).
     */
    static final int MIN_SPEED_SAMPLES = 4;
    /** Fewest values the movement and direction detection use (see {@link #MIN_SPEED_SAMPLES}). */
    static final int MIN_MOVEMENT_SAMPLES = 3;
    /** Fewest values the fit of a slow movement uses (see {@link #MIN_SPEED_SAMPLES}). */
    static final int MIN_SLOW_FIT_SAMPLES = 5;
    /** How long received values are kept for the measurements. */
    private static final int SAMPLE_RETENTION_MS = 1_500;
    /** Number of the newest input intervals the typical interval between two values is averaged over. */
    private static final int TYPICAL_INTERVAL_SAMPLES = 6;
    /**
     * How many typical input intervals without a new value count as a pause. Below that a missing value is just
     * a value that has not been sent yet (VRChat sends a value only once it changed), not a stop.
     */
    static final double PAUSE_INPUT_INTERVALS = 2.5;
    /**
     * How many times the longest of the newest input intervals counts as a pause as well. The values of a slow
     * movement arrive irregularly (a jittering hand adds values in quick succession), which shortens the average
     * interval while the gaps of the movement stay as long as they were.
     */
    static final double PAUSE_LONGEST_INTERVALS = 1.5;
    /** Bounds of the time without a new value that counts as a pause (see {@link #PAUSE_INPUT_INTERVALS}). */
    static final int MIN_PAUSE_MS = 75;
    static final int MAX_PAUSE_MS = 400;
    /**
     * Input speed (percent per second) below which the input always counts as stopped, whatever the speed of the
     * movement is. It keeps the quantization of a (nearly) still avatar parameter from driving the slider, at the
     * price of not following movements slower than this. It is also the speed that is used to stop the slider at
     * the position the input stopped at.
     */
    static final double MIN_INPUT_SPEED_PERCENT_PER_SECOND = 4;
    /**
     * Sanity cap for a measured input speed: a full stroke in less than a twentieth of a second is a teleport, not
     * a movement. It is deliberately far above what a Handy can execute (a Handy 1 reaches about 420 mm/s), so that
     * faster devices are not limited by the app; the device's own maximum is configured with
     * {@code hdspMaxSliderSpeed} and limits the plans instead.
     */
    static final double MAX_INPUT_SPEED = 20.0;
    /**
     * Time span in which the received values have to show movement for the input to count as moving. A movement of
     * the avatar parameter is quantized, so a user who stopped moving produces values that stay within one
     * quantization step: looking at the newest values notices that long before the average speed of the much
     * longer speed window has decayed, which is what makes the slider stop quickly.
     */
    static final int INPUT_MOVEMENT_WINDOW_MS = 100;
    /** Movement (in percent of the stroke) the values have to show within the movement window to count as moving. */
    static final double MIN_INPUT_MOVEMENT_PERCENT = 0.3;
    /**
     * Shortest move that is planned. Only a move that is about to reach an endpoint (or the position to hold) is
     * shorter, which is what makes those moves decelerate instead of overshooting. A full-stroke move therefore
     * cannot be planned faster than 833%/s; a device that can move faster than that (for example an overclocked
     * Handy 2 Pro) is limited by this floor, not by {@link #MAX_INPUT_SPEED}.
     */
    static final int MIN_MOVE_DURATION_MS = 120;
    /**
     * Upper bound for a single move. It must not be shorter than the time the slowest followed movement
     * ({@link #MIN_INPUT_SPEED_PERCENT_PER_SECOND}) needs for a full stroke: a move that is cut short covers its
     * distance faster than the input does, which runs the slider ahead of the user (and back once they stop).
     */
    public static final int HDSP_MAX_MOVE_DURATION_MS = 30_000;
    /** Input speed (stroke units per second) above which the command delay is compensated (see motionStartMs). */
    private static final double LATENCY_COMPENSATION_MIN_SPEED = 0.5;
    /** Catch up speed as a multiple of the input speed, so a slider that fell behind never sprints. */
    private static final double MAX_CATCH_UP_FACTOR = 1.5;
    /** Slowest speed (as a multiple of the input speed) a slider that is ahead of the input is slowed down to. */
    private static final double MIN_SLOW_DOWN_FACTOR = 0.25;
    /**
     * Time the slider is given to make up the distance between it and the input, so a shorter time catches up
     * (or lets the input catch up) faster and a longer one turns the correction into a gentler approach.
     */
    private static final int LOOK_AHEAD_MS = 300;
    /**
     * Time a slider that is ahead of the input is given to let the input catch up. Shorter than
     * {@link #LOOK_AHEAD_MS}: the measured speed lags behind a deceleration, and a lead that is corrected slowly
     * persists through the whole deceleration and ends as an overshoot when the input stops.
     */
    private static final int AHEAD_CORRECTION_MS = 150;
    /**
     * Distance (in stroke units) the input has to move away from where it rested before the slider follows it
     * again. While a hand rests, its jitter flips the (quantized) value back and forth by a step, which looks like
     * short movements in both directions; without this every flip would start the slider for a moment.
     */
    static final double START_MIN_DISTANCE = 0.02;
    /**
     * Shortest and longest time the slider is given to arrive at the position it is held at. The shortest time is
     * what turns an error of the believed slider position (the commands do not all reach the device with the same
     * delay) into a gentle correction instead of a jump.
     */
    static final int MIN_HOLD_DURATION_MS = 200;
    /** Only a bound for a stop planned with an (almost) zero speed: a slow approach or return keeps its speed. */
    private static final int MAX_HOLD_DURATION_MS = 3_000;
    /**
     * How much later than the moves before it a stop may reach the device without pulling the slider back. A stop
     * is planned for where the slider is believed to be, which the slider has passed already when the stop takes
     * longer to arrive, so the stop is placed this much (at the speed of the slider) further along.
     */
    private static final int HOLD_LATENCY_MARGIN_MS = 25;
    /** Speed (stroke units per second) a slider that got far past the input is taken back with. */
    private static final double MAX_RETURN_SPEED = 0.1;
    /**
     * How far (stroke units) the slider may get ahead of an input that is slower than the device can move before
     * it pauses. An input like that is followed in pulses at the minimum speed of the device (it cannot move any
     * slower): the slider moves until it is this far ahead, and pauses until the input is {@link #PULSE_LAG} ahead
     * of it. The wider the band between the two, the longer (and so the fewer) the pulses and pauses are, which is
     * what makes a slow movement feel smooth; this bound is also how far the slider can be past a stop.
     */
    static final double PULSE_LEAD = 0.03;
    /** How far (stroke units) the input gets ahead of a pausing slider before the next pulse (see PULSE_LEAD). */
    static final double PULSE_LAG = 0.07;
    /** Longest pulse: an input that is about as fast as the minimum speed is followed continuously meanwhile. */
    static final int MAX_PULSE_MS = 3_000;
    /** Distance (stroke units) by which the end of a pulse may drift (the input changed speed) before it is re-planned. */
    private static final double PULSE_RETARGET_DISTANCE = 0.02;
    /**
     * Input speed (as a multiple of the minimum speed) above which the pulses give way to a continuous movement
     * again. Above the minimum speed, so that an input around it does not switch between the two all the time.
     */
    private static final double PULSE_EXIT_SPEED_RATIO = 1.2;
    /**
     * Speed (stroke units per second) a slider that is far from the input (further than
     * {@code hdspDivergenceThresholdPercent}, for example because the device started somewhere else) is brought to
     * it with while the input does not move. Moderate, as this is a move the user did not make; while the input
     * moves, a resync has to keep up with it (see {@link #resyncPlan}).
     */
    static final double RESYNC_SPEED = 0.5;
    /** Input movement (stroke units) after which a slider that is being brought to the input is re-targeted. */
    private static final double RESYNC_RETARGET_DISTANCE = 0.02;
    /** Default for {@code hdspDevicePositionLogIntervalMs}: how often the device position is requested. */
    public static final int DEFAULT_DEVICE_POSITION_LOG_INTERVAL_MS = 200;
    /** Bounds of {@code hdspDevicePositionLogIntervalMs}; each request is a Bluetooth round trip. */
    public static final int MIN_DEVICE_POSITION_LOG_INTERVAL_MS = 20;
    public static final int MAX_DEVICE_POSITION_LOG_INTERVAL_MS = 2_000;
    /**
     * Input speed (stroke units per second) from which a stop is detected from the newest value alone. One input
     * interval of such a movement is clearly larger than the jitter of a tracked hand, so a value that (nearly)
     * did not move is a stop, not noise. Slower movements are stopped by the measurement windows, which is slower
     * to notice but cannot overshoot much at those speeds.
     */
    static final double FAST_STOP_MIN_SPEED = 0.4;
    /** The newest value counts as a stop when it moved less than this fraction of what the value before it moved. */
    private static final double FAST_STOP_SPEED_RATIO = 0.25;
    /**
     * Speed (stroke units per second) the newest pair has to stay below for a stop: a movement that only slowed
     * down while it approaches its endpoint still moves, and holding the slider there would cut the stroke short.
     */
    private static final double FAST_STOP_MAX_SPEED = 0.5;
    /**
     * Input intervals without a new value after which a fast movement counts as stopped. The value of a fast
     * movement changes every frame, so VRChat sends one every frame and a missing one is a stop, while a slow
     * movement only produces a value now and then (see {@link #PAUSE_INPUT_INTERVALS}).
     */
    private static final double FAST_STOP_OVERDUE_INTERVALS = 1.5;
    /** Input interval assumed until the interval between the values is measured. */
    private static final int DEFAULT_INPUT_INTERVAL_MS = 50;
    /** Input speed is considered stopped after it was below the stop threshold for this long. */
    private static final int STOP_CONFIRM_MS = 50;
    /**
     * Time constant of the decay of the reference speed the stop threshold is relative to. A movement that became
     * much slower must not keep being measured against the speed of an earlier, faster movement forever.
     */
    private static final int PEAK_SPEED_DECAY_MS = 500;
    /** Speed (stroke units per second) above which the slider counts as moving, so that it can be stopped. */
    private static final double MIN_SLIDER_SPEED = 0.01;
    /** A move shorter than this (in stroke units) is not visible, so the slider is treated as being there already. */
    private static final double MIN_MOVE_DISTANCE = 0.01;
    /**
     * Speed difference below which a diverged slider is not corrected again: the move underway already is the
     * correction (its speed is bounded, so the slider needs some time to get back to the input).
     */
    private static final double MIN_DIVERGENCE_SPEED_DRIFT = 0.05;
    /** Speed difference from which a slider that is ahead of the input is slowed down (see {@link #moveChanged}). */
    private static final double AHEAD_SPEED_DRIFT = 0.1;
    /** Fraction of the move underway after which the following move is queued. */
    private static final double MOVE_RENEWAL_RATIO = 0.8;
    /**
     * Longest time a move may run before it is re-issued with the measured input. Without it, a move that was
     * planned while the input was fast would keep running while the input slows down, and the slider would arrive
     * at the endpoint long after the input did (which is the overshoot this algorithm exists to avoid).
     */
    private static final int MOVE_RENEWAL_INTERVAL_MS = 300;
    /** How often the measured input update rate is summarized in the debug log. */
    private static final int INPUT_RATE_LOG_INTERVAL_MS = 5_000;

    private final int speedChangeThresholdPercent;
    private final int speedStopThresholdPercent;
    private final int divergenceThresholdPercent;
    private final int speedMeasureWindowMs;
    /** See {@link #DEFAULT_REVERSAL_MIN_DISTANCE_PERCENT}, in stroke units. */
    private final double reversalMinDistance;
    /** Slowest speed (millimeters per second) the device moves with, 0 when there is no minimum. */
    private final double minSliderSpeedMmPerSecond;
    /** Fastest speed (millimeters per second) the device can move, 0 when there is no maximum. */
    private final double maxSliderSpeedMmPerSecond;
    /** The calibration profile the limits and the motion start came from, null when none is used. */
    private final DeviceProfile deviceProfile;
    /** Input speed below which the input counts as stopped (percent per second, see stopSpeedThreshold). */
    private final double minInputSpeedPercentPerSecond;
    /** Slowest speed (stroke units per second) the slider is moved with, 0 when there is no minimum. */
    private double minSliderSpeed;
    /** Fastest speed (stroke units per second) the slider is moved with, 0 when there is no maximum. */
    private double maxSliderSpeed;
    /**
     * Time between sending a command and the slider actually starting to move, measured by the device calibration.
     * The planner leads the input by this much so that the movement arrives at the user's position in time instead
     * of trailing it. 0 disables the compensation.
     */
    private double motionStartMs;
    /** Thread of the device position log, null when it is not enabled. */
    private Thread devicePositionLogThread;
    /** How often the device position is read while the log is enabled (each read is a BLE round trip). */
    private final int devicePositionLogIntervalMs;
    /** Structured JSONL trace of this session, null when {@code hdspDiagnosticTrace} is disabled. */
    private volatile DiagnosticTrace trace;

    /** Raw input values, used for the speed measurement only. */
    private final List<InputSample> samples = new ArrayList<>();
    private InputSample lastSample;
    /** True when the input moves towards the top of the stroke (the endpoint the slider has to head for). */
    private boolean inputTowardsTop = true;
    /** Furthest position the input reached in {@link #inputTowardsTop} direction (the reversal reference). */
    private double turnExtreme = Double.NaN;
    /** True while the newest values move against {@link #inputTowardsTop} but not far enough to be a reversal. */
    private boolean reversalPending;
    /**
     * True from a stop that was detected from the newest value until a value shows movement again. The averaged
     * measurements still show the movement from before the stop for a while, which must not restart the slider.
     */
    private boolean fastStopped;

    /** The move the device is executing, or null before the first command (read by the device position log too). */
    private volatile SliderMove move;
    /**
     * Position the slider is at before the first command: where the device reported it, or where the input started
     * when the device position could not be read. NaN until either is known.
     */
    private double initialSliderPosition = Double.NaN;
    /** True while an input that is slower than the device can move is followed in pulses (see PULSE_LEAD). */
    private boolean pulsing;
    /** Position the input rested at when the slider was stopped (or the first value), see START_MIN_DISTANCE. */
    private double restPosition = Double.NaN;
    /** True while the input is measured as a slow movement (see {@link #SLOW_SPEED_MEASURE_WINDOW_MS}). */
    private boolean slowMovement;
    /** True while the slider is held at the position the input stopped at. */
    private boolean holding;
    /** Position the slider is held at (only meaningful while {@link #holding} is true). */
    private double holdTarget = 0.5;
    /** App time at which the last command was sent (0 while nothing was sent yet). */
    private volatile long lastCommandSentMs;
    /** App time at which the input speed dropped below the stop threshold (0 while the input is moving). */
    private long belowStopSpeedSinceMs;
    /** Fastest input speed of the current movement, used as the reference for the stop threshold. */
    private double peakSpeed;
    /** App time the reference speed was last decayed at. */
    private long lastPeakDecayMs;
    /** App time at which the slider was stopped (0 while the slider is not stopped). */
    private long stoppedSinceMs;

    private long lastMeasuredInputRatePerSecond;
    private long lastInputRateLogMs = Long.MIN_VALUE;
    private long receivedSamples;

    // Tests only
    HdspSmoothedParameterProcessor(ConfigProperties config)
    {
        this(null, config);
    }

    // Tests only
    void setMotionStartMsForTest(double motionStartMs)
    {
        this.motionStartMs = motionStartMs;
    }

    public HdspSmoothedParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        this(handyClient, config, loadDeviceProfile(handyClient, config));
    }

    /** Package-private variant that takes the profile explicitly, used by the tests. */
    HdspSmoothedParameterProcessor(HandyClient handyClient, ConfigProperties config, DeviceProfile deviceProfile)
    {
        super(handyClient, config);
        this.deviceProfile = deviceProfile;
        this.speedChangeThresholdPercent = resolvePositive(config.hdspSpeedChangeThresholdPercent(),
                DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT);
        this.speedStopThresholdPercent = resolvePositive(config.hdspSpeedStopThresholdPercent(),
                DEFAULT_SPEED_STOP_THRESHOLD_PERCENT);
        this.divergenceThresholdPercent = resolvePositive(config.hdspDivergenceThresholdPercent(),
                DEFAULT_DIVERGENCE_THRESHOLD_PERCENT);
        this.speedMeasureWindowMs = Math.max(resolvePositive(config.hdspSpeedMeasureWindowMs(),
                DEFAULT_SPEED_MEASURE_WINDOW_MS), MIN_SPEED_MEASURE_WINDOW_MS);
        // The calibration measures the device itself; without a profile the two settings are used.
        double profileMinMm = deviceProfile == null ? -1 : deviceProfile.plannedMinMmPerSecond();
        double profileMaxMm = deviceProfile == null ? -1 : deviceProfile.plannedMaxMmPerSecond();
        // 0 is a valid value here: it disables the minimum
        double configuredMinMm = config.hdspMinSliderSpeed() != null && config.hdspMinSliderSpeed() >= 0
                ? config.hdspMinSliderSpeed()
                : DEFAULT_MIN_SLIDER_SPEED_MM_PER_SECOND;
        this.minSliderSpeedMmPerSecond = profileMinMm >= 0 ? profileMinMm : configuredMinMm;
        this.minSliderSpeed = minSliderSpeedMmPerSecond / DEFAULT_STROKE_MM;
        // 0 is a valid value here as well: no maximum limit
        double configuredMaxMm = config.hdspMaxSliderSpeed() != null && config.hdspMaxSliderSpeed() >= 0
                ? config.hdspMaxSliderSpeed()
                : 0;
        this.maxSliderSpeedMmPerSecond = profileMaxMm > 0 ? profileMaxMm : configuredMaxMm;
        this.maxSliderSpeed = maxSliderSpeedMmPerSecond > 0
                ? maxSliderSpeedMmPerSecond / DEFAULT_STROKE_MM : 0;
        this.motionStartMs = deviceProfile == null ? 0 : Math.clamp(deviceProfile.motionStartMs(), 0, 500);
        // A device that can move at 1 mm/s is also worth following at 1 mm/s. The default 4%/s only exists to reject
        // the jitter of a tracked hand; a calibrated slow device lowers it, but never below half a percent per second.
        this.minInputSpeedPercentPerSecond = deviceProfile == null ? MIN_INPUT_SPEED_PERCENT_PER_SECOND
                : Math.min(MIN_INPUT_SPEED_PERCENT_PER_SECOND, Math.max(0.5, minSliderSpeedMmPerSecond / 2.0));
        // 0 is a valid value here as well: every movement back counts as a reversal
        this.reversalMinDistance = (config.hdspReversalMinDistancePercent() != null && config.hdspReversalMinDistancePercent() >= 0
                ? config.hdspReversalMinDistancePercent()
                : DEFAULT_REVERSAL_MIN_DISTANCE_PERCENT) / 100.0;
        this.devicePositionLogIntervalMs = Math.clamp(
                config.hdspDevicePositionLogIntervalMs() != null
                        ? config.hdspDevicePositionLogIntervalMs()
                        : DEFAULT_DEVICE_POSITION_LOG_INTERVAL_MS,
                MIN_DEVICE_POSITION_LOG_INTERVAL_MS, MAX_DEVICE_POSITION_LOG_INTERVAL_MS);
        if (handyClient == null)
        {
            return; // Tests only
        }

        HandyBaseResponseWithError response = handyClient.changeMode(DeviceMode.HDSP);
        if (response.error() != null)
        {
            throw new IllegalStateException("Could not change Handy mode to HDSP (reason: %s)".formatted(response.error().message()));
        }
        startDiagnosticTrace(config);
        log.info("HDSP_SMOOTHED: speedChangeThreshold={}%, speedStopThreshold={}%, divergenceThreshold={}%, speedWindow={}ms,"
                        + " reversalMinDistance={}%", speedChangeThresholdPercent, speedStopThresholdPercent,
                divergenceThresholdPercent, speedMeasureWindowMs, percent(reversalMinDistance));
        log.debug("HDSP_SMOOTHED does not apply minimalValueChange ({}): raw OSC values are needed to notice that"
                + " the user stopped moving", config.minimalValueChange());

        if (config.sliderMin() != null || config.sliderMax() != null)
        {
            handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        }
        Optional<SliderSettingsResult> sliderSettings = handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
        // The minimum speed is a property of the device (mm/s), the moves are planned in units of the stroke zone
        Float strokeMm = sliderSettings.map(SliderSettingsResult::strokeMm).orElse(null);
        this.minSliderSpeed = minSliderSpeedMmPerSecond / (strokeMm != null ? strokeMm : DEFAULT_STROKE_MM);
        this.maxSliderSpeed = maxSliderSpeedMmPerSecond > 0
                ? maxSliderSpeedMmPerSecond / (strokeMm != null ? strokeMm : DEFAULT_STROKE_MM) : 0;
        if (deviceProfile != null)
        {
            log.info("HDSP_SMOOTHED: using the measured device profile from {} (device {}): motion starts after"
                            + " {}ms, max {} mm/s, minimum {} mm/s", deviceProfile.calibratedAt(),
                    deviceProfile.deviceId() == null || deviceProfile.deviceId().isBlank()
                            ? "unknown" : deviceProfile.deviceId(),
                    "%.0f".formatted(deviceProfile.motionStartMs()),
                    "%.0f".formatted(maxSliderSpeedMmPerSecond),
                    "%.0f".formatted(minSliderSpeedMmPerSecond));
        }
        log.info("HDSP_SMOOTHED: minSliderSpeed={} mm/s ({}%/s of the {} stroke)", minSliderSpeedMmPerSecond,
                percentPerSecond(minSliderSpeed), strokeMm != null ? "%.0f mm".formatted(strokeMm) : "assumed 100 mm");
        if (maxSliderSpeedMmPerSecond > 0)
        {
            log.info("HDSP_SMOOTHED: maxSliderSpeed={} mm/s ({}%/s of the {} stroke)", maxSliderSpeedMmPerSecond,
                    percentPerSecond(maxSliderSpeed),
                    strokeMm != null ? "%.0f mm".formatted(strokeMm) : "assumed 100 mm");
        }
        else
        {
            log.info("HDSP_SMOOTHED: maxSliderSpeed=no limit");
        }
        readInitialSliderPosition(sliderSettings.orElse(null));
        if (config.hdspLogDevicePosition())
        {
            startDevicePositionLog();
        }
    }

    /**
     * Reads where the slider is before the first command. Without it the slider is assumed to be where the input
     * is, and the first moves (and the first stop) are planned for a slider that may be anywhere else.
     */
    private void readInitialSliderPosition(SliderSettingsResult sliderSettings)
    {
        try
        {
            handyClient.getSliderState().ifPresent(state ->
            {
                // Taken from the absolute position when the stroke zone is known in mm, as the zone positions are
                if (sliderSettings != null && sliderSettings.strokeMm() != null)
                {
                    initialSliderPosition = Math.clamp(
                            (state.positionMm() - sliderSettings.minMm()) / sliderSettings.strokeMm(), 0.0, 1.0);
                }
                else
                {
                    initialSliderPosition = Math.clamp(state.position(), 0.0, 1.0);
                }
                log.info("HDSP_SMOOTHED: slider starts at {}%", percent(initialSliderPosition));
            });
        }
        catch (Exception e)
        {
            log.warn("Could not read the slider position, assuming it is where the input starts: {}", e.getMessage());
        }
    }

    /**
     * Starts the structured trace when {@code hdspDiagnosticTrace} is enabled. The trace is written next to the
     * normal log files, so a session can be analyzed without parsing the text log.
     */
    private void startDiagnosticTrace(ConfigProperties config)
    {
        if (!config.hdspDiagnosticTrace())
        {
            return;
        }
        Map<String, Object> session = new LinkedHashMap<>();
        session.put("algorithm", "HDSP_SMOOTHED");
        session.put("connection", String.valueOf(config.connection()));
        session.put("spsType", String.valueOf(config.spsType()));
        session.put("sliderMin", config.sliderMin());
        session.put("sliderMax", config.sliderMax());
        session.put("fullyPenetratedAtValue", config.fullyPenetratedAtValue());
        session.put("reverseMovement", config.reverseMovement());
        session.put("minimalValueChange", config.minimalValueChange());
        session.put("speedChangeThresholdPercent", speedChangeThresholdPercent);
        session.put("speedStopThresholdPercent", speedStopThresholdPercent);
        session.put("divergenceThresholdPercent", divergenceThresholdPercent);
        session.put("speedMeasureWindowMs", speedMeasureWindowMs);
        session.put("minSliderSpeedMmPerSecond", minSliderSpeedMmPerSecond);
        session.put("maxSliderSpeedMmPerSecond", maxSliderSpeedMmPerSecond);
        session.put("useDeviceProfile", deviceProfile != null);
        session.put("deviceProfileCalibratedAt", deviceProfile == null ? null : deviceProfile.calibratedAt());
        session.put("deviceProfileMotionStartMs", deviceProfile == null ? null : deviceProfile.motionStartMs());
        session.put("minInputSpeedPercentPerSecond", minInputSpeedPercentPerSecond);
        session.put("reversalMinDistancePercent", reversalMinDistance * 100.0);
        session.put("devicePositionLogIntervalMs", devicePositionLogIntervalMs);
        try
        {
            DiagnosticTrace started = DiagnosticTrace.start(Path.of("logs", "diagnostics"), session);
            trace = started;
            log.info("HDSP_SMOOTHED: writing a JSONL diagnostic trace to {}", started.path().toAbsolutePath());
        }
        catch (IOException e)
        {
            log.warn("Could not start the diagnostic trace: {}", e.getMessage());
        }
    }

    /** Writes a structured trace event, when tracing is enabled. */
    private void traceEvent(String type, Object... fields)
    {
        DiagnosticTrace current = trace;
        if (current != null)
        {
            current.event(type, fields);
        }
    }

    /** @return the newest input value, read under the lock that {@link #addSample} writes it with */
    private InputSample lastSampleSnapshot()
    {
        synchronized (samples)
        {
            return lastSample;
        }
    }

    private double inputPositionSnapshot()
    {
        InputSample sample = lastSampleSnapshot();
        return sample == null ? 0.5 : sample.x();
    }

    /** @return the position the slider is believed to be at, also before the first command */
    private double believedPosition(SliderMove move, long atMs)
    {
        if (move != null)
        {
            return move.positionAt(atMs);
        }
        return Double.isNaN(initialSliderPosition) ? inputPositionSnapshot() : initialSliderPosition;
    }

    private static String describeMove(SliderMove move, long nowMs)
    {
        if (move == null)
        {
            return "none";
        }
        return "%s sent %dms ago %s%%->%s%% t=%dms".formatted(move.kind(), nowMs - move.sentMs(),
                percent(move.startX()), percent(move.target()), move.durationMs());
    }

    /**
     * Logs the position the device reports next to the position the slider is believed to be at, which shows
     * whether the device executes the moves the way they are planned (for example whether it can move as slowly
     * as it is asked to). Diagnostics only: every request is a round trip over BLE.
     * <p>
     * The request reaches the device about one command delay after it is sent, and so does every command: the
     * reported position is therefore compared with the position the slider was believed to have when the
     * request was sent (and when the response arrived), and the active move, the newest input value and the time
     * since the last command are logged as well, so that a sample can be aligned with the command stream.
     */
    private void startDevicePositionLog()
    {
        log.info("HDSP_SMOOTHED: logging the device position every {}ms (hdspLogDevicePosition=true; every read is a"
                + " BLE round trip)", devicePositionLogIntervalMs);
        Thread thread = new Thread(() ->
        {
            boolean failing = false;
            while (!Thread.currentThread().isInterrupted())
            {
                try
                {
                    long requestedAtMs = nowMs();
                    long requestedAtMonoMs = System.nanoTime() / 1_000_000L;
                    SliderMove moveAtRequest = move;
                    InputSample inputAtRequest = lastSampleSnapshot();
                    long lastCommandAtRequest = lastCommandSentMs;

                    Optional<SliderState> state = handyClient.getSliderState();

                    long respondedAtMs = nowMs();
                    long respondedAtMonoMs = System.nanoTime() / 1_000_000L;
                    long roundTripMs = respondedAtMs - requestedAtMs;
                    SliderMove moveAtResponse = move;
                    InputSample inputAtResponse = lastSampleSnapshot();
                    long lastCommandAtResponse = lastCommandSentMs;
                    if (state.isPresent())
                    {
                        SliderState slider = state.get();
                        double believedAtRequest = believedPosition(moveAtRequest, requestedAtMs);
                        double believedAtResponse = believedPosition(moveAtResponse, respondedAtMs);
                        double input = inputAtResponse == null ? 0.5 : inputAtResponse.x();
                        long inputAgeMs = inputAtResponse == null ? -1 : respondedAtMs - inputAtResponse.t();
                        long lastCommandAgeMs = lastCommandAtResponse == 0 ? -1 : respondedAtMs - lastCommandAtResponse;
                        String inputAge = inputAgeMs < 0 ? "no value yet" : inputAgeMs + "ms old";
                        String lastCommandAge = lastCommandAgeMs < 0 ? "none" : lastCommandAgeMs + "ms ago";
                        log.debug("[HDSP-SMOOTHED] Device at {}% ({} mm, {} mm/s), believed at request {}% /"
                                        + " response {}%, round trip {}ms; move {}; input {}% ({}); last command {}",
                                percent(slider.position()), "%.1f".formatted(slider.positionMm()),
                                "%.0f".formatted(slider.speedMmPerSecond()),
                                percent(believedAtRequest), percent(believedAtResponse), roundTripMs,
                                describeMove(moveAtResponse, respondedAtMs), percent(input), inputAge, lastCommandAge);
                        double inputAtRequestPosition = inputAtRequest == null ? 0.5 : inputAtRequest.x();
                        long inputAgeAtRequestMs = inputAtRequest == null ? -1 : requestedAtMs - inputAtRequest.t();
                        long lastCommandAgeAtRequestMs = lastCommandAtRequest == 0 ? -1 : requestedAtMs - lastCommandAtRequest;
                        traceEvent("device",
                                "position", slider.position(),
                                "positionMm", slider.positionMm(),
                                "reportedSpeedMmS", slider.speedMmPerSecond(),
                                "requestWallMs", requestedAtMs,
                                "responseWallMs", respondedAtMs,
                                "requestMonoMs", requestedAtMonoMs,
                                "responseMonoMs", respondedAtMonoMs,
                                "rttMs", roundTripMs,
                                "believedAtRequest", believedAtRequest,
                                "believedAtResponse", believedAtResponse,
                                "inputAtRequest", inputAtRequestPosition,
                                "inputAgeAtRequestMs", inputAgeAtRequestMs,
                                "lastCommandAgeAtRequestMs", lastCommandAgeAtRequestMs,
                                "moveAtRequestKind", moveAtRequest == null ? null : moveAtRequest.kind().name(),
                                "moveAtRequestSentWallMs", moveAtRequest == null ? null : moveAtRequest.sentMs(),
                                "moveAtRequestAgeMs", moveAtRequest == null ? null : requestedAtMs - moveAtRequest.sentMs(),
                                "moveAtRequestStart", moveAtRequest == null ? null : moveAtRequest.startX(),
                                "moveAtRequestTarget", moveAtRequest == null ? null : moveAtRequest.target(),
                                "moveAtRequestDurationMs", moveAtRequest == null ? null : moveAtRequest.durationMs(),
                                "moveAtResponseKind", moveAtResponse == null ? null : moveAtResponse.kind().name(),
                                "moveAtResponseSentWallMs", moveAtResponse == null ? null : moveAtResponse.sentMs(),
                                "moveAgeMs", moveAtResponse == null ? null : respondedAtMs - moveAtResponse.sentMs(),
                                "moveStart", moveAtResponse == null ? null : moveAtResponse.startX(),
                                "moveTarget", moveAtResponse == null ? null : moveAtResponse.target(),
                                "moveDurationMs", moveAtResponse == null ? null : moveAtResponse.durationMs(),
                                "input", input,
                                "inputAgeMs", inputAgeMs,
                                "lastCommandAgeMs", lastCommandAgeMs);
                    }
                    failing = false;
                }
                catch (Exception e)
                {
                    if (!failing)
                    {
                        log.warn("Could not read the device position: {}", e.getMessage());
                    }
                    failing = true; // Reported once: it fails every time once the connection is closed
                }
                try
                {
                    Thread.sleep(devicePositionLogIntervalMs);
                }
                catch (InterruptedException e)
                {
                    Thread.currentThread().interrupt();
                }
            }
        }, "hdsp-device-position");
        thread.setDaemon(true);
        thread.start();
        devicePositionLogThread = thread;
    }

    @Override
    public synchronized void stop()
    {
        super.stop();
        if (devicePositionLogThread != null)
        {
            devicePositionLogThread.interrupt();
        }
        DiagnosticTrace current = trace;
        trace = null;
        if (current != null)
        {
            current.event("session-end", "stoppedAtWallMs", nowMs());
            current.close();
        }
    }

    /**
     * Loads the newest calibration profile that belongs to this device. Nothing is loaded when the user turned the
     * profile off, so the two manual settings are used exactly as entered.
     */
    private static DeviceProfile loadDeviceProfile(HandyClient client, ConfigProperties config)
    {
        if (client == null || !config.hdspUseDeviceProfile())
        {
            return null;
        }
        return DeviceProfile.loadLatest(client.getDeviceIdentifier()).orElse(null);
    }

    private static int resolvePositive(Integer configured, int defaultValue)
    {
        return configured != null && configured > 0 ? configured : defaultValue;
    }

    @Override
    protected long getSendIntervalMs()
    {
        return Math.max(super.getSendIntervalMs(), HDSP_MIN_SEND_INTERVAL_MS);
    }

    @Override
    public void actOnValueChange(Float value)
    {
        addPositionPoint(toDevicePosition(value));
    }

    /**
     * In ORIFICE mode the movement arrives as the root proximity of the penetrator, which the base class turns
     * into a point without going through {@link #actOnValueChange}. The value is needed for the speed measurement
     * just like in PENETRATOR mode, so it is added to the input tracking here as well.
     */
    @Override
    public void actOnProximityChange(Float rootProximity, Float tipProximity)
    {
        super.actOnProximityChange(rootProximity, tipProximity);
        if (penetratorLengthDetector != null && rootProximity != null)
        {
            addSample(toDevicePosition(calculatePenetration(rootProximity)));
        }
    }

    /**
     * Adds a point. Unlike the plain HDSP processor every value is fed to the input tracking as well, whether it
     * becomes a point or not.
     */
    private void addPositionPoint(float position)
    {
        addSample(position);
        onValueChange.accept((int) (position * 100));
    }

    /**
     * Adds a raw input value. Unlike the plain HDSP processor the values are not filtered by
     * {@code minimalValueChange}: the value stream is what shows that the user is still moving or has stopped, so
     * every received value is used.
     */
    private void addSample(float floatPosition)
    {
        long now = nowMs();
        double position = Math.clamp(floatPosition, 0.f, 1.f);
        synchronized (samples)
        {
            if (lastSample == null)
            {
                log.info("First input value received, starting the input tracking");
                if (Double.isNaN(initialSliderPosition))
                {
                    // The device position could not be read: the slider is assumed to be where the input starts,
                    // and to stay there until it is moved (not to move along with the input on its own)
                    initialSliderPosition = position;
                }
            }
            lastSample = new InputSample(now, (float) position);
            samples.add(lastSample);
            samples.removeIf(sample -> now - sample.t() > SAMPLE_RETENTION_MS);
        }
        // Every raw value, so that a session can be replayed from its log
        log.trace("[HDSP-SMOOTHED] Value {}%", percent(position));
        traceEvent("value", "position", position, "raw", floatPosition);
        receivedSamples++;
        lastPointActivityMs = now;
    }

    @Override
    protected long trySendingMessage(long lastMoveSentMs)
    {
        logInputRate();
        long now = nowMs();
        double velocity = inputVelocity();
        updateInputState(now, velocity);
        long sinceLastCommandMs = now - lastCommandSentMs;
        if (sinceLastCommandMs < HDSP_MIN_SEND_INTERVAL_MS)
        {
            return lastMoveSentMs; // A command was sent just now, nothing can be gained from another one yet
        }
        // A stop and a reversal are sent right away; only the corrections of a movement respect the configured
        // send interval, every millisecond a stop waits is distance the slider runs past the user
        if (reversalPending || fastStopped || shouldHold(now))
        {
            // A moving slider is stopped. One that is not moving is only brought to the input when it is behind it
            // (a slow input is followed in steps, and the input can stop before it is a step ahead).
            if (sliderMoving(now) || (!holding && sliderBehindInput(now)))
            {
                holdAtInput(now, velocity);
                return now;
            }
        }
        MovePlan desired = desiredPlan(now, velocity);
        if (desired == null)
        {
            return lastMoveSentMs; // The slider is already where it should be
        }
        desired = limitSpeed(desired);
        boolean urgent = move == null || desired.kind() == MoveKind.HOLD || desired.towardsTop() != move.towardsTop();
        if (!urgent && sinceLastCommandMs < getSendIntervalMs())
        {
            return lastMoveSentMs;
        }
        if (moveChanged(now, desired) || moveEnding(now))
        {
            sendMove(now, desired);
            return now;
        }
        return lastMoveSentMs;
    }

    @Override
    protected void sleepUntilNextSend(long lastMessageSentMs)
    {
        // Wake up often enough to notice both the end of the move underway and a change of the input between two
        // received values (the values are used as they arrive, so this is the rate the algorithm works at).
        sleepSafe(HDSP_MIN_SEND_INTERVAL_MS / 4L);
    }

    /**
     * @return true when the slider should be stopped because the input stopped moving. A stall (the values no
     * longer change) is acted on immediately, a movement that only became slower after {@link #STOP_CONFIRM_MS}.
     */
    private boolean shouldHold(long now)
    {
        if (holding || belowStopSpeedSinceMs == 0)
        {
            return false;
        }
        if (inputMoved())
        {
            // Still moving, only slower than the algorithm follows: give it a moment, and while the input is in
            // the middle of a reversal (the values move but the average speed is still crossing zero) the
            // movement is followed instead of being stopped.
            return now - belowStopSpeedSinceMs >= STOP_CONFIRM_MS && inputDirection() == 0;
        }
        return true;
    }

    /**
     * @return the move the slider should be executing now: towards the endpoint the input is moving to, or a move
     * to the position the input stopped at. Null when the slider already is where it should be, so that a stopped
     * input cannot flood the device with commands.
     * <p>
     * The move is planned against the position the input is expected to have shortly (a look ahead), so the slider
     * follows the movement instead of trailing it, and the position the input had before that is what the slider
     * is compared with: the slider is never told to move further than the input, so it cannot arrive at an
     * endpoint long before the user gets there.
     */
    private MovePlan desiredPlan(long now, double velocity)
    {
        double sliderPosition = sliderPosition(now);
        double target = inputTowardsTop ? 1 : 0;
        double sliderDistance = Math.abs(target - sliderPosition);
        if (holding)
        {
            double holdError = holdTarget - sliderPosition;
            return Math.abs(holdError) <= MIN_MOVE_DISTANCE ? null : holdPlan(holdTarget, Math.abs(holdError), 0);
        }
        // Only the part of the measured speed that goes the way the input is heading drives the slider: the
        // average of the speed window still points the other way for a moment after a reversal.
        double speed = inputTowardsTop ? velocity : -velocity;
        boolean inputMoving = inputIsMoving(speed);
        if (needsResync(now, sliderPosition, inputMoving))
        {
            return resyncPlan(now, sliderPosition);
        }
        if (sliderDistance <= MIN_MOVE_DISTANCE)
        {
            return null; // The slider is already at the endpoint the input is moving towards
        }
        if (!inputMoving)
        {
            // The input is slower than the algorithm follows (or has not started moving yet), so there is nothing
            // to follow: the slider stays where it is instead of being moved towards an endpoint on its own.
            return null;
        }
        // The slider is given the look ahead time to close the distance to the input on top of moving with it:
        // a slider that is behind moves faster, and one that is ahead moves slower so that the input catches up.
        // The speed is bounded both ways, so a correction never turns into a sprint or into a stop.
        double extrapolatedInput = extrapolatedInputPosition(now, speed);
        // A command starts the device only after the connection and the firmware delays. Leading the input by
        // that time makes the movement arrive when the user gets there instead of trailing the whole stroke.
        double predictedInput = motionStartMs > 0 && !slowMovement && speed >= LATENCY_COMPENSATION_MIN_SPEED
                ? Math.clamp(extrapolatedInput + (inputTowardsTop ? 1 : -1) * speed * (motionStartMs / 1000.0),
                0.0, 1.0)
                : extrapolatedInput;
        double lag = sliderDistance - (inputTowardsTop ? 1 - predictedInput : predictedInput);
        // A slider that is ahead of a fast movement follows the newest values: the fit of the speed window still
        // holds the faster values of a deceleration, and a slider that keeps that speed runs past the user when
        // they stop. (The values of a fast movement arrive every frame, a slow one's newest pair is mostly noise.)
        double plannedSpeed = lag < 0 && speed >= FAST_STOP_MIN_SPEED ? Math.min(speed, newestSpeed(now)) : speed;
        double correctionMs = lag < 0 ? AHEAD_CORRECTION_MS : LOOK_AHEAD_MS;
        double desiredSpeed = Math.clamp(plannedSpeed + lag / (correctionMs / 1000.0),
                plannedSpeed * MIN_SLOW_DOWN_FACTOR, plannedSpeed * MAX_CATCH_UP_FACTOR);
        if (minSliderSpeed > 0 && (plannedSpeed < minSliderSpeed
                || (pulsing && plannedSpeed < minSliderSpeed * PULSE_EXIT_SPEED_RATIO)))
        {
            if (!pulsing)
            {
                log.debug("[HDSP-SMOOTHED] Input at {}%/s is slower than the slider can move, following it in pulses",
                        percentPerSecond(plannedSpeed));
            }
            pulsing = true;
            return pulsePlan(now, plannedSpeed, extrapolatedInput, lag);
        }
        pulsing = false;
        double slowestSpeed = Math.max(minSliderSpeed, MIN_SLIDER_SPEED);
        if (desiredSpeed < slowestSpeed)
        {
            if (lag < 0)
            {
                // Ahead of an input that slows down sharply: the slider waits where it is
                return move != null && move.kind() == MoveKind.ENDPOINT ? stopInPlacePlan(now) : null;
            }
            // The slider is ahead of an input that is faster than the minimum speed: it catches up at that speed
            desiredSpeed = slowestSpeed;
        }
        return new MovePlan(MoveKind.ENDPOINT, inputTowardsTop, target, sliderDistance, desiredSpeed, lag);
    }

    /**
     * @return true when the slider has to be brought to the input: it is further from it than
     * {@code hdspDivergenceThresholdPercent} while the input rests or while the slider is past it (the device
     * started somewhere else, so it may wait at the endpoint the input moves towards for the whole stroke), or
     * it is on its way to the input already. A slider that is far behind a moving input catches up with the
     * movement instead. Whether the input moves is judged from the raw values as well: the measured speed needs a
     * few values, and a movement that just started must not be mistaken for a rest (which would resync the slider
     * to the input at a moderate speed while the user moves away from it).
     */
    private boolean needsResync(long now, double sliderPosition, boolean inputMoving)
    {
        if (lastSample == null)
        {
            return false;
        }
        double gap = inputPosition() - sliderPosition;
        if (move != null && move.kind() == MoveKind.RESYNC && now < move.endMs())
        {
            return Math.abs(gap) > MIN_MOVE_DISTANCE;
        }
        boolean sliderPastInput = inputTowardsTop ? gap < 0 : gap > 0;
        // The raw values are what decides whether the input moves: the measured speed needs a few values before
        // it is available, and a movement that just started must not be mistaken for a stop (which would bring
        // the slider to the input at the moderate resync speed while the user moves away from it)
        boolean inputMoves = inputMoving || inputMoved();
        return Math.abs(gap) * 100 > divergenceThresholdPercent && (!inputMoves || sliderPastInput);
    }

    /**
     * @return a move of the slider to the input at {@link #RESYNC_SPEED}, stopping there. It is re-targeted while
     * the input moves (so that the slider stops where the input is when they meet), and null while the move
     * underway still ends close enough to the input.
     */
    private MovePlan resyncPlan(long now, double sliderPosition)
    {
        double inputPosition = inputPosition();
        if (move != null && move.kind() == MoveKind.RESYNC && now < move.endMs()
                && Math.abs(move.target() - inputPosition) < RESYNC_RETARGET_DISTANCE)
        {
            return null;
        }
        if (move == null || move.kind() != MoveKind.RESYNC)
        {
            log.debug("[HDSP-SMOOTHED] Slider at {}% is far from the input at {}%, bringing it there",
                    percent(sliderPosition), percent(inputPosition));
        }
        // A resync while the input moves has to keep up with it, otherwise the slider falls behind further
        // (a fixed moderate speed can never catch up with a fast movement)
        double resyncSpeed = Math.max(RESYNC_SPEED,
                Math.max(minSliderSpeed, Math.abs(inputVelocity())));
        return new MovePlan(MoveKind.RESYNC, inputPosition > sliderPosition, inputPosition,
                Math.abs(inputPosition - sliderPosition), resyncSpeed, 0);
    }

    /**
     * Plans the movement for an input that is slower than the device can move. The device does not move slower than
     * its minimum speed whatever it is told, so a slow move towards an endpoint runs ahead of the user (and the next
     * stop pulls the slider back), while moving to the input and stopping there at every few values makes the slider
     * stutter. Instead the slider moves in pulses at its minimum speed: a pulse ends {@link #PULSE_LEAD} ahead of
     * the input (where the slider stops by itself, a pulse is a move with stopOnTarget), and the slider pauses
     * until the input is {@link #PULSE_LAG} ahead of it. A slider that is moving already when the input becomes slow
     * goes on until it is that far ahead, rather than being stopped right away.
     *
     * @return the next pulse (or a re-planned one, when the input changed its speed), a stop of a slider that is
     * too far ahead, or null while a pulse is underway or the slider pauses
     */
    private MovePlan pulsePlan(long now, double inputSpeed, double inputPosition, double lag)
    {
        double direction = inputTowardsTop ? 1 : -1;
        double lead = -lag;
        boolean moving = sliderMoving(now);
        if (moving && move.kind() == MoveKind.STEP && move.towardsTop() == inputTowardsTop)
        {
            // A pulse is underway: it goes on unless the input changed its speed so much that it would end far
            // from where it should (too far ahead of a slower input, or behind a faster one)
            double inputAtEnd = inputPosition + direction * inputSpeed * ((move.endMs() - now) / 1000.0);
            double leadAtEnd = (move.target() - inputAtEnd) * direction;
            if (Math.abs(leadAtEnd - PULSE_LEAD) < PULSE_RETARGET_DISTANCE)
            {
                return null;
            }
        }
        else if (!moving && lead > -PULSE_LAG)
        {
            return null; // Pausing until the input is far enough ahead
        }
        if (lead >= PULSE_LEAD)
        {
            return moving ? stopInPlacePlan(now) : null;
        }
        double relativeSpeed = minSliderSpeed - inputSpeed;
        double durationS = relativeSpeed > 0
                ? Math.min((PULSE_LEAD - lead) / relativeSpeed, MAX_PULSE_MS / 1000.0)
                : MAX_PULSE_MS / 1000.0;
        double sliderPosition = sliderPosition(now);
        double target = Math.clamp(sliderPosition + direction * minSliderSpeed * durationS, 0.0, 1.0);
        double distance = Math.abs(target - sliderPosition);
        if (distance <= MIN_MOVE_DISTANCE)
        {
            return null; // At the end of the stroke already
        }
        return new MovePlan(MoveKind.STEP, inputTowardsTop, target, distance, minSliderSpeed, lag);
    }

    /**
     * @return true when the input moves faster than the algorithm follows (see the stop threshold), which is what
     * a movement has to be based on: a single value, or values that are only quantized noise, cannot drive it
     */
    private boolean inputIsMoving(double speed)
    {
        return speed >= followSpeedThreshold() && inputMoved();
    }

    /**
     * @return speed below which a slow movement stops being followed. A movement that is followed already keeps
     * being followed down to {@link #FOLLOWED_SPEED_THRESHOLD_RATIO} of the stop threshold (hysteresis): the measured
     * speed of a movement close to the threshold wobbles around it, and every dip would stop the slider.
     */
    private double followSpeedThreshold()
    {
        boolean followed = slowMovement && belowStopSpeedSinceMs == 0 && !holding;
        return stopSpeedThreshold() * (followed ? FOLLOWED_SPEED_THRESHOLD_RATIO : 1);
    }

    /** @return true when the newest values show more movement than the quantization of the input */
    private boolean inputMoved()
    {
        // A slow movement covers too little in the normal movement window to be told from jitter; the long window
        // that measures its speed is used instead. Without this a device that can move at 1 mm/s would never start.
        if (slowMovement)
        {
            return inputMoved(SLOW_SPEED_MEASURE_WINDOW_MS, MIN_INPUT_MOVEMENT_PERCENT / 200.0);
        }
        return inputMoved(INPUT_MOVEMENT_WINDOW_MS, MIN_INPUT_MOVEMENT_PERCENT / 100.0);
    }

    /**
     * @return true when the newest values (of the given time span, but at least {@link #MIN_MOVEMENT_SAMPLES} of
     * them) moved by at least the given distance (stroke units), and the input is not paused
     */
    private boolean inputMoved(long windowMs, double minMovement)
    {
        long now = nowMs();
        if (inputPaused(now))
        {
            return false;
        }
        List<InputSample> window = recentValues(now, windowMs, MIN_MOVEMENT_SAMPLES);
        return window.size() >= 2 && valueRange(window) >= minMovement;
    }

    private static double valueRange(List<InputSample> values)
    {
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (InputSample sample : values)
        {
            min = Math.min(min, sample.x());
            max = Math.max(max, sample.x());
        }
        return max - min;
    }

    /**
     * @return the newest values of the current movement: the ones of the given time span, extended to at least
     * the given number of values. VRChat only sends a value once it changed, so a slow movement can deliver less
     * than one value per window, which must not look like a stop (or, with two values that arrived close
     * together, like a fast movement).
     */
    private List<InputSample> recentValues(long now, long windowMs, int minCount)
    {
        List<InputSample> run = currentRun();
        int from = run.size();
        while (from > 0 && (now - run.get(from - 1).t() <= windowMs || run.size() - from < minCount))
        {
            from--;
        }
        return run.subList(from, run.size());
    }

    /**
     * @return the values of the current movement: the newest values back to the first gap between two values that
     * is a pause. Values from before a pause say nothing about the movement after it (the first value after a
     * pause is only the baseline of the values that follow it).
     */
    private List<InputSample> currentRun()
    {
        List<InputSample> all;
        synchronized (samples)
        {
            all = new ArrayList<>(samples);
        }
        double pauseMs = pauseThresholdMs(all);
        int from = all.size() - 1;
        while (from > 0 && all.get(from).t() - all.get(from - 1).t() <= pauseMs)
        {
            from--;
        }
        return from < 0 ? all : all.subList(from, all.size());
    }

    /**
     * @return true when no value arrived for longer than a pause: the user stopped (VRChat sends nothing while the
     * value does not change), or the values stopped arriving
     */
    private boolean inputPaused(long now)
    {
        InputSample newest = lastSample;
        if (newest == null)
        {
            return true;
        }
        List<InputSample> all;
        synchronized (samples)
        {
            all = new ArrayList<>(samples);
        }
        return now - newest.t() > pauseThresholdMs(all);
    }

    /**
     * @return time without a new value that counts as a pause (see {@link #PAUSE_INPUT_INTERVALS} and
     * {@link #PAUSE_LONGEST_INTERVALS})
     */
    private double pauseThresholdMs(List<InputSample> values)
    {
        long longest = 0;
        int count = 0;
        for (int i = values.size() - 1; i > 0 && count < TYPICAL_INTERVAL_SAMPLES; i--)
        {
            long interval = values.get(i).t() - values.get(i - 1).t();
            if (interval <= MAX_PAUSE_MS)
            {
                longest = Math.max(longest, interval);
                count++;
            }
        }
        double threshold = Math.max(typicalIntervalMs(values) * PAUSE_INPUT_INTERVALS, longest * PAUSE_LONGEST_INTERVALS);
        return Math.clamp(threshold, MIN_PAUSE_MS, MAX_PAUSE_MS);
    }

    /**
     * @return the average time between the newest values (pauses excluded). The average, not the median: values
     * that arrive in bursts shorten some intervals and lengthen others, and the time until the next value is due
     * is what the interval is used for.
     */
    private double typicalIntervalMs(List<InputSample> values)
    {
        long total = 0;
        int count = 0;
        for (int i = values.size() - 1; i > 0 && count < TYPICAL_INTERVAL_SAMPLES; i--)
        {
            long interval = values.get(i).t() - values.get(i - 1).t();
            if (interval <= MAX_PAUSE_MS)
            {
                total += interval;
                count++;
            }
        }
        return count == 0 ? DEFAULT_INPUT_INTERVAL_MS : Math.max(1.0, total / (double) count);
    }

    /**
     * @return speed (stroke units per second, in the direction the input moves, 0 when it moves the other way) of
     * the newest two values, where the time since the newest value counts as well: it is what shows a deceleration
     * first, and it keeps dropping while no new value arrives (the input slowed down or stopped).
     */
    private double newestSpeed(long now)
    {
        InputSample newest;
        InputSample previous;
        synchronized (samples)
        {
            if (samples.size() < 2)
            {
                return 0;
            }
            newest = samples.getLast();
            previous = samples.get(samples.size() - 2);
        }
        double spanMs = Math.max(newest.t() - previous.t(), now - newest.t());
        if (spanMs <= 0)
        {
            return Double.MAX_VALUE; // Two values at once: no speed to cap with
        }
        double movement = inputTowardsTop ? newest.x() - previous.x() : previous.x() - newest.x();
        return Math.max(0, movement / (spanMs / 1000.0));
    }

    /** @return the typical time between two input values right now (see {@link #typicalIntervalMs(List)}) */
    private double typicalIntervalMs()
    {
        synchronized (samples)
        {
            return typicalIntervalMs(samples);
        }
    }

    /**
     * Stops the slider instead of letting it finish the move it is on. A slider that is behind the input is
     * stopped at the position the input has reached (approached at the speed it is moving with). A slider that
     * got past the input is stopped where it is: sending it back would be a visible jump backwards, and the lead
     * is corrected by the following movement anyway. Only a lead beyond {@code hdspDivergenceThresholdPercent}
     * (which the planning does not let happen) is taken back, slowly.
     */
    private void holdAtInput(long now, double velocity)
    {
        double sliderPosition = sliderPosition(now);
        // Ahead and behind are judged along the way the slider moves; a slider that is not moving any more (it
        // finished a step) is judged along the way the input moves
        boolean moving = sliderMoving(now);
        boolean towardsTop = moving ? move.towardsTop() : inputTowardsTop;
        // The furthest position the input reached, not the newest value: while the input jitters (or starts to
        // reverse) the newest value can be a bit behind it, and the slider would be stopped short of where the
        // user actually got to
        double inputPosition = towardsTop == inputTowardsTop && !Double.isNaN(turnExtreme)
                ? turnExtreme
                : inputPosition();
        double lead = towardsTop ? sliderPosition - inputPosition : inputPosition - sliderPosition;
        // A moving slider approaches the input at the speed it has, a stopped one at a moderate one
        double approachSpeed = moving ? move.speed() : Math.max(minSliderSpeed, RESYNC_SPEED);
        MovePlan plan;
        if (lead * 100 > divergenceThresholdPercent)
        {
            plan = holdPlan(inputPosition, lead, Math.min(approachSpeed, MAX_RETURN_SPEED));
        }
        else if (lead >= 0)
        {
            plan = stopInPlacePlan(now);
        }
        else
        {
            plan = holdPlan(inputPosition, -lead, approachSpeed);
        }
        plan = limitSpeed(plan);
        holdTarget = plan.target();
        restPosition = inputPosition();
        log.debug("[HDSP-SMOOTHED] Input {} (speed={}%/s), stopping slider at {}% (input at {}%)",
                reversalPending ? "reversing" : fastStopped ? "stopped abruptly" : "stopped",
                percentPerSecond(velocity), percent(holdTarget), percent(inputPosition));
        sendMove(now, plan);
        holding = true;
        stoppedSinceMs = now;
    }

    /**
     * @return a stop of the slider where it is. The stop is placed a little further along the way the slider
     * moves: a command can reach the device later than the moves before it did, and by then the slider is past
     * the position it is believed to be at, so a stop exactly there would pull it back.
     */
    private MovePlan stopInPlacePlan(long now)
    {
        double sliderPosition = sliderPosition(now);
        double margin = sliderMoving(now) ? move.speed() * (HOLD_LATENCY_MARGIN_MS / 1000.0) : 0;
        double target = Math.clamp(sliderPosition + (move != null && move.towardsTop() ? margin : -margin), 0.0, 1.0);
        return holdPlan(target, Math.abs(target - sliderPosition), move == null ? 0 : move.speed());
    }

    /** @return true when the slider is behind the input (in the direction the input moves) by a visible distance */
    private boolean sliderBehindInput(long now)
    {
        if (move == null || lastSample == null)
        {
            return false;
        }
        double behind = inputTowardsTop ? inputPosition() - sliderPosition(now) : sliderPosition(now) - inputPosition();
        return behind > MIN_MOVE_DISTANCE;
    }

    /** @return true while the slider is moving: a move (or step) that has not arrived yet */
    private boolean sliderMoving(long now)
    {
        return move != null && move.kind() != MoveKind.HOLD && move.speed() > MIN_SLIDER_SPEED && now < move.endMs();
    }

    private MovePlan holdPlan(double position, double distance, double approachSpeed)
    {
        return new MovePlan(MoveKind.HOLD, position > 0.5, position, distance, approachSpeed, 0);
    }

    /**
     * @return true when the planned move is not what the device is doing: the input reversed, the speed changed
     * by more than the configured threshold, or a stop has to replace the move that is running
     */
    private boolean moveChanged(long now, MovePlan desired)
    {
        if (move == null || move.kind() != desired.kind())
        {
            return true;
        }
        if (desired.kind() == MoveKind.STEP || desired.kind() == MoveKind.RESYNC)
        {
            return true; // Only planned when it has to be sent (once the one before it is over or off target)
        }
        if (move.towardsTop() != desired.towardsTop())
        {
            return true;
        }
        if (desired.kind() == MoveKind.HOLD)
        {
            return false;
        }
        if (move.target() == desired.target() && move.endMs() - now <= MIN_MOVE_DURATION_MS
                && desired.lag() >= -MIN_MOVE_DISTANCE)
        {
            // The move underway is the final approach to the endpoint: every move planned from here is the same
            // short deceleration from a position closer to it, so re-issuing it would only flood the device (and
            // keep pushing the arrival back). Not when the slider is ahead of the input though: then the input
            // slows down before the endpoint, and the slider has to as well.
            return false;
        }
        // The speed the planned move is actually executed at: a move that hits the maximum duration is slower than
        // the input asks for, which is corrected by the next command once this one is over.
        double desiredSpeed = desired.executedSpeedPerSecond();
        double moveSpeed = move.speed();
        if (desiredSpeed <= 0)
        {
            return true;
        }
        double drift = Math.abs(desiredSpeed - moveSpeed) / desiredSpeed;
        if (Math.abs(desired.lag()) * 100 >= divergenceThresholdPercent && drift >= MIN_DIVERGENCE_SPEED_DRIFT)
        {
            log.debug("[HDSP-SMOOTHED] Slider is {}% {} the input, correcting", percent(Math.abs(desired.lag())),
                    desired.lag() > 0 ? "behind" : "ahead of");
            return true;
        }
        if (desired.lag() < -MIN_MOVE_DISTANCE && moveSpeed > desiredSpeed * (1 + AHEAD_SPEED_DRIFT))
        {
            // The slider is ahead of the input and still faster than planned (the input decelerates): that is the
            // lead that turns into an overshoot when the input stops or reverses, so it is not left to the threshold
            log.debug("[HDSP-SMOOTHED] Slider is {}% ahead of the input at {}%/s (planned {}%/s), slowing down",
                    percent(-desired.lag()), percentPerSecond(moveSpeed), percentPerSecond(desiredSpeed));
            return true;
        }
        if (drift * 100 < speedChangeThresholdPercent)
        {
            return false;
        }
        log.debug("[HDSP-SMOOTHED] Planned speed {}%/s differs from the move underway {}%/s by {}%, correcting",
                percentPerSecond(desiredSpeed), percentPerSecond(moveSpeed), percent(drift));
        return true;
    }

    /**
     * @return true when the move underway is close enough to its end that the following move has to be queued
     * now, so that the slider keeps moving without waiting for the next input values
     */
    private boolean moveEnding(long now)
    {
        return move != null && move.kind() == MoveKind.ENDPOINT && now >= move.renewAtMs();
    }

    /**
     * @return the plan with its speed limited to the measured maximum, or the plan itself when there is no maximum
     * (or the plan is already slower). The target and the distance stay the same; a slower move simply takes longer,
     * which keeps the position the slider is believed to be at realistic.
     */
    private MovePlan limitSpeed(MovePlan plan)
    {
        if (maxSliderSpeed <= 0 || plan.speedPerSecond() <= maxSliderSpeed)
        {
            return plan;
        }
        return new MovePlan(plan.kind(), plan.towardsTop(), plan.target(), plan.distance(), maxSliderSpeed, plan.lag());
    }

    /**
     * Sends a move and records it. The command is handed to the client without waiting for the move underway to
     * finish: the device replaces the running move, and because the command is issued for the position the slider
     * has reached by then, the slider keeps moving (at the corrected speed when there is one).
     */
    private void sendMove(long now, MovePlan plan)
    {
        plan = limitSpeed(plan);
        double position = sliderPosition(now);
        double durationMs = Math.round(plan.durationMs());
        sendHdspMove(plan.target(), (int) durationMs, plan.kind() != MoveKind.ENDPOINT);
        // The device does not move slower than its minimum speed, whatever time it is given: a move that asks
        // for less is believed to arrive as early as the device gets there
        double distance = Math.abs(plan.target() - position);
        double believedDurationMs = minSliderSpeed > 0 && distance > 0
                ? Math.min(durationMs, Math.max(1, distance / minSliderSpeed * 1000.0))
                : durationMs;
        move = new SliderMove(plan.kind(), now, position, plan.towardsTop(), plan.target(), (long) believedDurationMs);
        lastCommandSentMs = now;
        log.debug("[HDSP-SMOOTHED] Move to {}% in {}ms ({}%/s, slider at {}%, input {}%, {} values/s)",
                percent(plan.target()), (long) durationMs, percentPerSecond(plan.speedPerSecond()), percent(position),
                percent(inputPosition()), lastMeasuredInputRatePerSecond);
        traceEvent("move",
                "kind", plan.kind().name(),
                "towardsTop", plan.towardsTop(),
                "target", plan.target(),
                "durationMs", durationMs,
                "stopOnTarget", plan.kind() != MoveKind.ENDPOINT,
                "speedPerSecond", plan.speedPerSecond(),
                "slider", position,
                "input", inputPosition(),
                "lag", plan.lag(),
                "inputRatePerSecond", lastMeasuredInputRatePerSecond);
    }

    private void sendHdspMove(double target, int durationMs, boolean stopOnTarget)
    {
        try
        {
            // Positions leave the processor normalized (0.0-1.0), the unit the HDSP protocol uses.
            float normalizedXp = (float) Math.clamp(target, 0.0, 1.0);
            HandyBaseResponseWithError response = handyClient.hdspXpt(normalizedXp, durationMs, stopOnTarget);
            if (response.error() != null)
            {
                log.error("Error when sending HDSP command to Handy! (reason: {})", response.error().message());
            }
        }
        catch (Exception e)
        {
            log.error("Exception when sending HDSP command: {}", e.getMessage());
        }
    }

    /**
     * Updates the direction of the movement and the stop detection state. The input counts as moving while the
     * newest values change and the average speed of the speed window is above the stop threshold (a percentage of
     * the fastest speed of the current movement); the direction is taken from those values as well, because the
     * average speed of the window still crosses zero while the input is already moving the other way.
     */
    private void updateInputState(long now, double velocity)
    {
        double speed = Math.abs(velocity);
        // The reference speed decays all the time, not only while the input moves: a movement that is slower than
        // the stop threshold of an earlier, faster one has to become followable eventually.
        if (lastPeakDecayMs != 0 && now > lastPeakDecayMs)
        {
            peakSpeed *= Math.exp(-(now - lastPeakDecayMs) / (double) PEAK_SPEED_DECAY_MS);
        }
        lastPeakDecayMs = now;
        int direction = inputDirection();
        double position = inputPosition();
        if (lastSample != null
                && (Double.isNaN(turnExtreme) || (inputTowardsTop ? position > turnExtreme : position < turnExtreme)))
        {
            turnExtreme = position;
        }
        // A movement against the current direction is only a reversal once the input moved back far enough from
        // the furthest position it reached; until then it is treated as a stop, so jitter cannot reverse the slider
        boolean movedBack = lastSample != null && !Double.isNaN(turnExtreme)
                && Math.abs(position - turnExtreme) >= reversalMinDistance;
        if (slowMovement && direction != 0 && (direction > 0) == inputTowardsTop && movedBack
                && Math.signum(windowVelocity()) == (inputTowardsTop ? -1 : 1))
        {
            // The fit of a slow movement still shows the old direction for a while after a reversal, but jitter
            // cannot move the input back this far, so the reversal is taken from the position instead
            direction = inputTowardsTop ? -1 : 1;
        }
        reversalPending = direction != 0 && (direction > 0) != inputTowardsTop && !movedBack;
        if (Double.isNaN(restPosition) && lastSample != null)
        {
            restPosition = position; // The first value: the input rests there until it moves
        }
        if (direction != 0 && (holding || move == null) && Math.abs(position - restPosition) < START_MIN_DISTANCE)
        {
            // Still within the jitter around the position the input rested at: not a movement yet
            direction = 0;
            reversalPending = false;
        }
        if (reversalPending)
        {
            direction = 0;
        }
        updateFastStop(now, speed);
        if (fastStopped)
        {
            direction = 0;
        }
        if (direction != 0)
        {
            setDirection(direction > 0);
            if (stoppedSinceMs != 0)
            {
                log.debug("[HDSP-SMOOTHED] Input resumed after {}ms at {}%/s", now - stoppedSinceMs,
                        percentPerSecond(speed));
                stoppedSinceMs = 0;
            }
            belowStopSpeedSinceMs = 0;
            holding = false;
            peakSpeed = Math.max(speed, peakSpeed);
            return;
        }
        if (belowStopSpeedSinceMs == 0)
        {
            belowStopSpeedSinceMs = now;
        }
    }

    /**
     * Notices an abrupt stop from the newest value instead of waiting until the measurement windows show it: the
     * slider keeps moving at the speed of the input until the stop is noticed, so every input interval of delay
     * is distance the slider runs past the user. The newest value pair is compared with the pair before it (a
     * stop collapses from one value to the next, while a deceleration at an endpoint changes gradually), and the
     * time since the newest value counts as well, so a stop after which VRChat sends nothing (it only sends values
     * that changed) is noticed as soon as the next value is overdue.
     */
    private void updateFastStop(long now, double speed)
    {
        InputSample newest;
        InputSample previous;
        InputSample beforePrevious;
        synchronized (samples)
        {
            if (samples.size() < 3)
            {
                fastStopped = false;
                return;
            }
            newest = samples.getLast();
            previous = samples.get(samples.size() - 2);
            beforePrevious = samples.get(samples.size() - 3);
        }
        double pairSpanMs = Math.max(newest.t() - previous.t(), now - newest.t());
        double previousSpanMs = previous.t() - beforePrevious.t();
        if (pairSpanMs <= 0 || previousSpanMs <= 0)
        {
            return;
        }
        double pairMovement = inputTowardsTop ? newest.x() - previous.x() : previous.x() - newest.x();
        double previousMovement = inputTowardsTop
                ? previous.x() - beforePrevious.x()
                : beforePrevious.x() - previous.x();
        double pairSpeed = pairMovement / (pairSpanMs / 1000.0);
        double previousSpeed = previousMovement / (previousSpanMs / 1000.0);
        if (fastStopped)
        {
            // The stop lasts until a value moves again (in either direction, a reversal is handled from there)
            fastStopped = Math.abs(pairSpeed) < stopSpeedThreshold() || now - newest.t() > typicalIntervalMs();
            return;
        }
        if (speed < FAST_STOP_MIN_SPEED)
        {
            return;
        }
        // The newest value is compared with the value before it, not with the average of the speed window: a
        // movement that slows down near the end of its way (or before a reversal) changes gradually, while a stop
        // collapses from one value to the next. Comparing with the much faster average of the window would turn
        // every deceleration at an endpoint into a stop, which stops the slider short of the endpoint.
        if (pairMovement >= 0 && pairSpeed < FAST_STOP_MAX_SPEED
                && pairSpeed < previousSpeed * FAST_STOP_SPEED_RATIO)
        {
            fastStopped = true;
            log.debug("[HDSP-SMOOTHED] Input stopped abruptly (newest value moved at {}%/s, value before at {}%/s)",
                    percentPerSecond(pairSpeed), percentPerSecond(previousSpeed));
        }
        else if (now - newest.t() > typicalIntervalMs() * FAST_STOP_OVERDUE_INTERVALS)
        {
            fastStopped = true;
            log.debug("[HDSP-SMOOTHED] Input stopped abruptly (no value for {}ms at {}%/s)", now - newest.t(),
                    percentPerSecond(speed));
        }
    }

    /**
     * @return position the input is expected to have right now: the newest value, moved on at the measured speed
     * for the time since it arrived (at most one input interval, the next value is due by then). Comparing the
     * slider with the newest value alone would make a slider that moves with the input look ahead of it.
     */
    private double extrapolatedInputPosition(long now, double speed)
    {
        if (lastSample == null || slowMovement)
        {
            // The fit of a slow movement lags behind a deceleration, so extrapolating with it would make a slider
            // that runs ahead look like it follows; the movement within one input interval is negligible there
            return inputPosition();
        }
        double elapsedMs = Math.min(now - lastSample.t(), typicalIntervalMs());
        double movement = Math.max(0, speed) * (elapsedMs / 1000.0);
        return Math.clamp(inputPosition() + (inputTowardsTop ? movement : -movement), 0.0, 1.0);
    }

    /**
     * Updates the direction of the movement, which is the endpoint every move is planned towards. The direction
     * of the last movement is kept while the input is (nearly) stopped, so a reversal at an endpoint does not flip
     * the target while the input is in the middle of it.
     */
    private void setDirection(boolean towardsTop)
    {
        if (towardsTop != inputTowardsTop)
        {
            inputTowardsTop = towardsTop;
            turnExtreme = inputPosition();
            log.debug("[HDSP-SMOOTHED] Input reversed, now moving {} (input at {}%, {}% left)",
                    towardsTop ? "up" : "down", percent(inputPosition()), percent(inputDistance()));
        }
    }

    /**
     * @return the direction the newest values show (1 = towards the top, -1 = towards the bottom), or 0 when they
     * do not show a movement that is unambiguous. It is measured over the same values the movement detection uses,
     * which is what makes a reversal visible while the average speed of the much longer speed window is still
     * crossing zero.
     */
    private int inputDirection()
    {
        if (slowMovement)
        {
            // The newest values of a slow movement are dominated by jitter, the fit over the long window is not
            double velocity = slowVelocity();
            long now = nowMs();
            List<InputSample> newest = recentValues(now, speedMeasureWindowMs, MIN_MOVEMENT_SAMPLES);
            double spanMs = newest.isEmpty() ? 0 : now - newest.getFirst().t();
            double expectedMovement = Math.abs(velocity) * (spanMs / 1000.0);
            if (inputPaused(now) || newest.size() < 2 || valueRange(newest)
                    < Math.min(MIN_INPUT_MOVEMENT_PERCENT / 100.0, expectedMovement * SLOW_STALL_MOVEMENT_RATIO))
            {
                // The values stopped changing, while the fit still holds the movement before that. Checked over
                // the speed window against what the fit expects: a slow movement covers too little in the
                // movement window to tell it from a stop there
                return 0;
            }
            double windowVelocity = windowVelocity();
            if (Math.abs(windowVelocity) >= Math.max(stopSpeedThreshold(), Math.abs(velocity))
                    && Math.signum(windowVelocity) != Math.signum(velocity))
            {
                // The newest values clearly go the other way (at least as fast as the fit goes the old way):
                // either jitter or the start of a reversal, which the fit only shows later. Until that is decided
                // the slider must not keep moving the old way.
                return 0;
            }
            return Math.abs(velocity) < followSpeedThreshold() ? 0 : velocity > 0 ? 1 : -1;
        }
        long now = nowMs();
        if (inputPaused(now))
        {
            return 0;
        }
        List<InputSample> window = recentValues(now, INPUT_MOVEMENT_WINDOW_MS, MIN_MOVEMENT_SAMPLES);
        if (window.size() < 2 || window.getLast().t() == window.getFirst().t())
        {
            return 0;
        }
        InputSample first = window.getFirst();
        InputSample last = window.getLast();
        double movement = last.x() - first.x();
        double speed = Math.abs(movement) / ((last.t() - first.t()) / 1000.0);
        if (speed < stopSpeedThreshold())
        {
            return 0;
        }
        return movement > 0 ? 1 : -1;
    }

    /** Input speed below which the input counts as stopped (see {@link #MIN_INPUT_SPEED_PERCENT_PER_SECOND}). */
    private double stopSpeedThreshold()
    {
        double relativeThreshold = peakSpeed * (speedStopThresholdPercent / 100.0);
        return Math.max(relativeThreshold, minInputSpeedPercentPerSecond / 100.0);
    }

    /** @return position the slider is expected to be at right now */
    private double sliderPosition(long now)
    {
        if (move != null)
        {
            return move.positionAt(now);
        }
        // Nothing was sent yet: the slider is where the device reported it, or (when it could not be read) where the
        // input started, the only other information about that position
        return Double.isNaN(initialSliderPosition) ? inputPosition() : initialSliderPosition;
    }

    /** @return position the input is at right now (0 = bottom, 1 = top of the stroke) */
    private double inputPosition()
    {
        return lastSample == null ? 0.5 : lastSample.x();
    }

    /** @return distance the input still has to move until it reaches the end of its movement */
    private double inputDistance()
    {
        double position = inputPosition();
        return inputTowardsTop ? 1 - position : position;
    }

    /**
     * @return signed input speed in stroke units per second (positive = towards the top of the stroke), measured
     * as the movement of the raw values over the speed window (or of the two newest values while they move faster).
     */
    private double inputVelocity()
    {
        double velocity = windowVelocity();
        double slow = slowVelocity();
        // A movement that is fast within the normal window is followed with it (the long window would lag behind
        // the start of every stroke). The long window is only used when it also shows a slow movement: at a
        // reversal of a fast movement the short window crosses zero while the long one still holds the movement
        // before it (and at 2.5 strokes per second it even holds a whole stroke).
        slowMovement = Math.abs(velocity) < SLOW_MOVEMENT_SPEED && !Double.isNaN(slow)
                && Math.abs(slow) < SLOW_MOVEMENT_SPEED;
        if (!slowMovement)
        {
            // A fast movement that just started (or reversed) is still averaged down by the values before it:
            // the newest values already move, so their speed is what the slider can start with. Only when they
            // move faster (an acceleration) and in the same direction as the fit; slowing down is left to the
            // (steadier) fit so that the slider does not run past an input that decelerates.
            double newestPair = newestPairVelocity(nowMs());
            boolean fitMissing = Math.abs(velocity) < MIN_INPUT_SPEED_PERCENT_PER_SECOND / 100.0;
            if (!Double.isNaN(newestPair)
                    && (fitMissing || (Math.signum(newestPair) == Math.signum(velocity)
                    && Math.abs(newestPair) > Math.abs(velocity))))
            {
                return newestPair;
            }
            return velocity;
        }
        // The fit decides the direction, but it lags behind a deceleration (it still holds the faster values of the
        // start of the window), so the speed is never taken higher than the normal window measures: a slider that
        // is too fast is what runs past the input. When the two disagree on the direction nothing is certain yet.
        if (Math.signum(slow) != Math.signum(velocity))
        {
            return 0;
        }
        return Math.signum(slow) * Math.min(Math.abs(slow), Math.abs(velocity));
    }

    /**
     * @return signed speed of the two newest values (NaN when they do not move the same way as the pair before
     * them, or when no value arrived recently). The fit of the speed window needs a few values to show a movement
     * that just started (or a reversal), while the newest values already move: following them as well removes the
     * dead time at the start of a fast stroke.
     */
    private double newestPairVelocity(long now)
    {
        InputSample newest;
        InputSample previous;
        InputSample beforePrevious;
        synchronized (samples)
        {
            if (samples.size() < 3)
            {
                return Double.NaN;
            }
            newest = samples.getLast();
            previous = samples.get(samples.size() - 2);
            beforePrevious = samples.get(samples.size() - 3);
        }
        if (inputPaused(now))
        {
            return Double.NaN;
        }
        long spanMs = Math.max(newest.t() - previous.t(), now - newest.t());
        long previousSpanMs = previous.t() - beforePrevious.t();
        if (spanMs <= 0 || previousSpanMs <= 0)
        {
            return Double.NaN;
        }
        double speed = (newest.x() - previous.x()) / (spanMs / 1000.0);
        double previousSpeed = (previous.x() - beforePrevious.x()) / (previousSpanMs / 1000.0);
        if (speed == 0 || Math.signum(speed) != Math.signum(previousSpeed))
        {
            return Double.NaN; // Jitter (or the first value after a reversal): no direction yet
        }
        return Math.clamp(speed, -MAX_INPUT_SPEED, MAX_INPUT_SPEED);
    }

    /**
     * @return signed input speed measured over the normal speed window (see {@link #inputVelocity()}): the least
     * squares fit of its values. The fit uses every value of the window, so a single value that is off by a
     * quantization step (or two values that arrived close together) moves it much less than it would move the
     * difference of the first and the last value.
     */
    private double windowVelocity()
    {
        List<InputSample> window = recentValues(nowMs(), speedMeasureWindowMs, MIN_SPEED_SAMPLES);
        if (window.size() < 2 || window.getLast().t() - window.getFirst().t() < MIN_SPEED_MEASURE_WINDOW_MS)
        {
            return 0;
        }
        double velocity = fitVelocity(window);
        return Double.isNaN(velocity) ? 0 : velocity;
    }

    /**
     * @return signed input speed of the least squares fit over {@link #SLOW_SPEED_MEASURE_WINDOW_MS}, NaN while
     * the window does not hold enough values for a fit
     */
    private double slowVelocity()
    {
        List<InputSample> window = recentValues(nowMs(), SLOW_SPEED_MEASURE_WINDOW_MS, MIN_SLOW_FIT_SAMPLES);
        if (window.size() < 3 || window.getLast().t() - window.getFirst().t() < SLOW_SPEED_MEASURE_WINDOW_MS / 2)
        {
            return Double.NaN;
        }
        return fitVelocity(window);
    }

    /** @return slope of the least squares line through the values, NaN when their times do not differ */
    private static double fitVelocity(List<InputSample> window)
    {
        double meanT = 0;
        double meanX = 0;
        for (InputSample sample : window)
        {
            meanT += sample.t();
            meanX += sample.x();
        }
        meanT /= window.size();
        meanX /= window.size();
        double covariance = 0;
        double variance = 0;
        for (InputSample sample : window)
        {
            double dt = (sample.t() - meanT) / 1000.0;
            covariance += dt * (sample.x() - meanX);
            variance += dt * dt;
        }
        return variance <= 0 ? Double.NaN : Math.clamp(covariance / variance, -MAX_INPUT_SPEED, MAX_INPUT_SPEED);
    }

    /** Logs the measured input update rate, which is useful to judge the speed measurement and the thresholds. */
    private void logInputRate()
    {
        long now = nowMs();
        long rate = Math.round(inputRatePerSecond());
        if (rate > 0)
        {
            lastMeasuredInputRatePerSecond = rate;
        }
        if (now - lastInputRateLogMs < INPUT_RATE_LOG_INTERVAL_MS)
        {
            return;
        }
        lastInputRateLogMs = now;
        if (lastMeasuredInputRatePerSecond > 0)
        {
            log.debug("[HDSP-SMOOTHED] Input update rate: {} values/s ({} values received, window {}ms)",
                    lastMeasuredInputRatePerSecond, receivedSamples, speedMeasureWindowMs);
        }
    }

    /** @return input update rate in values per second, 0 when it cannot be measured yet */
    private double inputRatePerSecond()
    {
        List<InputSample> window;
        synchronized (samples)
        {
            window = new ArrayList<>(samples);
        }
        if (window.size() < 2)
        {
            return 0;
        }
        long spanMs = window.getLast().t() - window.getFirst().t();
        if (spanMs <= 0)
        {
            return 0;
        }
        return (window.size() - 1) * 1000.0 / spanMs;
    }

    // Tests only
    double sliderPositionForTest(long atTimeMs)
    {
        return sliderPosition(atTimeMs);
    }

    private static String percent(double value)
    {
        return "%.1f".formatted(value * 100);
    }

    private static String percentPerSecond(double value)
    {
        return "%.0f".formatted(value * 100);
    }

    /** Raw input value used for the speed measurement. */
    private record InputSample(long t, float x) {}

    /** What a move does (and so how it is planned, sent and replaced). */
    private enum MoveKind
    {
        /** Towards the top or the bottom of the stroke with the speed of the input, renewed while it runs. */
        ENDPOINT,
        /** To the position of an input that is slower than the device can move, stopping there (stopOnTarget). */
        STEP,
        /** A stop: at the position the slider is held at, approached with the given speed (stopOnTarget). */
        HOLD,
        /** To the input, from a slider that is far from it, stopping there (stopOnTarget). */
        RESYNC
    }

    /**
     * The move the slider should perform. The lag is how far (in stroke units) the slider is behind the input
     * (negative when it is ahead of it).
     */
    private record MovePlan(MoveKind kind, boolean towardsTop, double target, double distance, double speedPerSecond,
                            double lag)
    {
        /**
         * Time the move takes. The minimum shortens a move that would otherwise be too small to be visible, and a
         * stop takes just long enough for the slider to arrive at its position without a jump.
         */
        double durationMs()
        {
            if (speedPerSecond <= 0 || distance <= 0)
            {
                return MIN_HOLD_DURATION_MS;
            }
            double durationMs = distance / speedPerSecond * 1000.0;
            return switch (kind)
            {
                case HOLD -> Math.clamp(durationMs, MIN_HOLD_DURATION_MS, MAX_HOLD_DURATION_MS);
                case STEP, RESYNC -> Math.clamp(durationMs, 1, HDSP_MAX_MOVE_DURATION_MS);
                case ENDPOINT -> Math.clamp(durationMs, MIN_MOVE_DURATION_MS, HDSP_MAX_MOVE_DURATION_MS);
            };
        }

        /**
         * @return speed the move is executed at. It differs from {@link #speedPerSecond()} when the move is
         * shorter than the minimum duration (the last bit before the endpoint, where the slider decelerates).
         */
        double executedSpeedPerSecond()
        {
            return kind == MoveKind.HOLD || distance <= 0 ? 0 : distance / (durationMs() / 1000.0);
        }
    }

    /** The move the device is executing: from the position at the send time towards the target in durationMs. */
    private record SliderMove(MoveKind kind, long sentMs, double startX, boolean towardsTop, double target,
                              long durationMs)
    {
        /** @return position the slider is expected to have at the given time */
        double positionAt(long now)
        {
            if (durationMs <= 0)
            {
                return target;
            }
            double progress = Math.clamp((now - sentMs) / (double) durationMs, 0.0, 1.0);
            return Math.clamp(startX + (target - startX) * progress, 0.0, 1.0);
        }

        /** Speed (stroke units per second) the slider moves with while executing this move. */
        double speed()
        {
            return durationMs <= 0 || kind == MoveKind.HOLD ? 0 : Math.abs(target - startX) / (durationMs / 1000.0);
        }

        /** App time at which the slider arrives at the target. */
        long endMs()
        {
            return sentMs + durationMs;
        }

        /** App time at which the following move has to be queued to keep the slider moving. */
        long renewAtMs()
        {
            return sentMs + Math.min(Math.round(durationMs * MOVE_RENEWAL_RATIO), MOVE_RENEWAL_INTERVAL_MS);
        }
    }
}
