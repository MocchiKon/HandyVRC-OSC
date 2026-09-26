package org.example.processor;

import handy.model.DeviceModeValue;
import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.example.Main.delayedClosingWithLog;

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
 *   <li>The first value of a movement, and the first value after the input stopped, are only used as the baseline
 *   of the measurement of the values that follow: a single value has no timing, so no move is started from it.</li>
 *   <li>When the input stops, the slider is stopped at the position the input stopped at (a stop is sent as a move
 *   of zero duration, so the device does not finish the move it was on). When the input moves again, the slider
 *   continues from there.</li>
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
    public static final int DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT = 25;
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
     * A longer window is a steadier (but slower to react) speed.
     */
    public static final int DEFAULT_SPEED_MEASURE_WINDOW_MS = 150;
    /** Lower bound for the configured speed measurement window. */
    static final int MIN_SPEED_MEASURE_WINDOW_MS = 50;
    /**
     * Input speed (percent per second) below which the input always counts as stopped, whatever the speed of the
     * movement is. It keeps the quantization of a (nearly) still avatar parameter from driving the slider, at the
     * price of not following movements slower than this. It is also the speed that is used to stop the slider at
     * the position the input stopped at.
     */
    static final double MIN_INPUT_SPEED_PERCENT_PER_SECOND = 4;
    /** Cap for a measured input speed: a full stroke in less than a quarter of a second is not a real movement. */
    static final double MAX_INPUT_SPEED = 4.0;
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
     * shorter, which is what makes those moves decelerate instead of overshooting.
     */
    static final int MIN_MOVE_DURATION_MS = 120;
    /** Upper bound for a single move, so that a very slow input is still split into followable moves. */
    public static final int HDSP_MAX_MOVE_DURATION_MS = 2000;
    /** Weight of the newest value pair in the measured speed (the rest comes from the whole speed window). */
    private static final double NEWEST_SPEED_WEIGHT = 0.35;
    /** Catch up speed as a multiple of the input speed, so a slider that fell behind never sprints. */
    private static final double MAX_CATCH_UP_FACTOR = 1.25;
    /**
     * How far the slider plans ahead of the input. It is also the time the slider has to make up the distance
     * between it and the input, so a shorter time catches up faster (with a higher speed) and a longer one turns
     * the correction into a gentler approach.
     */
    private static final int LOOK_AHEAD_MS = 300;
    /** Input speed is considered stopped after it was below the stop threshold for this long. */
    private static final int STOP_CONFIRM_MS = 50;
    /**
     * Time constant of the decay of the reference speed the stop threshold is relative to. A movement that became
     * much slower must not keep being measured against the speed of an earlier, faster movement forever.
     */
    private static final int PEAK_SPEED_DECAY_MS = 5_000;
    /** Speed (stroke units per second) above which the slider counts as moving, so that it can be stopped. */
    private static final double MIN_SLIDER_SPEED = 0.01;
    /** A move shorter than this (in stroke units) is not visible, so the slider is treated as being there already. */
    private static final double MIN_MOVE_DISTANCE = 0.01;
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

    /** Raw input values, used for the speed measurement only. */
    private final List<InputSample> samples = new ArrayList<>();
    private InputSample lastSample;
    /** True when the input moves towards the top of the stroke (the endpoint the slider has to head for). */
    private boolean inputTowardsTop = true;

    /** The move the device is executing, or null before the first command. */
    private SliderMove move;
    /** True while the slider is held at the position the input stopped at. */
    private boolean holding;
    /** Position the slider is held at (only meaningful while {@link #holding} is true). */
    private double holdTarget = 0.5;
    /** App time at which the last command was sent (0 while nothing was sent yet). */
    private long lastCommandSentMs;
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

    public HdspSmoothedParameterProcessor(HandyClient handyClient, ConfigProperties config)
    {
        super(handyClient, config);
        this.speedChangeThresholdPercent = resolvePositive(config.hdspSpeedChangeThresholdPercent(),
                DEFAULT_SPEED_CHANGE_THRESHOLD_PERCENT);
        this.speedStopThresholdPercent = resolvePositive(config.hdspSpeedStopThresholdPercent(),
                DEFAULT_SPEED_STOP_THRESHOLD_PERCENT);
        this.divergenceThresholdPercent = resolvePositive(config.hdspDivergenceThresholdPercent(),
                DEFAULT_DIVERGENCE_THRESHOLD_PERCENT);
        this.speedMeasureWindowMs = Math.max(resolvePositive(config.hdspSpeedMeasureWindowMs(),
                DEFAULT_SPEED_MEASURE_WINDOW_MS), MIN_SPEED_MEASURE_WINDOW_MS);

        HandyBaseResponseWithError response = handyClient.changeMode(DeviceModeValue.HDSP);
        if (response.error() != null)
        {
            delayedClosingWithLog("Could not change Handy mode to HDSP (reason: %s). Closing app...".formatted(response.error().message()));
        }
        if (config.pointsOffset() != null)
        {
            log.warn("'pointsOffset' is ignored in HDSP mode (HDSP is a direct protocol without device-side buffering)");
        }
        if (config.hdspTiming())
        {
            log.info("'hdspTiming' is not used by HDSP_SMOOTHED (moves are chained by extrapolating the input, not by"
                    + " predicting the device delay)");
        }
        log.info("HDSP_SMOOTHED: speedChangeThreshold={}%, speedStopThreshold={}%, divergenceThreshold={}%, speedWindow={}ms",
                speedChangeThresholdPercent, speedStopThresholdPercent, divergenceThresholdPercent, speedMeasureWindowMs);
        log.debug("HDSP_SMOOTHED does not apply minimalValueChange ({}): raw OSC values are needed to notice that"
                + " the user stopped moving", config.minimalValueChange());

        if (config.sliderMin() != null || config.sliderMax() != null)
        {
            handyClient.setSliderSettings(config.sliderMin(), config.sliderMax());
        }
        Optional<SliderSettingsResult> sliderSettings = handyClient.getSliderSettings();
        sliderSettings.ifPresent(s -> log.info("Slider settings min={}, max={}", s.min(), s.max()));
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
            }
            lastSample = new InputSample(now, (float) position);
            samples.add(lastSample);
            samples.removeIf(sample -> now - sample.t() > speedMeasureWindowMs);
        }
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
        if (now - lastCommandSentMs < getSendIntervalMs())
        {
            return lastMoveSentMs; // A command was sent just now, nothing can be gained from another one yet
        }
        if (shouldHold(now))
        {
            // A slider that is not moving is not stopped, and the hold is only planned once the input moved at
            // least once (a slider that never moved is already where the input is).
            if (move != null && move.speed() > MIN_SLIDER_SPEED)
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
        if (moveChanged(desired) || moveEnding(now))
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
            return Math.abs(holdError) <= MIN_MOVE_DISTANCE ? null : holdPlan(holdTarget, Math.abs(holdError));
        }
        if (sliderDistance <= MIN_MOVE_DISTANCE)
        {
            return null; // The slider is already at the endpoint the input is moving towards
        }
        double speed = Math.abs(velocity);
        if (!inputIsMoving(velocity))
        {
            // The input is slower than the algorithm follows (or has not started moving yet), so there is nothing
            // to follow: the slider stays where it is instead of being moved towards an endpoint on its own.
            return null;
        }
        // Where the input is expected to be after the look ahead time: that is where the slider belongs right now,
        // and the slider is given that same time to get there (which is shorter than the time it would need at the
        // speed of the input when it is behind, so it catches up with the movement).
        double desiredDistance = Math.max(0, inputDistance() - speed * (LOOK_AHEAD_MS / 1000.0));
        double desiredSpeed = Math.abs(desiredDistance - sliderDistance) / (LOOK_AHEAD_MS / 1000.0);
        return new MovePlan(inputTowardsTop, target, sliderDistance, Math.max(desiredSpeed, speed));
    }

    /**
     * @return true when the input moves faster than the algorithm follows (see the stop threshold), which is what
     * a movement has to be based on: a single value, or values that are only quantized noise, cannot drive it
     */
    private boolean inputIsMoving(double velocity)
    {
        return Math.abs(velocity) >= stopSpeedThreshold() && inputMoved();
    }

    /** @return true when the newest values show more movement than the quantization of the input */
    private boolean inputMoved()
    {
        long now = nowMs();
        List<InputSample> window;
        synchronized (samples)
        {
            window = new ArrayList<>(samples);
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        int count = 0;
        for (InputSample sample : window)
        {
            if (now - sample.t() > INPUT_MOVEMENT_WINDOW_MS)
            {
                continue;
            }
            min = Math.min(min, sample.x());
            max = Math.max(max, sample.x());
            count++;
        }
        return count >= 2 && (max - min) * 100 >= MIN_INPUT_MOVEMENT_PERCENT;
    }

    /**
     * Stops the slider at the position the input has reached instead of letting it finish the move it is on. The
     * stop is a move of zero duration, so the device stops there immediately.
     */
    private void holdAtInput(long now, double velocity)
    {
        holdTarget = inputPosition();
        double distance = Math.abs(holdTarget - sliderPosition(now));
        log.debug("[HDSP-SMOOTHED] Input stopped (speed={}%/s), stopping slider at {}%", percentPerSecond(velocity),
                percent(holdTarget));
        sendMove(now, holdPlan(holdTarget, distance));
        holding = true;
        stoppedSinceMs = now;
    }

    private MovePlan holdPlan(double position, double distance)
    {
        return new MovePlan(position > 0.5, position, distance, 0);
    }

    /**
     * @return true when the planned move is not what the device is doing: the input reversed, the speed changed
     * by more than the configured threshold, or a stop has to replace the move that is running
     */
    private boolean moveChanged(MovePlan desired)
    {
        if (move == null || move.stop() != desired.isStop())
        {
            return true;
        }
        if (move.towardsTop() != desired.towardsTop())
        {
            return true;
        }
        if (desired.isStop())
        {
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
        return move != null && !move.stop() && now >= move.renewAtMs();
    }

    /**
     * Sends a move and records it. The command is handed to the client without waiting for the move underway to
     * finish: the device replaces the running move, and because the command is issued for the position the slider
     * has reached by then, the slider keeps moving (at the corrected speed when there is one).
     */
    private void sendMove(long now, MovePlan plan)
    {
        double position = sliderPosition(now);
        double durationMs = plan.isStop() ? 50 : Math.round(plan.durationMs());
        if (!plan.isStop())
        {
            durationMs = Math.clamp(durationMs, 1, HDSP_MAX_MOVE_DURATION_MS);
        }
        sendHdspMove(plan.target(), (int) durationMs, plan.isStop());
        move = new SliderMove(now, position, plan.towardsTop(), plan.target(), (long) durationMs, plan.isStop());
        lastCommandSentMs = now;
        log.debug("[HDSP-SMOOTHED] Move to {}% in {}ms ({}%/s, slider at {}%, input {}%, {} values/s)",
                percent(plan.target()), (long) durationMs, percentPerSecond(plan.speedPerSecond()), percent(position),
                percent(inputPosition()), lastMeasuredInputRatePerSecond);
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
        int direction = inputDirection();
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
            if (lastPeakDecayMs != 0 && now > lastPeakDecayMs)
            {
                peakSpeed *= Math.exp(-(now - lastPeakDecayMs) / (double) PEAK_SPEED_DECAY_MS);
            }
            lastPeakDecayMs = now;
            peakSpeed = Math.max(speed, peakSpeed);
            return;
        }
        if (belowStopSpeedSinceMs == 0)
        {
            belowStopSpeedSinceMs = now;
        }
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
        long now = nowMs();
        List<InputSample> window;
        synchronized (samples)
        {
            window = new ArrayList<>(samples);
        }
        InputSample first = null;
        InputSample last = null;
        for (InputSample sample : window)
        {
            if (now - sample.t() > INPUT_MOVEMENT_WINDOW_MS)
            {
                continue;
            }
            if (first == null)
            {
                first = sample;
            }
            last = sample;
        }
        if (first == null || first == last || last.t() == first.t())
        {
            return 0;
        }
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
        return Math.max(relativeThreshold, MIN_INPUT_SPEED_PERCENT_PER_SECOND / 100.0);
    }

    /** @return position the slider is expected to be at right now */
    private double sliderPosition(long now)
    {
        if (move != null)
        {
            return move.positionAt(now);
        }
        // Nothing was sent yet, so the slider is wherever the last movement left it; the input is the only
        // information about that position (the slider follows the input while a movement lasts).
        return inputPosition();
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
     * as the movement of the raw values over the speed window. 0 when there is not enough input to measure it,
     * which also keeps the first value of a movement from starting a move.
     */
    private double inputVelocity()
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
        double spanMs = window.getLast().t() - window.getFirst().t();
        if (spanMs < MIN_SPEED_MEASURE_WINDOW_MS)
        {
            return 0;
        }
        InputSample newest = window.getLast();
        double speed = (newest.x() - window.getFirst().x()) / (spanMs / 1000.0);
        if (window.size() >= 3)
        {
            // The average of the window is behind the movement while the user accelerates or decelerates, which is
            // what a stroke is made of, so the newest values are given a part of the weight as well. The plain
            // newest difference alone would follow the quantization of the avatar parameter instead.
            InputSample previous = window.get(window.size() - 2);
            double newestSpanMs = newest.t() - previous.t();
            if (newestSpanMs > 0)
            {
                double newestSpeed = (newest.x() - previous.x()) / (newestSpanMs / 1000.0);
                speed = speed * (1 - NEWEST_SPEED_WEIGHT) + newestSpeed * NEWEST_SPEED_WEIGHT;
            }
        }
        return Math.clamp(speed, -MAX_INPUT_SPEED, MAX_INPUT_SPEED);
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

    /** The move the slider should perform: towards an endpoint, or a stop at the position it has reached. */
    private record MovePlan(boolean towardsTop, double target, double distance, double speedPerSecond)
    {
        /** A plan without speed or distance is a stop: the slider is told to stay where it is. */
        boolean isStop()
        {
            return speedPerSecond <= 0 || distance <= 0;
        }

        /** Time the move takes. The minimum shortens a move that would otherwise be too small to be visible. */
        double durationMs()
        {
            if (isStop())
            {
                return 0;
            }
            return Math.min(Math.max(distance / speedPerSecond * 1000.0, MIN_MOVE_DURATION_MS),
                    HDSP_MAX_MOVE_DURATION_MS);
        }

        /**
         * @return speed the move is executed at. It differs from {@link #speedPerSecond()} when a move is longer
         * than the maximum duration: the endpoint cannot be reached within one command then, and the rest of the
         * distance is covered by the next command.
         */
        double executedSpeedPerSecond()
        {
            return isStop() ? 0 : distance / (durationMs() / 1000.0);
        }
    }

    /** The move the device is executing: from the position at the send time towards the target in durationMs. */
    private record SliderMove(long sentMs, double startX, boolean towardsTop, double target, long durationMs,
                              boolean stop)
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
            return durationMs <= 0 || stop ? 0 : Math.abs(target - startX) / (durationMs / 1000.0);
        }

        /** App time at which the following move has to be queued to keep the slider moving. */
        long renewAtMs()
        {
            return sentMs + Math.min(Math.round(durationMs * MOVE_RENEWAL_RATIO), MOVE_RENEWAL_INTERVAL_MS);
        }
    }
}
