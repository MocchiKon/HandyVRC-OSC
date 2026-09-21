package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import org.example.handy.common.dto.HandyHspAddResponse;
import org.example.handy.common.dto.HspAddRequest;
import org.example.handy.common.dto.HspPlayState;
import org.example.handy.common.dto.MovementPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the HSP add response handling: the batch is no longer fire and forget, its response is matched to the
 * batch that caused it and the returned state is checked and reported.
 */
class HandyClientBleHspAddTest
{
    private static final List<MovementPoint> POINTS = List.of(new MovementPoint(1_200, 30), new MovementPoint(1_300, 60));
    private static final long RESPONSE_TIMEOUT_MS = 200;

    private final TestBleAdapter ble = new TestBleAdapter();
    private final HandyClientBle client = new HandyClientBle(ble, RESPONSE_TIMEOUT_MS);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown()
    {
        client.close();
        executor.shutdownNow();
    }

    @Test
    void returnsTheStateTheDeviceReportedForTheSentPoints() throws Exception
    {
        Future<HandyHspAddResponse> future = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, false)));
        HandyRpc.Request request = awaitHspAddRequest(0);
        assertThat(request.getRequestHspAdd().getPointsList())
                .extracting(Constants.Point::getT)
                .containsExactly(1_200, 1_300);

        receiveHspAddResponse(request.getId(), playingState(1_000, 1_200, 1_300, 40, 120));

        HandyHspAddResponse response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNull();
        assertThat(response.result().current_time()).isEqualTo(1_000);
        assertThat(response.result().first_point_time()).isEqualTo(1_200);
        assertThat(response.result().last_point_time()).isEqualTo(1_300);
        assertThat(response.result().points()).isEqualTo(40);
        assertThat(response.result().max_points()).isEqualTo(120);
        assertThat(response.result().play_state()).isEqualTo(HspPlayState.PLAYING);
    }

    @Test
    void reportsATimeoutWhenTheDeviceDoesNotAnswer()
    {
        HandyHspAddResponse response = client.hspAdd(new HspAddRequest(POINTS, false));

        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_ADD_TIMEOUT");
        assertThat(response.result()).isNull();
    }

    @Test
    void reportsTheErrorCodeTheDeviceReturned() throws Exception
    {
        Future<HandyHspAddResponse> future = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, false)));
        HandyRpc.Request request = awaitHspAddRequest(0);

        ble.transport.receive(HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder()
                        .setId(request.getId())
                        .setError(HandyRpc.Error.newBuilder().setCode(7).setMessage("HSP buffer full")))
                .build());

        HandyHspAddResponse response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().code()).isEqualTo(7);
        assertThat(response.error().message()).contains("HSP buffer full");
    }

    @Test
    void reportsAnEmptyResponse() throws Exception
    {
        Future<HandyHspAddResponse> future = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, false)));
        HandyRpc.Request request = awaitHspAddRequest(0);

        ble.transport.receive(HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder().setId(request.getId()))
                .build());

        HandyHspAddResponse response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_ADD_EMPTY_RESPONSE");
    }

    @Test
    void reportsAResponseWithoutAState() throws Exception
    {
        Future<HandyHspAddResponse> future = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, false)));
        HandyRpc.Request request = awaitHspAddRequest(0);

        // The device answered with an HSP state message, but did not fill in the state itself
        ble.transport.receive(HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder()
                        .setId(request.getId())
                        .setResponseHspAdd(Messages.ResponseHspAdd.getDefaultInstance()))
                .build());

        HandyHspAddResponse response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_ADD_NO_STATE");
        assertThat(response.result()).isNull();
    }

    @Test
    void reportsAnUninitializedStream() throws Exception
    {
        Future<HandyHspAddResponse> future = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, false)));
        HandyRpc.Request request = awaitHspAddRequest(0);

        receiveHspAddResponse(request.getId(), Constants.HspState.newBuilder()
                .setPlayState(Constants.HspPlayState.HSP_STATE_NOT_INITIALIZED)
                .setCurrentTime(1_000)
                .setFirstPointTime(1_200)
                .setLastPointTime(1_300));

        HandyHspAddResponse response = future.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNotNull();
        assertThat(response.error().name()).isEqualTo("HSP_NOT_INITIALIZED");
        assertThat(response.result().current_time()).isEqualTo(1_000); // the state is still passed on for diagnostics
    }

    @Test
    void keepsStreamingAndMatchingAfterATimedOutBatch() throws Exception
    {
        HandyHspAddResponse timedOut = client.hspAdd(new HspAddRequest(POINTS, false));
        assertThat(timedOut.error().name()).isEqualTo("HSP_ADD_TIMEOUT");
        int timedOutId = ble.transport.written().getFirst().getRequest().getId();

        // The late response of the first batch must not be handed to the second batch
        receiveHspAddResponse(timedOutId, playingState(999, 1_200, 1_300, 40, 120));

        Future<HandyHspAddResponse> second = executor.submit(() -> client.hspAdd(new HspAddRequest(POINTS, true)));
        HandyRpc.Request secondRequest = awaitHspAddRequest(1);
        receiveHspAddResponse(secondRequest.getId(), playingState(2_000, 2_200, 2_300, 30, 120));

        HandyHspAddResponse response = second.get(2, TimeUnit.SECONDS);
        assertThat(response.error()).isNull();
        assertThat(response.result().current_time()).isEqualTo(2_000);
    }

    private static Constants.HspState.Builder playingState(int currentTime, int firstPointTime, int lastPointTime, int points, int maxPoints)
    {
        return Constants.HspState.newBuilder()
                .setPlayState(Constants.HspPlayState.HSP_STATE_PLAYING)
                .setCurrentTime(currentTime)
                .setFirstPointTime(firstPointTime)
                .setLastPointTime(lastPointTime)
                .setPoints(points)
                .setMaxPoints(maxPoints);
    }

    private void receiveHspAddResponse(int id, Constants.HspState.Builder state)
    {
        ble.transport.receive(HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder()
                        .setId(id)
                        .setResponseHspAdd(Messages.ResponseHspAdd.newBuilder().setState(state)))
                .build());
    }

    private HandyRpc.Request awaitHspAddRequest(int index) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 2_000;
        while (ble.transport.written().size() <= index && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(5);
        }
        List<HandyRpc.RpcMessage> written = ble.transport.written();
        assertThat(written).hasSizeGreaterThan(index);
        HandyRpc.Request request = written.get(index).getRequest();
        assertThat(request.getParamsCase()).isEqualTo(HandyRpc.Request.ParamsCase.REQUEST_HSP_ADD);
        return request;
    }

    /** BLE adapter backed by a transport double, so no device is needed. */
    private static final class TestBleAdapter extends HandyBleAdapter
    {
        private final FakeRpcTransport transport = new FakeRpcTransport();

        @Override
        public void write(byte[] payload)
        {
            transport.write(payload);
        }

        @Override
        public byte[] readMessage(long timeoutMs) throws TimeoutException, InterruptedException
        {
            return transport.readMessage(timeoutMs);
        }
    }
}
