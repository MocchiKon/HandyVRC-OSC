package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.*;

/**
 * The check that runs right after connecting. It is what turns a device that connects but never answers (the
 * reported Handy 2 problem) into one clear message instead of a timeout on whichever request happens to be first.
 */
class HandyClientBleHandshakeTest
{
    private final TestBleAdapter ble = new TestBleAdapter();
    private final HandyClientBle client = new HandyClientBle(ble);

    @AfterEach
    void tearDown()
    {
        client.close();
    }

    @Test
    void asksForTheModeAndThenForTheCapabilities() throws Exception
    {
        Thread responder = new Thread(() ->
        {
            try
            {
                respondTo(0, true);
                respondTo(1, true);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        responder.start();

        client.verifyResponsive(2_000);
        responder.join(2_000);

        List<HandyRpc.RpcMessage> written = ble.transport.written();
        assertThat(written).hasSize(2);
        assertThat(written.get(0).getRequest().getParamsCase())
                .isEqualTo(HandyRpc.Request.ParamsCase.REQUEST_MODE_GET);
        assertThat(written.get(1).getRequest().getParamsCase())
                .isEqualTo(HandyRpc.Request.ParamsCase.REQUEST_CAPABILITIES_GET);
    }

    @Test
    void reportsADeviceThatDoesNotAnswer()
    {
        assertThatThrownBy(() -> client.verifyResponsive(200))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("connected but does not answer");
    }

    @Test
    void doesNotFailWhenOnlyTheCapabilitiesAreNotAnswered() throws Exception
    {
        Thread responder = new Thread(() ->
        {
            try
            {
                respondTo(0, false);
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        responder.start();

        assertThatCode(() -> client.verifyResponsive(200)).doesNotThrowAnyException();
        responder.join(2_000);
    }

    /**
     * Answers the request with the given index, so that it arrives while the client is still waiting for it.
     *
     * @param withCapabilities whether the answer is a capabilities response; otherwise only the mode request gets one
     */
    private void respondTo(int index, boolean withCapabilities) throws InterruptedException
    {
        List<HandyRpc.RpcMessage> written = ble.transport.awaitWritten(index + 1, 2_000);
        HandyRpc.Request request = written.get(index).getRequest();
        HandyRpc.Response.Builder response = HandyRpc.Response.newBuilder().setId(request.getId());
        switch (request.getParamsCase())
        {
            case REQUEST_MODE_GET -> response.setResponseModeGet(
                    Messages.ResponseModeGet.newBuilder().setMode(Constants.Mode.MODE_HDSP));
            case REQUEST_CAPABILITIES_GET -> {
                if (!withCapabilities)
                {
                    return; // The device answers the mode request but not this one
                }
                response.setResponseCapabilitiesGet(Messages.ResponseCapabilitiesGet.newBuilder()
                        .setSlider(1)
                        .setBattery(true));
            }
            default -> throw new AssertionError("Unexpected request: " + request.getParamsCase());
        }
        ble.transport.receive(HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(response)
                .build());
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
