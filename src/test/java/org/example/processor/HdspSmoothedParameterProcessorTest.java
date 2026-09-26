package org.example.processor;

import handy.model.DeviceModeValue;
import org.example.config.ConfigProperties;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.MessageDelayStats;
import org.example.handy.common.dto.*;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.DoubleUnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Tests of the HDSP_SMOOTHED algorithm. The processor is driven with a virtual clock, so a whole movement can be
 * replayed in a test without waiting for it, and the recorded commands show both what was sent to the device and
 * (through the command duration) with which speed.
 */
class HdspSmoothedParameterProcessorTest
{
    /** Input update interval that the app actually sees (around 20 values per second). */
    private static final long INPUT_INTERVAL_MS = 50;

    @Test
    void firstValueDoesNotStartAMove()
    {
        var harness = new Harness();

        harness.feed(0.5f);
        harness.advance(100);

        assertThat(harness.client.calls).isEmpty();
    }

    @Test
    void inputValuesAreMappedToFullyPenetratedAtValue()
    {
        var config = ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .minimalValueChange(0)
                .fullyPenetratedAtValue(50f)
                .build();
        var harness = new Harness(config);

        harness.feed(0.75f); // Received penetration 25%, which counts as 50% of the stroke

        assertThat(harness.processor.sliderPositionForTest(harness.virtualTimeMs())).isCloseTo(0.5, within(0.001));
    }

    @Test
    void sliderFollowsTheInputThroughAStroke()
    {
        var harness = new Harness();
        // 0 -> 100% -> 0 with a peak speed of 100%/s
        DoubleUnaryOperator input = t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 2000.0);

        harness.run(2000, input);

