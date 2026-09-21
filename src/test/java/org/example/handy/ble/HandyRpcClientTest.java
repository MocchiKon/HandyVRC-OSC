package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import dev.handy.proto.Notifications;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandyRpcClientTest
{
    private final FakeRpcTransport transport = new FakeRpcTransport();
    private final HandyRpcClient client = new HandyRpcClient(transport);
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown()
    {
        client.close();
        executor.shutdownNow();
    }

    @Test
    void matchesResponsesThatArriveOutOfOrderToTheirRequests() throws Exception
    {
        Future<HandyRpc.Response> first = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        // Wait for the first request to be written, so that the two requests are sent in a known order
        int firstId = transport.awaitWritten(1, 2_000).getFirst().getRequest().getId();
        Future<HandyRpc.Response> second = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));

        int secondId = transport.awaitWritten(2, 2_000).get(1).getRequest().getId();
        assertThat(secondId).isNotEqualTo(firstId);

        // The second request is answered first: responses are matched by id, not by arrival order
        transport.receive(hspStateResponse(secondId, 222));
        transport.receive(hspStateResponse(firstId, 111));

        assertThat(currentTimeOf(first.get(2, TimeUnit.SECONDS))).isEqualTo(111);
        assertThat(currentTimeOf(second.get(2, TimeUnit.SECONDS))).isEqualTo(222);
        assertThat(client.getPendingRequestCount()).isZero();
    }

    @Test
    void answersManyConcurrentRequestsWithTheResponseThatBelongsToThem() throws Exception
    {
        int requestCount = 16;
        List<Future<HandyRpc.Response>> futures = new ArrayList<>();
        for (int i = 0; i < requestCount; i++)
        {
            futures.add(executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000)));
        }

        List<HandyRpc.RpcMessage> requests = transport.awaitWritten(requestCount, 2_000);
        Set<Integer> ids = new HashSet<>();
        for (HandyRpc.RpcMessage request : requests)
        {
            ids.add(request.getRequest().getId());
        }
        assertThat(ids).hasSize(requestCount); // ids allocated by concurrent callers must not collide

        // Answer in reverse order, with a current time that identifies the request it belongs to
        for (int i = requests.size() - 1; i >= 0; i--)
        {
            int id = requests.get(i).getRequest().getId();
            transport.receive(hspStateResponse(id, id));
        }

        Map<Integer, Integer> answered = new HashMap<>();
        for (Future<HandyRpc.Response> future : futures)
        {
            HandyRpc.Response response = future.get(2, TimeUnit.SECONDS);
            answered.put(response.getId(), currentTimeOf(response));
        }

        assertThat(answered.keySet()).containsExactlyInAnyOrderElementsOf(ids);
        answered.forEach((id, currentTime) -> assertThat(currentTime)
                .as("request %d must receive the response that was crafted for it", id)
                .isEqualTo(id));
        assertThat(client.getPendingRequestCount()).isZero();
    }

    @Test
    void dropsTheResponseOfARequestThatAlreadyTimedOut() throws Exception
    {
        Future<HandyRpc.Response> timedOut = executor.submit(() -> client.sendRequest(stateGetRequest(), 200));
        int timedOutId = transport.awaitWritten(1, 2_000).getFirst().getRequest().getId();

        assertThatThrownBy(() -> timedOut.get(2, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
        assertThat(client.getPendingRequestCount()).isZero();

        // The late response arrives after the next request was sent: it must not be handed to it
        Future<HandyRpc.Response> next = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        int nextId = transport.awaitWritten(2, 2_000).get(1).getRequest().getId();
        transport.receive(hspStateResponse(timedOutId, 999));
        transport.receive(hspStateResponse(nextId, 111));

        assertThat(currentTimeOf(next.get(2, TimeUnit.SECONDS))).isEqualTo(111);
        assertThat(client.getPendingRequestCount()).isZero();
    }

    @Test
    void ignoresResponsesOfFireAndForgetRequestsAndOfUnknownIds() throws Exception
    {
        client.sendRequestFireAndForget(stateGetRequest());
        HandyRpc.RpcMessage fireAndForget = transport.awaitWritten(1, 2_000).getFirst();
        assertThat(fireAndForget.getRequest().getId()).isEqualTo(HandyRpcClient.NO_COMPLETION_ID);

        transport.receive(hspStateResponse(HandyRpcClient.NO_COMPLETION_ID, 500)); // the device answers anyway
        transport.receive(hspStateResponse(4242, 500)); // nobody ever sent this id

        // A regular request afterwards still works, so none of the dropped messages broke the reader
        Future<HandyRpc.Response> next = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        int nextId = transport.awaitWritten(2, 2_000).get(1).getRequest().getId();
        transport.receive(hspStateResponse(nextId, 111));

        assertThat(currentTimeOf(next.get(2, TimeUnit.SECONDS))).isEqualTo(111);
        assertThat(client.getPendingRequestCount()).isZero();
    }

    @Test
    void keepsWorkingAfterAnUnparseableMessage() throws Exception
    {
        transport.receiveRaw(new byte[]{0x08}); // truncated protobuf message
        transport.receiveRaw(new byte[]{(byte) 0xFF, (byte) 0xFF, (byte) 0xFF});

        Future<HandyRpc.Response> future = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        int id = transport.awaitWritten(1, 2_000).getFirst().getRequest().getId();
        transport.receive(hspStateResponse(id, 111));

        assertThat(currentTimeOf(future.get(2, TimeUnit.SECONDS))).isEqualTo(111);
    }

    @Test
    void routesNotificationsToTheListenerInsteadOfTheWaitingRequest() throws Exception
    {
        AtomicReference<HandyRpc.Notification> received = new AtomicReference<>();
        client.setNotificationListener(received::set);

        Future<HandyRpc.Response> future = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        int id = transport.awaitWritten(1, 2_000).getFirst().getRequest().getId();

        transport.receive(starvingNotification());
        awaitNotNull(received);
        assertThat(received.get().getNotificationCase()).isEqualTo(HandyRpc.Notification.NotificationCase.NOTIFICATION_HSP_STARVING);
        assertThat(future.isDone()).isFalse(); // a notification never completes a request

        transport.receive(hspStateResponse(id, 111));
        assertThat(currentTimeOf(future.get(2, TimeUnit.SECONDS))).isEqualTo(111);
    }

    @Test
    void throwsDeviceErrorWithItsCode() throws Exception
    {
        Future<HandyRpc.Response> future = executor.submit(() -> client.sendRequest(stateGetRequest(), 5_000));
        int id = transport.awaitWritten(1, 2_000).getFirst().getRequest().getId();
        transport.receive(errorResponse(id, 42, "HSP buffer full"));

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .rootCause()
                .isInstanceOf(HandyDeviceException.class)
                .hasMessageContaining("code=42")
                .hasMessageContaining("HSP buffer full");
    }

    @Test
    void failsPendingRequestsWhenClosed() throws Exception
    {
        Thread closer = new Thread(() ->
        {
            try
            {
                transport.awaitWritten(1, 2_000);
                client.close();
            }
            catch (InterruptedException e)
            {
                Thread.currentThread().interrupt();
            }
        });
        closer.start();

        assertThatThrownBy(() -> client.sendRequest(stateGetRequest(), 10_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    private static void awaitNotNull(AtomicReference<?> reference) throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 2_000;
        while (reference.get() == null && System.currentTimeMillis() < deadline)
        {
            Thread.sleep(5);
        }
        assertThat(reference.get()).isNotNull();
    }

    private static int currentTimeOf(HandyRpc.Response response)
    {
        return response.getResponseHspStateGet().getState().getCurrentTime();
    }

    private static HandyRpc.Request.Builder stateGetRequest()
    {
        return HandyRpc.Request.newBuilder()
                .setRequestHspStateGet(Messages.RequestHspStateGet.getDefaultInstance());
    }

    private static HandyRpc.RpcMessage hspStateResponse(int id, int currentTime)
    {
        return HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder()
                        .setId(id)
                        .setResponseHspStateGet(Messages.ResponseHspStateGet.newBuilder()
                                .setState(Constants.HspState.newBuilder().setCurrentTime(currentTime))))
                .build();
    }

    private static HandyRpc.RpcMessage errorResponse(int id, int code, String message)
    {
        return HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_RESPONSE)
                .setResponse(HandyRpc.Response.newBuilder()
                        .setId(id)
                        .setError(HandyRpc.Error.newBuilder().setCode(code).setMessage(message)))
                .build();
    }

    private static HandyRpc.RpcMessage starvingNotification()
    {
        return HandyRpc.RpcMessage.newBuilder()
                .setType(HandyRpc.MessageType.MESSAGE_TYPE_NOTIFICATION)
                .setNotification(HandyRpc.Notification.newBuilder()
                        .setNotificationHspStarving(Notifications.NotificationHspStarving.newBuilder()
                                .setState(Constants.HspState.newBuilder()
                                        .setPlayState(Constants.HspPlayState.HSP_STATE_STARVING))))
                .build();
    }
}
