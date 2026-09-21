package org.example.handy.v3;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import handy.model.HspAdd;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that the HTTP connection to the API is renewed before the server closes it, so that no batch has to wait
 * for a new connection (and its TCP + TLS handshake) to be established, and that the client complains when a
 * connection is given up long before its renewal.
 */
class ApiConnectionRotationTest
{
    private static final int REQUESTS = 250;
    /** The real API server recycles a connection after 100 requests. */
    private static final int SERVER_REQUEST_LIMIT = 100;

    @Test
    void movesToAWarmedUpConnectionBeforeTheServerClosesTheCurrentOne() throws Exception
    {
        try (var server = new FakeHandyApiServer(SERVER_REQUEST_LIMIT, false))
        {
            var apiClients = new HandyApiClients("application-id", server.baseUri());
            try
            {
                for (int i = 0; i < REQUESTS; i++)
                {
                    var response = apiClients.call("hspAdd", clients -> clients.hspApi().hspAdd("connection-key", new HspAdd(), null));
                    assertThat(response.getResult().getPoints()).isEqualTo(42);
                }
            }
            finally
            {
                apiClients.close();
            }

            // the client moves on before the limit, so the server never had to close a connection it served
            assertThat(server.maxRequestsOnOneConnection()).isLessThan(SERVER_REQUEST_LIMIT);
            // three connections for 250 requests (95 requests each) instead of the three the server would have cut off
            assertThat(server.connections()).isEqualTo(3);
            // every request of the test plus one warm up request on each replacement connection
            assertThat(server.requests()).isEqualTo(REQUESTS + 2);
        }
    }

    @Test
    void warnsWhenTheServerClosesAConnectionBeforeTheRenewal() throws Exception
    {
        // the server drops a connection it is serving, long before the renewal would have replaced it: the batch is
        // repeated (nothing is lost), but the client has to point out that its limit is wrong
        try (var server = new FakeHandyApiServer(SERVER_REQUEST_LIMIT, true))
        {
            ListAppender<ILoggingEvent> logs = attachLogCapture();
            var apiClients = new HandyApiClients("application-id", server.baseUri());
            try
            {
                var response = apiClients.call("hspAdd", clients -> clients.hspApi().hspAdd("connection-key", new HspAdd(), null));

                assertThat(response.getResult().getPoints()).isEqualTo(42);
                assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                        .contains("closed a connection after 1 request(s)")
                        .contains("before the renewal"));
            }
            finally
            {
                apiClients.close();
                detachLogCapture(logs);
            }
        }
    }

    @Test
    void renewsTheConnectionWhileRequestsAreInFlight() throws Exception
    {
        try (var server = new FakeHandyApiServer(SERVER_REQUEST_LIMIT, false))
        {
            var apiClients = new HandyApiClients("application-id", server.baseUri());
            try (var workers = Executors.newVirtualThreadPerTaskExecutor())
            {
                List<Future<Integer>> answers = new ArrayList<>();
                for (int worker = 0; worker < 4; worker++)
                {
                    answers.add(workers.submit(() ->
                    {
                        // like the streaming loop: several batches are in flight while the connection is renewed
                        int points = 0;
                        for (int i = 0; i < 60; i++)
                        {
                            points = apiClients.call("hspAdd", clients -> clients.hspApi().hspAdd("connection-key", new HspAdd(), null))
                                    .getResult().getPoints();
                            Thread.sleep(1);
                        }
                        return points;
                    }));
                }
                for (Future<Integer> answer : answers)
                {
                    // no batch may be lost or fail while the connection is handed over
                    assertThat(answer.get()).isEqualTo(42);
                }
            }
            finally
            {
                apiClients.close();
            }

            assertThat(server.connections()).isGreaterThan(1);
        }
    }

    private static ListAppender<ILoggingEvent> attachLogCapture()
    {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HandyApiClients.class);
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static void detachLogCapture(ListAppender<ILoggingEvent> appender)
    {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(HandyApiClients.class)).detachAppender(appender);
    }
}
