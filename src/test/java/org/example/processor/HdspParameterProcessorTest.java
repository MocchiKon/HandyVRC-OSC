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

import static org.assertj.core.api.Assertions.assertThat;

class HdspParameterProcessorTest
{
    @Test
    void penetratorUsesValueAsPenetrationAndIgnoresProximities()
    {
        var processor = new HdspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnProximityChange(0.9f, 0.95f); // Only used for orifice
        processor.actOnValueChange(0.25f);

        assertThat(positions).containsExactly(75);
    }

    @Test
    void minimalValueChangeIsRespected()
    {
        var processor = new HdspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .minimalValueChange(10)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnValueChange(0.05f); // Only 5% change from the initial position, ignored
        processor.actOnValueChange(0.5f); // 50% change, kept

        assertThat(positions).containsExactly(50);
    }

    @Test
    void firstPointIsSentWithSendIntervalAndWithoutStopOnTarget()
    {
        var client = new RecordingHandyClient();
        var processor = processor(client, false);

        processor.actOnValueChange(0.5f); // Position 50
        processor.trySendingMessage(0);

        assertThat(client.hdspCalls).containsExactly(new HdspCall(50f, 20, false));
    }

    @Test
    void deviceIsStoppedWhenPointsStopComing()
    {
        var client = new RecordingHandyClient();
        var processor = processor(client, false);
        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0);

        // Simulate that no new point was produced for longer than the idle timeout
        processor.lastPointActivityMs = System.currentTimeMillis() - HdspParameterProcessor.HDSP_IDLE_STOP_TIMEOUT_MS - 10;
        processor.trySendingMessage(0);

