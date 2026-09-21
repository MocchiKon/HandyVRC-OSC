package org.example.handy.v3;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import handy.api.HspApi;
import handy.invoker.ApiException;
import handy.model.*;
import org.example.handy.common.dto.HandyHspAddResponse;
import org.example.handy.common.dto.HspAddRequest;
import org.example.handy.common.dto.MovementPoint;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the response of an HSP add is verified in API mode as well: the state the server reports is mapped
 * and checked against the sent points, so skipped points and a broken stream are reported here too.
 */
class HandyClientV3HspAddTest
{
    private static final List<MovementPoint> POINTS = List.of(new MovementPoint(1_200, 30), new MovementPoint(1_300, 60));

    @Test
    void returnsTheStateTheApiReported()
    {
        HspState state = state(1_000, 1_200, 1_300)
                .playState(HspPlayState.PLAYING)
                .points(40)
                .maxPoints(BigDecimal.valueOf(120));
        HandyHspAddResponse response = clientAnswering(response(state)).hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNull();
        assertThat(response.result().current_time()).isEqualTo(1_000);
        assertThat(response.result().first_point_time()).isEqualTo(1_200);
        assertThat(response.result().last_point_time()).isEqualTo(1_300);
        assertThat(response.result().points()).isEqualTo(40);
        assertThat(response.result().max_points()).isEqualTo(120);
        assertThat(response.result().play_state()).isEqualTo(org.example.handy.common.dto.HspPlayState.PLAYING);
    }

    @Test
    void reportsTheErrorTheApiReturned()
    {
        HandyHspAddResponse response = clientAnswering(response(new DeviceError().code(7).name("HSP_ERROR").message("HSP buffer full")))
                .hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(7);
        assertThat(response.error().message()).isEqualTo("HSP buffer full");
        assertThat(response.result()).isNull(); // an error takes precedence, the (empty) state is not checked
    }

    @Test
    void reportsAMissingState()
    {
        HandyHspAddResponse response = clientAnswering(new GetHsspState200Response())
                .hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_ADD_NO_STATE");
    }

    @Test
    void reportsAStreamThatIsNotInitialized()
    {
        HspState state = state(1_000, 1_200, 1_300)
                .playState(HspPlayState.NOT_INITIALIZED)
                .points(40)
                .maxPoints(BigDecimal.valueOf(120));
        HandyHspAddResponse response = clientAnswering(response(state)).hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_NOT_INITIALIZED");
    }

    @Test
    void warnsAboutPointsThatTheDeviceAlreadyPassed()
    {
        // The device is already at 1_500ms while the batch it just received ends at 1_300ms: all of it is skipped
        HspState state = state(1_500, 1_600, 1_700)
                .playState(HspPlayState.PLAYING)
                .points(30)
                .maxPoints(BigDecimal.valueOf(120));
        ListAppender<ILoggingEvent> logs = attachLogCapture();
        try
        {
            HandyHspAddResponse response = clientAnswering(response(state)).hspAdd(new HspAddRequest(POINTS, false));

            assertThat(response.error()).isNull(); // a skipped batch degrades the movement but does not stop the stream
            assertThat(logs.list)
                    .anySatisfy(event -> assertThat(event.getFormattedMessage())
                            .contains("skipped all 2 point(s)")
                            .contains("the play position (1500ms)"));
        }
        finally
        {
            detachLogCapture(logs);
        }
    }

    @Test
    void resendsTheBatchWhenTheServerRecycledTheConnection() throws Exception
    {
        // The API server closes a connection after a fixed number of requests, which an older JDK reports as a
        // "GOAWAY received" failure to every request that was in flight: such a batch has to be sent again
        // instead of being dropped (the points keep their timestamps, so the batch is still playable).
        var api = new FailingOnceHspApi(response(state(1_000, 1_200, 1_300)
                .playState(HspPlayState.PLAYING)
                .points(40)
                .maxPoints(BigDecimal.valueOf(120))));

        HandyHspAddResponse response = new HandyClientV3(api).hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNull();
        assertThat(response.result().last_point_time()).isEqualTo(1_300);
        assertThat(api.calls).hasValue(2);
    }

    private static HandyClientV3 clientAnswering(GetHsspState200Response response)
    {
        return new HandyClientV3(new FakeHspApi(response));
    }

    private static GetHsspState200Response response(HspState state)
    {
        return new GetHsspState200Response().result(state);
    }

    private static GetHsspState200Response response(DeviceError error)
    {
        return new GetHsspState200Response().error(error);
    }

    private static HspState state(int currentTime, Integer firstPointTime, Integer lastPointTime)
    {
        var state = new HspState();
        state.setCurrentTime(currentTime);
        state.setFirstPointTime(firstPointTime);
        state.setLastPointTime(lastPointTime);
        return state;
    }

    private static ListAppender<ILoggingEvent> attachLogCapture()
    {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HandyClientV3.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogCapture(ListAppender<ILoggingEvent> appender)
    {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HandyClientV3.class)).detachAppender(appender);
    }

    /** HSP API double: answers every add with the response the test prepared. */
    private static final class FakeHspApi extends HspApi
    {
        private final GetHsspState200Response response;

        private FakeHspApi(GetHsspState200Response response)
        {
            this.response = response;
        }

        @Override
        public GetHsspState200Response hspAdd(String xConnectionKey, HspAdd hspAdd, Integer timeout) throws ApiException
        {
            return response;
        }
    }

    /** HSP API double: fails the first add the way a recycled HTTP/2 connection does and answers the second one. */
    private static final class FailingOnceHspApi extends HspApi
    {
        private final AtomicInteger calls = new AtomicInteger();
        private final GetHsspState200Response response;

        private FailingOnceHspApi(GetHsspState200Response response)
        {
            this.response = response;
        }

        @Override
        public GetHsspState200Response hspAdd(String xConnectionKey, HspAdd hspAdd, Integer timeout) throws ApiException
        {
            if (calls.incrementAndGet() == 1)
            {
                // What the JDK HttpClient (before 21.0.8) throws for a request that was in flight when the server
                // sent its GOAWAY frame, wrapped the way the generated API client wraps it
                throw new ApiException(new IOException("/10.0.0.1:51234: GOAWAY received"));
            }
            return response;
        }
    }
}
