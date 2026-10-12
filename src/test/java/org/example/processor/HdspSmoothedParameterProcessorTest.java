package org.example.processor;

import org.example.config.ConfigProperties;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;
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
    /** Frame interval of VRChat (it evaluates the avatar parameters once per frame, at about 45 frames per second). */
    private static final long VRCHAT_FRAME_MS = 22;
    /** Interval of the processor loop (it sleeps a quarter of the minimum send interval). */
    private static final long TICK_MS = HdspSmoothedParameterProcessor.HDSP_MIN_SEND_INTERVAL_MS / 4;
    /** Length (in meters) of the penetrator simulated in ORIFICE mode, used to derive its tip proximity. */
    private static final float SIMULATED_PENETRATOR_LENGTH = 0.1f;

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
    void reverseMovementMovesTheSliderUpWhenPenetrating()
    {
        var config = ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .minimalValueChange(0)
                .reverseMovement(true)
                .build();
        var harness = new Harness(config);

        harness.feed(0.75f); // Received penetration 25%, which is 25% from the bottom of the stroke when reversed

        assertThat(harness.processor.sliderPositionForTest(harness.virtualTimeMs())).isCloseTo(0.25, within(0.001));
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
        harness.run(1000, t -> 0.8 * t / 1000.0); // 0 -> 80% at 80%/s
        double positionWhenStopped = harness.lastInputPosition();

        long stoppedAt = harness.virtualTimeMs();
        harness.feedUntil(stoppedAt + 500, t -> positionWhenStopped); // the value stays where it was

        RecordedCall stop = harness.client.calls.getLast();
        assertThat(stop.stop()).isTrue();
        assertThat(stop.at()).isLessThanOrEqualTo(stoppedAt + 250);
        // Stopped at the input, or a bit past it when the stop was only noticed with the next value and the stop
        // is placed a little further along for the command delay (the slider is not sent back for that, it would
        // be a visible bounce)
        assertThat(stop.xp()).isBetween((float) positionWhenStopped - 0.01f, (float) positionWhenStopped + 0.08f);
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

        // The slider was stopped by a stop command, and it starts moving again after the input did
        RecordedCall stop = harness.client.calls.stream()
                .filter(call -> call.stop() && call.at() > stoppedAt).findFirst().orElseThrow();
        RecordedCall firstMoveAfterStop = harness.client.calls.stream()
                .filter(call -> call.at() > stop.at() && call.isMove()).findFirst().orElseThrow();
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
                    .isLessThan(0.04);
            assertThat(inputPosition - sliderPosition)
                    .as("slider lag at t=%sms", t)
                    .isLessThan(0.25);
            // The lead stays bounded by the look ahead while the input is away from the endpoint, so the slider
            // cannot arrive at the endpoint long before the input does
            if (inputPosition < 0.8)
            {
                assertThat(sliderPosition).as("slider at t=%sms", t).isLessThan(inputPosition + 0.04);
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
    void orificeMovementDrivesTheSliderAsWell()
    {
        // In ORIFICE mode the movement arrives as the root/tip proximity of the penetrator, which does not go
        // through actOnValueChange: without the raw value the speed measurement stays empty and nothing is sent
        var harness = new Harness(ConfigProperties.builder()
                .spsType(SpsType.ORIFICE)
                .minimalValueChange(0)
                .build());

        harness.run(1500, t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 1500.0));

        assertThat(harness.movingCalls()).isNotEmpty();
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

    @Test
    void aSlowStrokeThatReversesDoesNotMakeTheSliderJumpBack()
    {
        var harness = new Harness();
        // 20%/s up for 1.5s (a move to the top would be longer than the old 2s cap), then back down
        DoubleUnaryOperator input = t -> t < 1500 ? 0.1 + 0.2 * t / 1000.0 : 0.4 - 0.2 * (t - 1500) / 1000.0;

        harness.run(1500, input);
        double sliderAtReversal = harness.sliderPositionAt(harness.virtualTimeMs());
        harness.feedUntil(harness.startTimeMs() + 2500, input);

        // The slider never got noticeably past the furthest position of the input...
        assertThat(sliderAtReversal).isLessThan(0.4 + 0.035);
        assertSliderNeverGoesPast(harness, 0.4 + 0.035, harness.client.calls.getFirst().at(), harness.virtualTimeMs());
        // ...and it follows the input down afterwards
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs())).isLessThan(0.3);
    }

    @Test
    void aSliderAheadOfTheInputIsStoppedWhereItIsNotSentBack()
    {
        var harness = new Harness();
        // Decelerating stroke (a slider following the speed of the window is a bit ahead), then a stop
        harness.run(1200, t -> 0.1 + 0.7 * Math.sin(Math.PI / 2 * Math.min(t, 1000) / 1000.0));
        harness.advance(500);

        for (RecordedCall call : harness.client.calls)
        {
            // Every command keeps the slider moving up (or stops it): there is no move back down
            assertThat(call.xp()).as("command at %sms", call.at() - harness.startTimeMs())
                    .isGreaterThanOrEqualTo((float) call.startX() - 0.005f);
        }
    }

    @Test
    void jitterOfASlowMovementDoesNotReverseTheSlider()
    {
        var harness = new Harness();
        var random = new java.util.Random(42);
        // 10%/s up with +-0.6% of hand jitter: within the movement window the jitter is larger than the movement
        double[] noise = new double[200];
        for (int i = 0; i < noise.length; i++)
        {
            noise[i] = (random.nextDouble() - 0.5) * 0.012;
        }
        harness.run(4000, t -> 0.1 + 0.1 * t / 1000.0 + noise[(int) (t / INPUT_INTERVAL_MS) % noise.length]);

        assertThat(harness.movingCalls()).isNotEmpty();
        assertThat(harness.movingCalls()).allSatisfy(call -> assertThat(call.xp()).isEqualTo(1f));
    }

    @Test
    void aSlowMovementAfterFastStrokesIsFollowed()
    {
        var harness = new Harness();
        // Two fast strokes (peak 300%/s), then a slow movement up at 20%/s, well below 15% of that peak
        harness.run(1000, t -> 0.5 - 0.5 * Math.cos(2 * Math.PI * t / 500.0));
        long slowFrom = harness.virtualTimeMs() - harness.startTimeMs();
        harness.run(3000, t -> 0.2 * (t - slowFrom) / 1000.0);

        double inputAtEnd = harness.lastInputPosition();
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs()))
                .isCloseTo(inputAtEnd, within(0.1));
    }

    @Test
    void anAbruptStopWhileValuesKeepComingDoesNotSendTheSliderBack()
    {
        var harness = new Harness();
        var random = new java.util.Random(7);
        // 150%/s up, then a sudden stop at 80%; the tracked hand still jitters, so values keep arriving (a fine
        // quantization, so that the jitter changes the value)
        harness.runVrchat(700, t -> Math.min(0.8, 0.1 + 1.5 * t / 1000.0), 0.001);
        long stopAt = harness.startTimeMs() + 467;
        harness.runVrchat(600, t -> 0.8 + (random.nextDouble() - 0.5) * 0.004, 0.001);

        assertNoMoveBack(harness, stopAt);
        // A stop of a movement this fast is noticed with the next frame, plus the command delay margin
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs())).isLessThan(0.8 + 0.09);
    }

    @Test
    void anAbruptStopAfterWhichNoValuesArriveIsNoticedWithinAnInputInterval()
    {
        var harness = new Harness();
        // 150%/s up, then a sudden stop at 80% after which VRChat sends nothing (the value does not change)
        harness.runVrchat(467, t -> 0.1 + 1.5 * t / 1000.0, 0.01);
        long lastValueAt = harness.virtualTimeMs();
        harness.idle(600);

        RecordedCall stop = harness.client.calls.stream()
                .filter(call -> call.stop() && call.at() > lastValueAt).findFirst().orElseThrow();
        // A value of a movement this fast is due every frame, so the stop is noticed within two frames
        assertThat(stop.at() - lastValueAt).isLessThanOrEqualTo(2 * VRCHAT_FRAME_MS + 10);
        assertNoMoveBack(harness, lastValueAt);
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs())).isLessThan(harness.lastInputPosition() + 0.09);
    }

    @Test
    void jitterOfASlowMovementDoesNotStopTheSlider()
    {
        var harness = new Harness();
        var random = new java.util.Random(42);
        double[] noise = new double[200];
        for (int i = 0; i < noise.length; i++)
        {
            noise[i] = (random.nextDouble() - 0.5) * 0.012;
        }
        // 10%/s up with +-0.6% of hand jitter
        harness.run(4000, t -> 0.1 + 0.1 * t / 1000.0 + noise[(int) (t / INPUT_INTERVAL_MS) % noise.length]);

        long firstMoveAt = harness.movingCalls().getFirst().at();
        // The slider keeps moving: it is not stopped (and restarted) every time the jitter outweighs the movement
        assertThat(harness.client.calls.stream().filter(call -> call.stop() && call.at() > firstMoveAt))
                .hasSizeLessThanOrEqualTo(1);
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs())).isCloseTo(0.5, within(0.05));
    }

    @Test
    void aStopIsNotUndoneByTheCommandLatency()
    {
        // Commands reach the device 20-80ms after they are sent (BLE round trips of 60-180ms were measured): a stop
        // that arrives later than the moves before it did finds the slider further along than planned
        var harness = new Harness(50, 30, 0);
        harness.run(900, t -> Math.min(0.8, 0.1 + 0.4 * t / 1000.0)); // 40%/s up to 80%, then the values stop
        harness.idle(800);

        assertNoMoveBack(harness, harness.client.calls.getFirst().at());
        assertThat(harness.client.calls.stream().filter(RecordedCall::stop)).isNotEmpty();
    }

    @Test
    void aSlowMovementWithSparseValuesIsFollowedWithoutStopping()
    {
        // 8%/s delivered the way VRChat does it: only changed values (1% steps), so one value every ~125ms
        var harness = new Harness(50, 30, 0);
        DoubleUnaryOperator input = t -> 0.2 + 0.08 * t / 1000.0;
        harness.runVrchat(5000, input, 0.01);

        long firstMoveAt = harness.movingCalls().getFirst().at();
        assertThat(harness.client.calls.stream().filter(call -> call.stop() && call.at() > firstMoveAt))
                .as("stops while the input keeps moving").isEmpty();
        assertNoMoveBack(harness, firstMoveAt);
        for (long t = firstMoveAt - harness.startTimeMs() + 500; t < 5000; t += 100)
        {
            assertThat(harness.sliderPositionAt(harness.startTimeMs() + t))
                    .as("slider at %sms", t).isCloseTo(input.applyAsDouble(t), within(0.03));
        }
    }

    @Test
    void sparseValuesOfAStoppedInputStopTheSliderWithoutAJump()
    {
        var harness = new Harness(50, 30, 0);
        // 15%/s for two seconds, then the hand stays (VRChat sends nothing more)
        harness.runVrchat(2000, t -> 0.2 + 0.15 * t / 1000.0, 0.01);
        double stoppedAt = harness.lastInputPosition();
        harness.idle(1000);

        assertNoMoveBack(harness, harness.client.calls.getFirst().at());
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs())).isCloseTo(stoppedAt, within(0.03));
    }

    @Test
    void aDeviceThatCannotMoveSlowlyIsNotRunAheadAndPulledBack()
    {
        // The device does not move slower than 8%/s (configured as the minimum slider speed, 8 mm/s of the 100 mm
        // stroke), and the input moves at 5%/s: the slider follows in steps, but it must never be pulled back
        var harness = new Harness(ConfigProperties.builder().spsType(SpsType.PENETRATOR).minimalValueChange(0)
                .hdspMinSliderSpeed(8).build(), 50, 30, 0.08);
        harness.runVrchat(6000, t -> 0.2 + 0.05 * t / 1000.0, 0.01);

        assertNoMoveBack(harness, harness.client.calls.getFirst().at());
        assertThat(harness.sliderPositionAt(harness.virtualTimeMs()))
                .isCloseTo(harness.lastInputPosition(), within(0.04));
    }

    @Test
    void aStopIsNotDelayedBySendMessageEveryMs()
    {
        // sendMessageEveryMs spaces out the corrections of a movement, but a stop that waits for it lets the
        // slider run on past the user
        var harness = new Harness(ConfigProperties.builder().spsType(SpsType.PENETRATOR).minimalValueChange(0)
                .hdspMinSliderSpeed(0).sendMessageEveryMs(50).build());
        harness.runVrchat(467, t -> 0.1 + 1.0 * t / 1000.0, 0.01);
        long lastValueAt = harness.virtualTimeMs();
        harness.idle(500);

        RecordedCall stop = harness.client.calls.stream()
                .filter(call -> call.stop() && call.at() > lastValueAt).findFirst().orElseThrow();
        // Noticed within two frames, and sent right away (not up to 50ms later)
        assertThat(stop.at() - lastValueAt).isLessThanOrEqualTo(2 * VRCHAT_FRAME_MS + 10);
    }

    @Test
    void theFirstMoveStartsWhereTheDeviceReportsTheSlider()
    {
        // The slider rests at the bottom while the input starts at 60%: the moves have to be planned from where
        // the slider is, or the first stop is a jump to where it was wrongly believed to be
        var harness = new Harness(Harness.defaultConfig(), 0, 0, 0, 0.0);
        harness.runVrchat(1500, t -> 0.6 + 0.3 * t / 1000.0, 0.01);

        assertThat(harness.client.calls).isNotEmpty();
        assertThat(harness.client.calls.getFirst().startX()).isCloseTo(0.0, within(0.01));
    }

    @Test
    void aSessionOfSlowStrokesAndPausesNeverPullsTheSliderBack()
    {
        // Slow strokes and pauses like the ones of a logged session, delivered the way VRChat does it (changed
        // values only, 1% steps, a jittering hand) to a device that gets the commands 20-80ms after they are sent:
        // {speed %/s, duration ms}, a speed of 0 is a pause
        double[][] profile = {{10, 1500}, {0, 600}, {15, 1000}, {0, 500}, {-20, 1500}, {0, 700}, {-8, 1500},
                {0, 400}, {30, 800}, {0, 500}, {-12, 1200}, {6, 1500}, {0, 800}, {25, 900}};
        var harness = new Harness(Harness.defaultConfig(), 50, 30, 0);
        var random = new java.util.Random(1);
        double[] jitter = new double[2000];
        for (int i = 0; i < jitter.length; i++)
        {
            jitter[i] = (random.nextDouble() - 0.5) * 0.006;
        }
        long duration = 0;
        for (double[] segment : profile)
        {
            duration += (long) segment[1];
        }
        harness.runVrchat(duration + 500,
                t -> profilePosition(profile, (long) t) + jitter[(int) (t / VRCHAT_FRAME_MS) % jitter.length], 0.01);

        // Backward is against the direction the input moves in (kept through a pause). Right after the input
        // reversed the slider is still finishing the old direction, which is lag, not a jump back.
        double previousDirection = 0;
        long reversedAt = 0;
        double furthest = Double.NaN;
        double previous = Double.NaN;
        for (long t = harness.client.calls.getFirst().at(); t < harness.virtualTimeMs(); t += TICK_MS)
        {
            double direction = profileDirection(profile, t - harness.startTimeMs(), previousDirection);
            double position = harness.sliderPositionAt(t);
            if (direction != previousDirection)
            {
                previousDirection = direction;
                reversedAt = t;
            }
            if (t - reversedAt <= 700 || (position - furthest) * direction > 0)
            {
                furthest = position;
            }
            assertThat((furthest - position) * direction).as("slider moved back at %sms", t - harness.startTimeMs())
                    .isLessThan(0.015);
            if (!Double.isNaN(previous))
            {
                // The fastest stroke is 30%/s: catching up after a start may be faster, but not a sprint
                assertThat(Math.abs(position - previous) / (TICK_MS / 1000.0))
                        .as("slider speed at %sms", t - harness.startTimeMs()).isLessThan(0.75);
            }
            previous = position;
        }
        // 6 pauses and the end of the input: a stop for each, and a few more for the jittering hand at most
        assertThat(harness.client.calls.stream().filter(RecordedCall::stop)).hasSizeLessThanOrEqualTo(10);
    }

    @Test
    void aSessionOfSlowStrokesOnAHandyNeverPullsTheSliderBack()
    {
        // The same kind of session on a device that does not move slower than 30%/s (a Handy): the strokes slower
        // than that are followed in steps, which must not be pulled back
        double[][] profile = {{10, 1500}, {0, 600}, {15, 1000}, {0, 500}, {-20, 1500}, {0, 700}, {-8, 1500},
                {0, 400}, {30, 800}, {0, 500}, {-12, 1200}, {6, 1500}, {0, 800}, {25, 900}};
        var harness = new Harness(Harness.handyConfig(), 50, 30, 0.3);
        var random = new java.util.Random(1);
        double[] jitter = new double[2000];
        for (int i = 0; i < jitter.length; i++)
        {
            jitter[i] = (random.nextDouble() - 0.5) * 0.006;
        }
        long duration = 0;
        for (double[] segment : profile)
        {
            duration += (long) segment[1];
        }
        harness.runVrchat(duration + 500,
                t -> profilePosition(profile, (long) t) + jitter[(int) (t / VRCHAT_FRAME_MS) % jitter.length], 0.01);

        double previousDirection = 0;
        long reversedAt = 0;
        double furthest = Double.NaN;
        for (long t = harness.client.calls.getFirst().at(); t < harness.virtualTimeMs(); t += TICK_MS)
        {
            long sinceStart = t - harness.startTimeMs();
            double direction = profileDirection(profile, sinceStart, previousDirection);
            double position = harness.sliderPositionAt(t);
            if (direction != previousDirection)
            {
                previousDirection = direction;
                reversedAt = t;
            }
            if (t - reversedAt <= 700 || (position - furthest) * direction > 0)
            {
                furthest = position;
                continue;
            }
            assertThat((furthest - position) * direction).as("slider moved back at %sms", sinceStart)
                    .isLessThan(0.015);
        }
    }

    private static double profilePosition(double[][] profile, long t)
    {
        double position = 0.3;
        long segmentStart = 0;
        for (double[] segment : profile)
        {
            long segmentDuration = (long) segment[1];
            long elapsed = Math.min(t - segmentStart, segmentDuration);
            position += segment[0] / 100 * elapsed / 1000.0;
            if (t < segmentStart + segmentDuration)
            {
                break;
            }
            segmentStart += segmentDuration;
        }
        return position;
    }

    private static double profileDirection(double[][] profile, long t, double previousDirection)
    {
        long segmentStart = 0;
        for (double[] segment : profile)
        {
            if (t < segmentStart + (long) segment[1])
            {
                return segment[0] == 0 ? previousDirection : Math.signum(segment[0]);
            }
            segmentStart += (long) segment[1];
        }
        return previousDirection;
    }

    /** Asserts that the slider never moves against the direction of the movement after the given time. */
    private static void assertNoMoveBack(Harness harness, long fromMs)
    {
        double highest = harness.sliderPositionAt(fromMs);
        for (long t = fromMs; t <= harness.virtualTimeMs(); t += TICK_MS)
        {
            double position = harness.sliderPositionAt(t);
            assertThat(position).as("slider at %sms", t - harness.startTimeMs()).isGreaterThan(highest - 0.005);
            highest = Math.max(highest, position);
        }
    }

    private static void assertSliderNeverGoesPast(Harness harness, double max, long fromMs, long toMs)
    {
        for (long t = fromMs; t <= toMs; t += TICK_MS)
        {
            assertThat(harness.sliderPositionAt(t)).as("slider at %sms", t - harness.startTimeMs()).isLessThan(max);
        }
    }

    /** Derivative of the input function (per millisecond). */
    private static double derivative(DoubleUnaryOperator input, long t)
    {
        return (input.applyAsDouble(t + 1) - input.applyAsDouble(t - 1)) / 2.0;
    }

    /** HDSP command as it was sent, with the virtual time it was sent at. */
    private record RecordedCall(float xp, int t, long at, double startX, boolean stop)
    {
        /** Speed the slider moves with while executing this command, in percent of the stroke per second. */
        double speedPercentPerSecond()
        {
            return t <= 0 ? 0 : Math.abs(xp - startX) / (t / 1000.0) * 100;
        }

        boolean isMove()
        {
            return !stop && t > 0;
        }
    }

    /**
     * Runs the processor on a virtual clock, feeds it input values and records the commands. The virtual time only
     * moves when {@link #advance} is called; the processor loop is driven by hand, one loop iteration every
     * {@link #TICK_MS} of virtual time (like the real loop).
     */
    private static class Harness
    {
        final RecordingHandyClient client;
        final HdspSmoothedParameterProcessor processor;
        private static final double startPosition = 0.5;
        private final long startTimeMs = 1_000_000;
        private final boolean orifice;
        private long virtualTimeMs = startTimeMs;
        private double lastInputPosition;
        private long lastInputSentMs;
        /** Time a command takes to reach the simulated device. */
        private final long commandLatencyMs;
        /** Variation of {@link #commandLatencyMs} (each command is delayed by the latency +- up to this much). */
        private final long commandLatencyJitterMs;
        /** Slowest speed (stroke units per second) the simulated device moves with, 0 for none. */
        private final double deviceMinSpeed;
        /** Last value sent by {@link #runVrchat} (VRChat only sends values that changed). */
        private double lastVrchatValue = Double.NaN;

        Harness()
        {
            this(defaultConfig());
        }

        Harness(ConfigProperties config)
        {
            this(config, 0, 0, 0);
        }

        /** Position the simulated device reports before the first command (NaN: it cannot be read). */
        private final double initialDevicePosition;

        Harness(ConfigProperties config, long commandLatencyMs, double deviceMinSpeed)
        {
            this(config, commandLatencyMs, 0, deviceMinSpeed);
        }

        Harness(long commandLatencyMs, long commandLatencyJitterMs, double deviceMinSpeed)
        {
            this(defaultConfig(), commandLatencyMs, commandLatencyJitterMs, deviceMinSpeed);
        }

        Harness(ConfigProperties config, long commandLatencyMs, long commandLatencyJitterMs, double deviceMinSpeed)
        {
            this(config, commandLatencyMs, commandLatencyJitterMs, deviceMinSpeed, Double.NaN);
        }

        Harness(ConfigProperties config, long commandLatencyMs, long commandLatencyJitterMs, double deviceMinSpeed,
                double initialDevicePosition)
        {
            this.initialDevicePosition = initialDevicePosition;
            this.commandLatencyMs = commandLatencyMs;
            this.commandLatencyJitterMs = commandLatencyJitterMs;
            this.deviceMinSpeed = deviceMinSpeed;
            this.client = new RecordingHandyClient(this);
            this.lastInputSentMs = virtualTimeMs; // The first value is fed on the first tick
            this.orifice = config.spsType() == SpsType.ORIFICE;
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

        /** Config for an ideal device: one that moves as slowly as it is told, so no minimum slider speed. */
        static ConfigProperties defaultConfig()
        {
            return ConfigProperties.builder().spsType(SpsType.PENETRATOR).minimalValueChange(0).hdspMinSliderSpeed(0)
                    .build();
        }

        /** Config with the defaults a real Handy is used with (its minimum speed of ~30 mm/s). */
        static ConfigProperties handyConfig()
        {
            return ConfigProperties.builder().spsType(SpsType.PENETRATOR).minimalValueChange(0).build();
        }

        long virtualTimeMs()
        {
            return virtualTimeMs;
        }

        long startTimeMs()
        {
            return startTimeMs;
        }

        /**
         * Feeds one input value (average of the interval that just passed, like a 20 values/s stream). In ORIFICE
         * mode the value is the insertion amount, which is turned into the root/tip proximity of a simulated
         * penetrator the way the test data source does it.
         */
        void feed(float position)
        {
            lastInputPosition = position;
            if (orifice)
            {
                float rootProximity = 1.f - (1.f - position) * SIMULATED_PENETRATOR_LENGTH;
                processor.actOnProximityChange(rootProximity, rootProximity + SIMULATED_PENETRATOR_LENGTH);
                return;
            }
            processor.actOnValueChange((float) (1.0 - position));
        }

        void tick()
        {
            processor.trySendingMessage(0);
        }



        void advance(long durationMs)
        {
            long endTimeMs = virtualTimeMs + durationMs;
            while (virtualTimeMs < endTimeMs)
            {
                virtualTimeMs += TICK_MS;
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
                virtualTimeMs += TICK_MS;
                if (virtualTimeMs - lastInputSentMs >= INPUT_INTERVAL_MS)
                {
                    lastInputSentMs = virtualTimeMs;
                    feed((float) input.applyAsDouble(virtualTimeMs - startTimeMs));
                }
                tick();
            }
        }

        /**
         * Feeds the input function the way VRChat delivers it: the value is sampled every frame, quantized to the
         * given step, and only sent when it changed. The update rate is therefore proportional to the speed (a
         * slow movement arrives as a few values per second), which is what the real input looks like.
         */
        void runVrchat(long durationMs, DoubleUnaryOperator input, double step)
        {
            long endTimeMs = virtualTimeMs + durationMs;
            long nextFrameMs = virtualTimeMs;
            while (virtualTimeMs < endTimeMs)
            {
                virtualTimeMs += TICK_MS;
                if (virtualTimeMs >= nextFrameMs)
                {
                    nextFrameMs += VRCHAT_FRAME_MS;
                    double value = Math.round(input.applyAsDouble(virtualTimeMs - startTimeMs) / step) * step;
                    if (value != lastVrchatValue)
                    {
                        lastVrchatValue = value;
                        lastInputSentMs = virtualTimeMs;
                        feed((float) value);
                    }
                }
                tick();
            }
        }

        /** Lets the virtual time pass without any input value arriving (VRChat only sends values that changed). */
        void idle(long durationMs)
        {
            long endTimeMs = virtualTimeMs + durationMs;
            while (virtualTimeMs < endTimeMs)
            {
                virtualTimeMs += TICK_MS;
                tick();
            }
            lastInputSentMs = virtualTimeMs;
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
         * Position the simulated device has at the given virtual time. The device is simulated independently of
         * what the processor believes: every command reaches it {@link #commandLatencyMs} after it was sent, and it
         * then moves from wherever it really is towards the target in the given time (at its minimum speed when
         * that time asks for a slower movement). A difference between the processor's belief and the device shows
         * up here, which is what a jump of the real slider comes from.
         */
        double sliderPositionAt(long atTimeMs)
        {
            List<RecordedCall> calls = client.calls;
            // Before the first command the device is where it reported to be, or (when it could not report it)
            // where the processor assumed it to be
            double initial = !Double.isNaN(initialDevicePosition) ? initialDevicePosition
                    : calls.isEmpty() ? startPosition : calls.getFirst().startX();
            if (calls.isEmpty() || arrivalMs(0) > atTimeMs)
            {
                return initial;
            }
            double from = initial;
            double target = from;
            long segmentStartMs = arrivalMs(0);
            double speed = 0;
            for (int i = 0; i < calls.size(); i++)
            {
                RecordedCall call = calls.get(i);
                long arrivalMs = arrivalMs(i);
                if (arrivalMs > atTimeMs)
                {
                    break;
                }
                from = segmentPosition(from, target, segmentStartMs, speed, arrivalMs);
                target = call.xp();
                segmentStartMs = arrivalMs;
                double distance = Math.abs(target - from);
                speed = call.t() <= 0 ? Double.POSITIVE_INFINITY : distance / (call.t() / 1000.0);
                if (distance > 0 && speed < deviceMinSpeed)
                {
                    speed = deviceMinSpeed;
                }
            }
            return segmentPosition(from, target, segmentStartMs, speed, atTimeMs);
        }

        /**
         * Time the command with the given index reaches the device: its latency varies (deterministically per
         * command), but the commands arrive in the order they were sent, like over BLE.
         */
        private long arrivalMs(int index)
        {
            long arrival = Long.MIN_VALUE;
            for (int i = 0; i <= index; i++)
            {
                long jitter = commandLatencyJitterMs == 0 ? 0
                        : Math.floorMod(i * 7919L + 13, 2 * commandLatencyJitterMs + 1) - commandLatencyJitterMs;
                arrival = Math.max(arrival, client.calls.get(i).at() + commandLatencyMs + jitter);
            }
            return arrival;
        }

        private static double segmentPosition(double from, double target, long startMs, double speed, long atMs)
        {
            double distance = Math.abs(target - from);
            double travelled = speed * Math.max(0, atMs - startMs) / 1000.0;
            return travelled >= distance ? target : from + Math.signum(target - from) * travelled;
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
        public HandyBaseResponseWithError changeMode(DeviceMode mode)
        {
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public boolean checkConnectionStatus()
        {
            return true;
        }

        @Override
        public void setSliderSettings(Float min, Float max)
        {
        }

        @Override
        public Optional<SliderSettingsResult> getSliderSettings()
        {
            return Optional.of(new SliderSettingsResult("0.0", "1.0", 5f, 105f));
        }

        @Override
        public Optional<SliderState> getSliderState()
        {
            double position = harness == null ? Double.NaN : harness.initialDevicePosition;
            return Double.isNaN(position) ? Optional.empty()
                    : Optional.of(new SliderState((float) position, (float) (5 + position * 100), 0f));
        }

        @Override
        public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
        {
            double startX = harness.processor.sliderPositionForTest(harness.virtualTimeMs());
            calls.add(new RecordedCall(xp, t, harness.virtualTimeMs(), startX, stopOnTarget));
            return new HandyBaseResponseWithError(null);
        }
    }
}