        assertThat(client.hdspCalls).containsExactly(
                new HdspCall(50f, 20, false),
                new HdspCall(50f, 20, true));
    }

    @Test
    void stopIsSentOnlyOnce()
    {
        var client = new RecordingHandyClient();
        var processor = processor(client, false);
        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0);
        processor.lastPointActivityMs = System.currentTimeMillis() - HdspParameterProcessor.HDSP_IDLE_STOP_TIMEOUT_MS - 10;
        processor.trySendingMessage(0);
        processor.trySendingMessage(0);
        processor.trySendingMessage(0);

        assertThat(client.hdspCalls).hasSize(2);
    }

    @Test
    void pointAfterStopUsesTheSameIntervalAsTheFirstPoint()
    {
        var client = new RecordingHandyClient();
        var processor = processor(client, false);
        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0);
        processor.lastPointActivityMs = System.currentTimeMillis() - HdspParameterProcessor.HDSP_IDLE_STOP_TIMEOUT_MS - 10;
        processor.trySendingMessage(0);

        processor.actOnValueChange(0.2f); // Position 80
        processor.trySendingMessage(0);

        assertThat(client.hdspCalls.getLast()).isEqualTo(new HdspCall(80f, 20, false));
    }

    @Test
    void aFreshMoveIsNotStoppedInstantlyEvenIfItsPointWaitedALongTime()
    {
        var client = new RecordingHandyClient();
        var processor = processor(client, false);
        processor.actOnValueChange(0.5f);
        // Simulate a point that waited for the device (for example while HDSP timing was enabled)
        processor.lastPointActivityMs = System.currentTimeMillis() - HdspParameterProcessor.HDSP_IDLE_STOP_TIMEOUT_MS - 10;

        processor.trySendingMessage(0); // Sends the movement
        processor.trySendingMessage(0); // Would stop it immediately if the activity time was not refreshed

        assertThat(client.hdspCalls).containsExactly(new HdspCall(50f, 20, false));
    }

    @Test
    void timingHoldsBackTheNextCommandUntilTheCurrentMoveFinishes() throws InterruptedException
    {
        var client = new RecordingHandyClient();
        client.messageDelayMs = 30;
        var processor = processor(client, true);

        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0); // First point is always sent immediately
        assertThat(client.hdspCalls).hasSize(1);

        processor.actOnValueChange(0.2f);
        processor.trySendingMessage(0); // The previous move (20ms) is still running
        assertThat(client.hdspCalls).hasSize(1);

        Thread.sleep(25);
        processor.trySendingMessage(0); // Now the next command may be sent
        assertThat(client.hdspCalls).hasSize(2);
        assertThat(client.hdspCalls.getLast().stopOnTarget()).isFalse();
    }

    @Test
    void timingAddsTheMeasuredJitterAsSafetyMargin() throws InterruptedException
    {
        var client = new RecordingHandyClient();
        client.messageDelayMs = 30;
        client.messageJitterMs = 40;
        var processor = processor(client, true);

        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0); // First point (20ms move)

        processor.actOnValueChange(0.2f);
        Thread.sleep(25); // Longer than the move, but shorter than move + jitter margin
        processor.trySendingMessage(0);
        assertThat(client.hdspCalls).hasSize(1);

        Thread.sleep(45); // Now the jitter margin has passed as well
        processor.trySendingMessage(0);
        assertThat(client.hdspCalls).hasSize(2);
    }

    @Test
    void pointAfterStopIsSentImmediatelyEvenWithTimingEnabled()
    {
        var client = new RecordingHandyClient();
        client.messageDelayMs = 30;
        var processor = processor(client, true);

        processor.actOnValueChange(0.5f);
        processor.trySendingMessage(0);
        processor.lastPointActivityMs = System.currentTimeMillis() - HdspParameterProcessor.HDSP_IDLE_STOP_TIMEOUT_MS - 10;
        processor.trySendingMessage(0); // Stop

        processor.actOnValueChange(0.2f);
        processor.trySendingMessage(0); // After a stop the timing is not trusted, so it is sent right away

        assertThat(client.hdspCalls).hasSize(3);
        assertThat(client.hdspCalls.get(1).stopOnTarget()).isTrue();
        assertThat(client.hdspCalls.get(2)).isEqualTo(new HdspCall(80f, 20, false));
    }

    @Test
    void firstMoveUsesSendInterval()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(null, 5000, 20, false)).isEqualTo(20);
    }

    @Test
    void firstMoveNeverHasZeroDuration()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(null, 0, 0, false)).isEqualTo(1);
    }

    @Test
    void moveDurationMatchesTimeBetweenSentPoints()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(100, 180, 20, false)).isEqualTo(80);
    }

    @Test
    void moveAfterStopUsesTheSameIntervalAsTheFirstMove()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(1000, 5000, 20, true)).isEqualTo(20);
    }

    @Test
    void moveDurationIsCappedAtMaximum()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(0, 60_000, 20, false))
                .isEqualTo(HdspParameterProcessor.HDSP_MAX_MOVE_DURATION_MS);
    }

    @Test
    void moveDurationIsAtLeastOneMsForOutOfOrderPoints()
    {
        assertThat(HdspParameterProcessor.resolveMoveDurationMs(500, 100, 20, false)).isEqualTo(1);
    }

    private static HdspParameterProcessor processor(HandyClient handyClient, boolean hdspTiming)
    {
        var processor = new HdspParameterProcessor(handyClient, ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .minimalValueChange(0)
                .hdspTiming(hdspTiming)
                .build());
        processor.setValueChangeListener(value -> {});
        return processor;
    }

    private record HdspCall(float xp, int t, boolean stopOnTarget) {}

    /** Minimal HandyClient that records the HDSP commands instead of talking to a device. */
    private static class RecordingHandyClient extends HandyClient
    {
        final List<HdspCall> hdspCalls = new ArrayList<>();
        long messageDelayMs;
        long messageJitterMs;

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
            hdspCalls.add(new HdspCall(xp, t, stopOnTarget));
            return new HandyBaseResponseWithError(null);
        }

        @Override
        public void sendRequestForMessageDelayCalc()
        {
        }

        @Override
        public MessageDelayStats calculateMessageDelayStats()
        {
            return new MessageDelayStats(messageDelayMs, messageJitterMs);
        }
    }
}
