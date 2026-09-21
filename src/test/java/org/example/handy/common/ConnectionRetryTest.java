package org.example.handy.common;

import handy.invoker.ApiException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Tests that a call whose connection was recycled by the API server is repeated, while failures that mean
 * something else (a rejected request, a broken configuration) are handed to the caller unchanged.
 */
class ConnectionRetryTest
{
    @Test
    void repeatsTheCallAndReturnsItsResultWhenTheConnectionWasRecycled() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();

        String result = ConnectionRetry.call("hspAdd", () ->
        {
            if (attempts.incrementAndGet() == 1)
            {
                throw goAway();
            }
            return "sent";
        });

        assertThat(result).isEqualTo("sent");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void givesUpAfterOneRetryWhenTheConnectionCannotBeReestablished() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConnectionRetry.call("hspAdd", () ->
        {
            attempts.incrementAndGet();
            throw goAway();
        })).isInstanceOf(ApiException.class)
                .hasMessageContaining("GOAWAY received");
        assertThat(attempts).hasValue(2);
    }

    @Test
    void doesNotRepeatFailuresThatAreNotAboutTheConnection() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();

        assertThatThrownBy(() -> ConnectionRetry.call("hspAdd", () ->
        {
            attempts.incrementAndGet();
            throw new ApiException(401, "Unauthorized");
        })).isInstanceOf(ApiException.class);
        assertThat(attempts).hasValue(1);
    }

    @Test
    void tellsTheCallerWhichFailureItIsAboutToRepeat() throws Exception
    {
        AtomicInteger attempts = new AtomicInteger();
        List<Exception> reported = new ArrayList<>();

        String result = ConnectionRetry.call("hspAdd", () ->
        {
            if (attempts.incrementAndGet() == 1)
            {
                throw goAway();
            }
            return "sent";
        }, reported::add);

        assertThat(result).isEqualTo("sent");
        assertThat(reported).hasSize(1);
        assertThat(reported.getFirst().getMessage()).contains("GOAWAY received");
    }

    @Test
    void doesNotReportFailuresThatAreNotRepeated() throws Exception
    {
        List<Exception> reported = new ArrayList<>();

        assertThatThrownBy(() -> ConnectionRetry.call("hspAdd", () ->
        {
            throw new ApiException(401, "Unauthorized");
        }, reported::add)).isInstanceOf(ApiException.class);

        assertThat(reported).isEmpty();
    }

    @Test
    void recognizesTheFailuresThatMeanTheConnectionIsGone()
    {
        // What the JDK HttpClient reports to every request that was in flight when the server recycled the
        // connection (JDK-8335181); the generated API client wraps it into an ApiException
        assertThat(ConnectionRetry.isConnectionFailure(goAway())).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(new ApiException(new IOException("EOF reached")))).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(new ApiException(new ConnectException("Connection refused")))).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(new ClosedChannelException())).isTrue();
        assertThat(ConnectionRetry.isConnectionFailure(new HttpConnectTimeoutException("Connect timed out"))).isTrue();
    }

    @Test
    void doesNotMistakeOtherFailuresForAConnectionFailure()
    {
        assertThat(ConnectionRetry.isConnectionFailure(new ApiException(401, "Unauthorized"))).isFalse();
        assertThat(ConnectionRetry.isConnectionFailure(new IOException("No value for parameter 'x'"))).isFalse();
        assertThat(ConnectionRetry.isConnectionFailure(new IOException())).isFalse();
        // the whole chain of causes is inspected, not only the outermost failure
        assertThat(ConnectionRetry.isConnectionFailure(new ApiException(new IOException("outer", new IOException("broken pipe"))))).isTrue();
    }

    /** The failure the JDK throws for requests that were in flight when the API server sent an HTTP/2 GOAWAY. */
    private static ApiException goAway()
    {
        return new ApiException(new IOException("/10.0.0.1:51234: GOAWAY received"));
    }
}
