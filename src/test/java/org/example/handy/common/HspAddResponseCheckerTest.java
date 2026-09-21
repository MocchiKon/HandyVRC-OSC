package org.example.handy.common;

import org.example.handy.common.dto.HspPlayState;
import org.example.handy.common.dto.HspState;
import org.example.handy.common.dto.MovementPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HspAddResponseCheckerTest
{
    private static final List<MovementPoint> BATCH = List.of(
            new MovementPoint(1_200, 30),
            new MovementPoint(1_300, 60),
            new MovementPoint(1_400, 90));

    @Test
    void healthyBatchIsAcceptedWithoutProblems()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 50, 100, HspPlayState.PLAYING));

        assertThat(check.isOk()).isTrue();
        assertThat(check.error()).isNull();
        assertThat(check.warnings()).isEmpty();
        assertThat(check.sentPoints()).isEqualTo(3);
        assertThat(check.skippedPoints()).isZero();
        assertThat(check.leewayMs()).isEqualTo(200);
        assertThat(check.bufferedPoints()).isEqualTo(50);
        assertThat(check.playState()).isEqualTo(HspPlayState.PLAYING);
    }

    @Test
    void missingStateIsAnError()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, null);

        assertThat(check.error()).isNotNull();
        assertThat(check.error().name()).isEqualTo("HSP_ADD_NO_STATE");
        assertThat(check.sentPoints()).isEqualTo(3);
    }

    @Test
    void notInitializedStreamIsAnError()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 50, 100, HspPlayState.NOT_INITIALIZED));

        assertThat(check.error()).isNotNull();
        assertThat(check.error().name()).isEqualTo("HSP_NOT_INITIALIZED");
    }

    @Test
    void reportsTheWholeBatchBeingSkipped()
    {
        // The play position is already at the end of the batch when the device handled it
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_400, 1_200, 1_400, 50, 100, HspPlayState.PLAYING));

        assertThat(check.error()).isNull();
        assertThat(check.skippedPoints()).isEqualTo(3);
        assertThat(check.leewayMs()).isEqualTo(-200);
        assertThat(check.warnings()).singleElement().asString().contains("skipped all 3 point(s)");
    }

    @Test
    void reportsPartiallySkippedPoints()
    {
        // The device is already at 1_250ms: the first point of the batch is in the past, the other two are not
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_250, 1_400, 1_600, 20, 100, HspPlayState.PLAYING));

        assertThat(check.skippedPoints()).isEqualTo(1);
        assertThat(check.leewayMs()).isEqualTo(-50);
        assertThat(check.warnings()).singleElement().asString()
                .contains("1 of 3 point(s) of the batch were skipped")
                .contains("leeway -50ms");
    }

    @Test
    void reportsAStarvingStream()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 0, 100, HspPlayState.STARVING));

        assertThat(check.error()).isNull();
        assertThat(check.warnings()).singleElement().asString().contains("STARVING");
    }

    @Test
    void reportsAStoppedStream()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 3, 100, HspPlayState.STOPPED));

        assertThat(check.warnings()).singleElement().asString().contains("STOPPED");
    }

    @Test
    void reportsAFullBuffer()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 100, 100, HspPlayState.PLAYING));

        assertThat(check.warnings()).singleElement().asString().contains("buffer is full (100/100)");
    }

    @Test
    void reportsAnEmptyBufferRightAfterAdding()
    {
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_400, 0, 100, HspPlayState.PLAYING));

        assertThat(check.warnings()).singleElement().asString().contains("about to starve");
    }

    @Test
    void reportsABatchThatWasNotBufferedToItsEnd()
    {
        // The buffer ends before the last point of the batch, so the tail of the batch was dropped
        HspAddCheck check = HspAddResponseChecker.check(BATCH, state(1_000, 1_200, 1_250, 20, 100, HspPlayState.PLAYING));

        assertThat(check.warnings()).singleElement().asString().contains("the end of the batch was not buffered");
    }

    @Test
    void reportsAnInconsistentBuffer()
    {
        List<MovementPoint> earlyBatch = List.of(new MovementPoint(100, 10), new MovementPoint(200, 20));
        HspAddCheck check = HspAddResponseChecker.check(earlyBatch, state(0, 3_000, 1_000, 5, 100, HspPlayState.PLAYING));

        assertThat(check.warnings()).singleElement().asString().contains("inconsistent buffer");
    }

    @Test
    void doesNotJudgeWhatTheDeviceDidNotReport()
    {
        // Only the timing fields are known (a source without buffer/play-state information), and the play
        // position is before the batch: nothing can be judged, so nothing is reported.
        HspAddCheck check = HspAddResponseChecker.check(BATCH, new HspState(1_000, 1_200, 1_400));

        assertThat(check.isOk()).isTrue();
        assertThat(check.playState()).isNull();
        assertThat(check.bufferedPoints()).isNull();
        assertThat(check.maxPoints()).isNull();
    }

    @Test
    void acceptsAnEmptyBatchWithoutJudgingIt()
    {
        HspAddCheck check = HspAddResponseChecker.check(List.of(), state(1_000, 1_200, 1_400, 50, 100, HspPlayState.PLAYING));

        assertThat(check.isOk()).isTrue();
        assertThat(check.sentPoints()).isZero();
        assertThat(check.leewayMs()).isNull();
    }

    private static HspState state(int currentTime, Integer firstPointTime, Integer lastPointTime,
                                  Integer points, Integer maxPoints, HspPlayState playState)
    {
        return new HspState(currentTime, firstPointTime, lastPointTime, points, maxPoints, 0, playState);
    }
}
