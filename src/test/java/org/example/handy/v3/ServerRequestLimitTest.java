package org.example.handy.v3;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Measures how many requests the Handy API serves on one connection and checks it against the limit the client
 * renews its connection for.
 * <p>
 * The client deliberately does not measure this itself: that would cost a connection of its own and about as many
 * requests as the limit, in the background of every app start (the server marks the last response of a connection
 * with {@code Connection: close}, which is the only signal an application can see - a JDK that handles the HTTP/2
 * GOAWAY frame itself hides it). Run this test when the API is suspected to behave differently:
 * <pre>./mvnw test -Dtest=ServerRequestLimitTest -Dhandy.measureServerRequestLimit=true</pre>
 */
@EnabledIfSystemProperty(named = "handy.measureServerRequestLimit", matches = "true")
class ServerRequestLimitTest
{
    /** Requests after which the test gives up: a limit above this does not hurt the client, it only renews early. */
    private static final int MAX_REQUESTS = 300;

    @Test
    void servesTheNumberOfRequestsPerConnectionThatTheClientAssumes() throws Exception
    {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        try
        {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://www.handyfeeling.com/api/handy-rest/v3/servertime"))
                    .GET()
                    .build();
            for (int served = 1; served <= MAX_REQUESTS; served++)
            {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                if (servesItsLastRequest(response))
                {
                    assertThat(served)
                            .as("the API serves %d request(s) on one connection, the client renews for %d: adjust HandyApiClients.SERVER_REQUEST_LIMIT",
                                    served, HandyApiClients.SERVER_REQUEST_LIMIT)
                            .isEqualTo(HandyApiClients.SERVER_REQUEST_LIMIT);
                    return;
                }
            }
            assertThat(HandyApiClients.SERVER_REQUEST_LIMIT)
                    .as("the API did not close the connection within %d requests, the client renews for %d",
                            MAX_REQUESTS, HandyApiClients.SERVER_REQUEST_LIMIT)
                    .isGreaterThan(MAX_REQUESTS);
        }
        finally
        {
            client.close();
        }
    }

    private static boolean servesItsLastRequest(HttpResponse<?> response)
    {
        return response.headers().firstValue("connection")
                .map(value -> value.toLowerCase(Locale.ROOT).contains("close"))
                .orElse(false);
    }
}
