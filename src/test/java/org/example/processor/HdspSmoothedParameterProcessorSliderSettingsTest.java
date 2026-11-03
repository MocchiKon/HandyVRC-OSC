package org.example.processor;

import org.example.config.ConfigProperties;
import org.example.handy.common.DeviceMode;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.SliderSettingsResult;
import org.example.handy.common.dto.SliderState;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The stroke range is a convenience, not a requirement. A connected device that does not answer those requests (a
 * Handy 2 that is connected but mute was reported) must not stop the session from starting: the movement commands
 * are fire-and-forget, so the device can still be moved on the range it already has.
 */
class HdspSmoothedParameterProcessorSliderSettingsTest
{
    private static final ConfigProperties CONFIG = ConfigProperties.builder()
            .spsType(SpsType.PENETRATOR)
            .minimalValueChange(0)
            .hdspMinSliderSpeed(0)
            .sliderMin(0.2f)
            .sliderMax(0.8f)
            .build();

    @Test
    void startsWhenTheDeviceDoesNotAnswerTheStrokeRangeRequests()
    {
        var client = new SliderRangeClient(true, true);

        assertThatCode(() -> new HdspSmoothedParameterProcessor(client, CONFIG)).doesNotThrowAnyException();

        assertThat(client.setAttempted).isTrue();
        assertThat(client.getAttempted).isTrue();
    }

    @Test
    void startsWhenTheStrokeRangeCanBeSetButNotRead()
    {
        var client = new SliderRangeClient(false, true);

        assertThatCode(() -> new HdspSmoothedParameterProcessor(client, CONFIG)).doesNotThrowAnyException();
    }

    @Test
    void startsWhenTheStrokeRangeCanBeReadButNotSet()
    {
        var client = new SliderRangeClient(true, false);

        assertThatCode(() -> new HdspSmoothedParameterProcessor(client, CONFIG)).doesNotThrowAnyException();
    }

    @Test
    void leavesTheDeviceOnItsFullStrokeWhenTheConnectionHasNoStrokeRange()
    {
        var client = new SliderRangeClient(false, false);
        client.supportsSliderSettings = false;

        assertThatCode(() -> new HdspSmoothedParameterProcessor(client, CONFIG)).doesNotThrowAnyException();

        assertThat(client.setAttempted).isFalse();
        assertThat(client.getAttempted).isFalse();
    }

    @Test
    void doesNotReadTheDevicePositionWhenTheConnectionCannotReportIt()
    {
        var client = new SliderRangeClient(false, false);
        client.supportsSliderState = false;

        var processor = new HdspSmoothedParameterProcessor(client,
                CONFIG.toBuilder().hdspLogDevicePosition(true).build());
        processor.stop();

        assertThat(client.stateAttempted).isFalse();
    }

    /** Client whose stroke range requests fail on demand, like a device that ignores them. */
    private static final class SliderRangeClient extends HandyClient
    {
        private final boolean setFails;
        private final boolean getFails;
        private boolean setAttempted;
        private boolean getAttempted;
        private boolean stateAttempted;
        private boolean supportsSliderSettings = true;
        private boolean supportsSliderState = true;

        private SliderRangeClient(boolean setFails, boolean getFails)
        {
            this.setFails = setFails;
            this.getFails = getFails;
        }

        @Override
        public boolean supportsSliderSettings()
        {
            return supportsSliderSettings;
        }

        @Override
        public boolean supportsSliderState()
        {
            return supportsSliderState;
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
            setAttempted = true;
            if (setFails)
            {
                throw new RuntimeException("BLE SliderSet failed", new TimeoutException("no answer"));
            }
        }

        @Override
        public Optional<SliderSettingsResult> getSliderSettings()
        {
            getAttempted = true;
            if (getFails)
            {
                throw new RuntimeException("BLE SliderGet failed", new TimeoutException("no answer"));
            }
            return Optional.of(new SliderSettingsResult("0.2", "0.8", 20f, 80f));
        }

        @Override
        public Optional<SliderState> getSliderState()
        {
            stateAttempted = true;
            return Optional.empty();
        }
    }
}
