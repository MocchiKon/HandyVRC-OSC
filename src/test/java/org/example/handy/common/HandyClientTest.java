package org.example.handy.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandyClientTest
{
    @Test
    void rejectsOutliersBeforeCalculatingDelayAndJitter()
    {
        // 200 is a clear outlier that would badly skew a plain average (plain average RTD would be ~50ms)
        var stats = HandyClient.toMessageDelayStats(new long[]{10, 12, 12, 14, 200});

        assertThat(stats.delayMs()).isEqualTo(6); // average of the remaining {12, 12, 14} = 13ms RTD -> 6ms one-way
        assertThat(stats.jitterMs()).isEqualTo(1); // (14 - 12) / 2
    }

    @Test
    void stableSamplesHaveNoJitter()
    {
        var stats = HandyClient.toMessageDelayStats(new long[]{20, 20, 20, 20});

        assertThat(stats.delayMs()).isEqualTo(10);
        assertThat(stats.jitterMs()).isZero();
    }

    @Test
    void singleSampleHasNoJitter()
    {
        var stats = HandyClient.toMessageDelayStats(new long[]{8});

        assertThat(stats.delayMs()).isEqualTo(4);
        assertThat(stats.jitterMs()).isZero();
    }

    @Test
    void failsWithoutSamples()
    {
        assertThatThrownBy(() -> HandyClient.toMessageDelayStats(new long[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
