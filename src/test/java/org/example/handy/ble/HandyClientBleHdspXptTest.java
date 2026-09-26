package org.example.handy.ble;

import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the HDSP XPT command as it goes on the wire. The position is normalized by the HDSP processor before it
 * reaches the client, so the client has to pass it through unchanged: the device caps positions outside its
 * range silently, which would turn a scaled value into a move to the end of the stroke.
 */
class HandyClientBleHdspXptTest
{
    private final TestBleAdapter ble = new TestBleAdapter();
    private final HandyClientBle client = new HandyClientBle(ble);

    @AfterEach
    void tearDown()
    {
        client.close();
    }

    @Test
    void sendsThePositionUnchanged() throws Exception
    {
        assertThat(client.hdspXpt(0.5f, 20, false).error()).isNull();

        Messages.RequestHdspXpTSet request = awaitXpt();
        assertThat(request.getXp()).isEqualTo(0.5f);
        assertThat(request.getT()).isEqualTo(20);
        assertThat(request.getStopOnTarget()).isFalse();
    }

    @Test
    void sendsTheEndsOfTheStrokeAndTheStopFlag() throws Exception
    {
        client.hdspXpt(0f, 5, true);
        client.hdspXpt(1f, 200, false);

        List<Messages.RequestHdspXpTSet> requests = awaitXpts(2);
        assertThat(requests.get(0).getXp()).isEqualTo(0f);
        assertThat(requests.get(0).getT()).isEqualTo(5);
        assertThat(requests.get(0).getStopOnTarget()).isTrue();
        assertThat(requests.get(1).getXp()).isEqualTo(1f);
        assertThat(requests.get(1).getT()).isEqualTo(200);
        assertThat(requests.get(1).getStopOnTarget()).isFalse();
    }

    @Test
    void doesNotWaitForAResponse() throws Exception
    {
        // HDSP commands are streamed continuously, so they are sent fire and forget (the device's response is
        // recognized by its id and dropped instead of being awaited)
        assertThat(client.hdspXpt(0.1f, 20, false).error()).isNull();

        HandyRpc.Request request = awaitRequest(0);
        assertThat(request.getId()).isEqualTo(HandyRpcClient.NO_COMPLETION_ID);
    }

    private Messages.RequestHdspXpTSet awaitXpt() throws InterruptedException
    {
        return awaitRequest(0).getRequestHdspXpTSet();
    }

    private List<Messages.RequestHdspXpTSet> awaitXpts(int count) throws InterruptedException
    {
        return awaitWritten(count).stream()
                .map(message -> message.getRequest().getRequestHdspXpTSet())
                .toList();
    }

    private HandyRpc.Request awaitRequest(int index) throws InterruptedException
    {
        HandyRpc.Request request = awaitWritten(index + 1).get(index).getRequest();
        assertThat(request.getParamsCase()).isEqualTo(HandyRpc.Request.ParamsCase.REQUEST_HDSP_XP_T_SET);
        return request;
    }

    private List<HandyRpc.RpcMessage> awaitWritten(int count) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 2_000;
        while (ble.transport.written().size() < count && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(5);
        }
        List<HandyRpc.RpcMessage> written = ble.transport.written();
        assertThat(written).hasSizeGreaterThanOrEqualTo(count);
        return written;
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