        assertThat(harness.client.calls).isNotEmpty();
        for (RecordedCall call : harness.movingCalls())
        {
            // Every command heads for an endpoint of the stroke (never for a position in between)
            assertThat(call.xp()).isIn(0f, 1f);
        }
        // The slider has to be where the input is (within the divergence the algorithm allows) all the time, so
        // the movement the user makes is the movement the device makes. The start of the first movement is
        // skipped: it takes a few values until the speed measurement is filled.
        for (long t = 600; t < 2000; t += 50)
        {
            double inputPosition = input.applyAsDouble(t);
            double sliderPosition = harness.sliderPositionAt(harness.startTimeMs() + t);
            assertThat(Math.abs(inputPosition - sliderPosition))
                    .as("slider tracking at t=%sms (input %s%%, slider %s%%)", t, inputPosition * 100,
                            sliderPosition * 100)
                    .isLessThan(0.2); // The configured divergence threshold, which is what triggers corrections
        }
    }

    @Test
    void fasterInputCorrectsTheSpeedWithoutWaitingForTheEndpoint()
    {
        var harness = new Harness();
        // 0 -> 100% at 50%/s for a second, then the same distance at 100%/s
        DoubleUnaryOperator input = t -> t < 1000 ? 0.5 * t / 1000 : 0.5 + (t - 1000) / 1000.0;

        harness.run(1600, input);

        long speedChangeAt = 1000;
        List<RecordedCall> moves = harness.movingCalls();
        assertThat(moves).hasSizeGreaterThanOrEqualTo(2);
        double slower = moves.getFirst().speedPercentPerSecond();
        RecordedCall corrected = moves.stream()
                .filter(call -> call.at() > speedChangeAt + harness.startTimeMs() - harness.startTimeMs()
                        && call.speedPercentPerSecond() > slower)
                .findFirst().orElseThrow();

        assertThat(slower).isCloseTo(50, org.assertj.core.data.Offset.offset(15.0));
        assertThat(corrected.speedPercentPerSecond()).isGreaterThan(70);
        assertThat(corrected.at() - harness.startTimeMs()).isLessThanOrEqualTo(speedChangeAt + 300);
    }

    @Test
    void inputStopsAreFollowedByStopAtTheInputPosition()
    {
        var harness = new Harness();
        harness.run(600, t -> 0.8 * t / 600.0); // 0 -> 80% at 80%/s
        double positionWhenStopped = harness.lastInputPosition();

        long stoppedAt = harness.virtualTimeMs();
        harness.feedUntil(stoppedAt + 500, t -> positionWhenStopped); // the value stays where it was

        RecordedCall stop = harness.client.calls.getLast();
        assertThat(stop.t()).isZero();
        assertThat(stop.at()).isLessThanOrEqualTo(stoppedAt + 250);
        assertThat(stop.xp()).isCloseTo((float) positionWhenStopped, org.assertj.core.data.Offset.offset(0.05f));
        // No command flood while the input stays stopped
        assertThat(harness.client.calls.stream().filter(call -> call.at() > stop.at()).count()).isZero();
    }

    @Test
    void movementResumesFromTheStopPosition()
    {
        var harness = new Harness();
        harness.run(600, t -> 0.8 * t / 600.0);
        double positionWhenStopped = harness.lastInputPosition();
        long stoppedAt = harness.virtualTimeMs();
        harness.feedUntil(stoppedAt + 300, t -> positionWhenStopped);

        // The user starts moving again, in the opposite direction
        long resumedAt = harness.virtualTimeMs();
        double resumeFrom = harness.lastInputPosition();
        long resumedAfterMs = resumedAt - harness.startTimeMs();
        harness.feedUntil(resumedAt + 400, t -> Math.max(0, resumeFrom - (t - resumedAfterMs) / 1000.0));

        // The slider was stopped by a command without duration, and it starts moving again after the input did
        RecordedCall stop = harness.client.calls.stream()
                .filter(call -> call.t() == 0 && call.at() > stoppedAt).findFirst().orElseThrow();
        RecordedCall firstMoveAfterStop = harness.client.calls.stream()
                .filter(call -> call.at() > stop.at() && call.t() > 0).findFirst().orElseThrow();
        assertThat(firstMoveAfterStop.xp()).isEqualTo(0f); // The input moves towards the bottom now
        assertThat(firstMoveAfterStop.at()).isLessThan(resumedAt + 400);
        // And it follows the input down from the position it was stopped at
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs()))
                .isLessThan((double) stop.xp());
    }

    @Test
    void commandsNeverLeaveTheStroke()
    {
        var harness = new Harness();
        harness.run(3000, t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 1500.0));

        assertThat(harness.client.calls).allSatisfy(call ->
        {
            assertThat(call.xp()).isBetween(0f, 1f);
            assertThat(call.t()).isBetween(0, HdspSmoothedParameterProcessor.HDSP_MAX_MOVE_DURATION_MS);
        });
    }

    @Test
    void commandsAreNotSentMoreOftenThanTheMinimumInterval()
    {
        var harness = new Harness();
        harness.run(3000, t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 1500.0));

        List<RecordedCall> calls = harness.client.calls;
        assertThat(calls.size()).isGreaterThan(2);
        for (int i = 1; i < calls.size(); i++)
        {
            assertThat(calls.get(i).at() - calls.get(i - 1).at())
                    .isGreaterThanOrEqualTo(HdspSmoothedParameterProcessor.HDSP_MIN_SEND_INTERVAL_MS);
        }
    }

    @Test
    void slowMovementIsDeliveredAsAChainOfShortMoves()
    {
        var harness = new Harness();
        // 40%/s: the end of the stroke is more than one maximum duration away, so the movement has to be
        // delivered as a chain of moves that all head for the same endpoint
        harness.run(4000, t -> 0.1 + 0.4 * t / 1000.0);

        List<RecordedCall> moves = harness.movingCalls();
        assertThat(moves.size()).isGreaterThan(2);
        for (RecordedCall call : moves)
        {
            assertThat(call.xp()).isEqualTo(1f); // the input only moves towards the top
            assertThat(call.t()).isLessThanOrEqualTo(HdspSmoothedParameterProcessor.HDSP_MAX_MOVE_DURATION_MS);
        }
        // The moves that cover the movement (not the short ones that end it) are at the input speed
        assertThat(moves.stream().filter(call -> call.t() > 200).mapToDouble(RecordedCall::speedPercentPerSecond))
                .allSatisfy(speed -> assertThat(speed).isBetween(20.0, 70.0));
    }

    @Test
    void aPositionThatDoesNotChangeCannotDriveTheSlider()
    {
        var harness = new Harness();
        harness.feed(0.3f);
        harness.advance(1000);
        long stoppedAt = harness.virtualTimeMs();
        harness.feedUntil(stoppedAt + 1000, t -> 0.3);

        assertThat(harness.client.calls).isEmpty();
    }

    @Test
    void directionReversalChangesTheEndpoint()
    {
        var harness = new Harness();
        long upForMs = 800;
        harness.run(upForMs, t -> 0.05 + 0.0002 * t); // up at 20%/s
        double top = harness.lastInputPosition();
        harness.feedUntil(harness.virtualTimeMs() + 600, t -> top - 0.0002 * (t - upForMs));

        List<Float> endpoints = harness.movingCalls().stream().map(RecordedCall::xp).toList();
        assertThat(endpoints).isNotEmpty();
        assertThat(endpoints.getFirst()).isEqualTo(1f); // It starts by moving up...
        assertThat(endpoints.getLast()).isEqualTo(0f); // ...and heads down after the reversal
    }

    @Test
    void aSlowStrokeIsFollowedWithoutOvershooting()
    {
        var harness = new Harness();
        // 30%/s: slower than the maximum move duration can cover in one command
        harness.run(3000, t -> 0.05 + 0.3 * t / 1000.0);

        for (long t = 600; t < 3000; t += 100)
        {
            double inputPosition = 0.05 + 0.3 * t / 1000.0;
            double sliderPosition = harness.sliderPositionAt(harness.startTimeMs() + t);
            // The slider follows the movement: it does not run away from the input (a small lead is the look
            // ahead the movement is planned with, a lag is bounded by the divergence threshold)
            assertThat(sliderPosition - inputPosition)
                    .as("slider lead at t=%sms", t)
                    .isLessThan(0.12);
            assertThat(inputPosition - sliderPosition)
                    .as("slider lag at t=%sms", t)
                    .isLessThan(0.25);
            // The lead stays bounded by the look ahead while the input is away from the endpoint, so the slider
            // cannot arrive at the endpoint long before the input does
            if (inputPosition < 0.8)
            {
                assertThat(sliderPosition).as("slider at t=%sms", t).isLessThan(inputPosition + 0.12);
            }
        }
        assertThat(harness.movingCalls()).allSatisfy(call -> assertThat(call.xp()).isEqualTo(1f));
    }

    @Test
    void anInputThatStaysAtAnEndpointDoesNotFloodTheDevice()
    {
        var harness = new Harness();
        harness.run(1200, t -> Math.min(1.0, 1.0 * t / 1000.0)); // reaches the top at ~1000ms
        int callsWhenArrived = harness.client.calls.size();

        harness.advance(1000); // the input keeps reporting the top

        assertThat(harness.client.calls).hasSize(callsWhenArrived);
    }

    @Test
    void thresholdsFromConfigAreUsed()
    {
        var harness = new Harness(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .hdspSpeedChangeThresholdPercent(50)
                .hdspSpeedStopThresholdPercent(20)
                .hdspDivergenceThresholdPercent(30)
                .hdspSpeedMeasureWindowMs(100)
                .build());
        harness.run(1500, t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 1500.0));

        assertThat(harness.client.calls).isNotEmpty();
    }

    /** Derivative of the input function (per millisecond). */
    private static double derivative(DoubleUnaryOperator input, long t)
    {
        return (input.applyAsDouble(t + 1) - input.applyAsDouble(t - 1)) / 2.0;
    }

    /** HDSP command as it was sent, with the virtual time it was sent at. */
    private record RecordedCall(float xp, int t, long at, double startX)
    {
        RecordedCall(float xp, int t, long at)
        {
            this(xp, t, at, 0);
        }

        /** Speed the slider moves with while executing this command, in percent of the stroke per second. */
        double speedPercentPerSecond()
        {
            return t <= 0 ? 0 : Math.abs(xp - startX) / (t / 1000.0) * 100;
        }

        boolean isMove()
        {
            return t > 0;
        }
    }

    /**
     * Runs the processor on a virtual clock, feeds it input values and records the commands. The virtual time only
     * moves when {@link #advance} is called; the processor loop is driven by hand, so a test sees exactly one loop
     * iteration per millisecond of virtual time.
     */
    private static class Harness
    {
        final RecordingHandyClient client;
        final HdspSmoothedParameterProcessor processor;
        private static final double startPosition = 0.5;
        private final long startTimeMs = 1_000_000;
        private long virtualTimeMs = startTimeMs;
        private double lastInputPosition;
        private long lastInputSentMs;

        Harness()
        {
            this(ConfigProperties.builder().spsType(SpsType.PENETRATOR).minimalValueChange(0).build());
        }

        Harness(ConfigProperties config)
        {
            this.client = new RecordingHandyClient(this);
            this.lastInputSentMs = virtualTimeMs; // The first value is fed on the first tick
            this.processor = new HdspSmoothedParameterProcessor(client, config)
            {
                @Override
                protected long nowMs()
                {
                    return virtualTimeMs;
                }

                @Override
                protected void sleepSafe(long sleepMs)
                {
                    // The loop is driven by the test, not by sleeping
                }
            };
            processor.setValueChangeListener(value -> {});
        }

        long virtualTimeMs()
        {
            return virtualTimeMs;
        }

        long startTimeMs()
        {
            return startTimeMs;
        }

        /** Feeds one input value (average of the interval that just passed, like a 20 values/s stream). */
        void feed(float position)
        {
            lastInputPosition = position;
            processor.actOnValueChange((float) (1.0 - position));
        }

        void tick()
        {
            processor.trySendingMessage(0);
        }



        void advance(long durationMs)
        {
            for (long i = 0; i < durationMs / INPUT_INTERVAL_MS; i++)
            {
                virtualTimeMs += INPUT_INTERVAL_MS;
                virtualTimeMs++;
                if (virtualTimeMs - lastInputSentMs >= INPUT_INTERVAL_MS)
                {
                    lastInputSentMs = virtualTimeMs;
                    feed((float) lastInputPosition); // unchanged input: still shows that the user is not moving
                }
                tick();
            }
        }

        /** Runs the input function for the given duration, feeding a value every {@link #INPUT_INTERVAL_MS}. */
        void run(long durationMs, DoubleUnaryOperator input)
        {
            feedUntil(virtualTimeMs + durationMs, input);
        }

        /**
         * Feeds the values of the input function until the virtual time reaches the given timestamp. The function
         * is called with the time since the start of the test (not with the virtual time).
         */
        void feedUntil(long endTimeMs, DoubleUnaryOperator input)
        {
            while (virtualTimeMs < endTimeMs)
            {
                virtualTimeMs += INPUT_INTERVAL_MS;
                if (virtualTimeMs - lastInputSentMs >= INPUT_INTERVAL_MS)
                {
                    lastInputSentMs = virtualTimeMs;
                    feed((float) input.applyAsDouble(virtualTimeMs - startTimeMs));
                }
                tick();
            }
        }

        double lastInputPosition()
        {
            return lastInputPosition;
        }

        List<RecordedCall> movingCalls()
        {
            return client.calls.stream().filter(RecordedCall::isMove).toList();
        }

        /**
         * Position the slider is expected to have at the given virtual time, as the processor models it (each
         * command is interpolated from the position the slider had when it was sent, which is what the device
         * does with the command as well).
         */
        double sliderPositionAt(long atTimeMs)
        {
            RecordedCall current = null;
            for (RecordedCall call : client.calls)
            {
                if (call.at() <= atTimeMs)
                {
                    current = call;
                }
            }
            if (current == null)
            {
                return startPosition;
            }
            if (current.t() <= 0)
            {
                return current.xp();
            }
            double progress = Math.clamp((atTimeMs - current.at()) / (double) current.t(), 0.0, 1.0);
            return current.startX() + (current.xp() - current.startX()) * progress;
        }

    }

    /** Minimal HandyClient that records the HDSP commands instead of talking to a device. */
    private static class RecordingHandyClient extends HandyClient
    {
        final List<RecordedCall> calls = new ArrayList<>();
        private final Harness harness;

        RecordingHandyClient(Harness harness)
        {
            this.harness = harness;
        }

        @Override
        public HandyBaseResponseWithError changeMode(DeviceModeValue mode)
        {
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public boolean checkConnectionStatus()
        {
            return true;
        }

        @Override
        public HandySetupResponse hspSetup()
        {
            return new HandySetupResponse(null, new HandySetupResult(0));
        }

        @Override
        public HandyBaseResponseWithError hspFlush()
        {
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving)
        {
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public HandyHspAddResponse hspAdd(HspAddRequest requestBody)
        {
            return new HandyHspAddResponse(null, null);
        }

        @Override
        public void setSliderSettings(Float min, Float max)
        {
        }

        @Override
        public Optional<SliderSettingsResult> getSliderSettings()
        {
            return Optional.empty();
        }

        @Override
        public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
        {
            double startX = harness.processor.sliderPositionForTest(harness.virtualTimeMs());
            calls.add(new RecordedCall(xp, t, harness.virtualTimeMs(), startX));
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public void sendRequestForMessageDelayCalc()
        {
        }

        @Override
        public MessageDelayStats calculateMessageDelayStats()
        {
            return new MessageDelayStats(0, 0);
        }
    }
}
