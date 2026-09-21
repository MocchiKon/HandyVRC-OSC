package org.example.handy.v3;

import handy.api.HspApi;
import handy.invoker.ApiException;
import handy.model.HspAdd;
import org.example.handy.common.ConnectionRetry;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests the retry against a real HTTP connection: the Handy API closes a connection once it has served its
 * request limit, and a request that runs into that has to be sent again instead of being dropped.
 */
class ApiConnectionLossTest
{
    @Test
    void sendsTheBatchAgainWhenTheServerClosedTheConnection() throws Exception
    {
        try (var server = new FakeHandyApiServer(100, true))
        {
            var apiClients = new HandyApiClients("application-id", server.baseUri());
            try
            {
                var response = apiClients.call("hspAdd", clients -> clients.hspApi().hspAdd("connection-key", new HspAdd(), null));

                // the answer of the first connection was thrown away, so this is the response of the repeated request
                assertThat(response.getResult().getPoints()).isEqualTo(42);
                assertThat(server.requests()).isEqualTo(2);
            }
            finally
            {
                apiClients.close();
            }
        }
    }

    @Test
    void withoutTheRetryTheCallerSeesTheFailureOfTheRecycledConnection() throws Exception
    {
        try (var server = new FakeHandyApiServer(100, true))
        {
            var apiClient = new SharedHttpClientApiClient(HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
            apiClient.updateBaseUri(server.baseUri());
            var hspApi = new HspApi(apiClient);

            // the JDK does not repeat a POST by itself, so the dropped connection reaches the caller ...
            assertThatThrownBy(() -> hspApi.hspAdd("connection-key", new HspAdd(), null))
                    .isInstanceOf(ApiException.class)
                    // ... and is recognized as a connection that can simply be used again
                    .satisfies(e -> assertThat(ConnectionRetry.isConnectionFailure(e)).isTrue());
            assertThat(server.requests()).isEqualTo(1);
        }
    }
}
