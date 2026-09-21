package org.example.handy.common;

import lombok.extern.slf4j.Slf4j;

import java.io.EOFException;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.nio.channels.ClosedChannelException;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Retries an API call that failed because its HTTP connection was closed by the server or lost.
 * <p>
 * The Handy API closes every connection after a fixed number of requests (the nginx style
 * {@code keepalive_requests} limit; it is 100 requests on handyfeeling.com at the time of writing), which is
 * signalled to the client as an HTTP/2 GOAWAY frame. Depending on the JDK the HttpClient is running on, that
 * frame either makes the client transparently resend the requests that the server did not process on a new
 * connection (JDK 21.0.8/17.0.17 and newer, see JDK-8335181) or it makes every request that was in flight fail
 * with an {@code IOException: ...: GOAWAY received}. The latter drops the points of a batch and interrupts the
 * HSP stream, so such a connection level failure is repeated here on a fresh connection.
 * <p>
 * Repeating a call is safe here: a GOAWAY names the last stream the server did process, so the failed request was
 * not processed at all, and for the remaining connection level failures repeating is harmless as well (the device
 * commands are idempotent and HSP points carry their own timestamps, so a point that arrives twice cannot move
 * the device anywhere it was not asked to move).
 */
@Slf4j
public final class ConnectionRetry
{
    /** One retry is enough: a new connection can only fail again if the network itself is broken. */
    private static final int MAX_ATTEMPTS = 2;

    /** A recycled connection produces one event every ~100 requests (about every 5 seconds while streaming). */
    private static final RateLimitedLogger reconnectLog = new RateLimitedLogger(log, 60_000);

    /** Messages of connection level failures that mean "this connection is gone, the request can be repeated". */
    private static final List<String> CONNECTION_FAILURE_MESSAGES = List.of(
            "goaway",                       // HTTP/2 connection recycled by the server (JDK-8335181, JDK-8371903)
            "request not processed by peer",// the JDK could not resend an unprocessed request (after its own retry)
            "refused_stream",               // HTTP/2 stream refused by the server: the request was not processed
            "rst_stream",
            "connection reset",
            "connection closed",
            "broken pipe",
            "eof reached",
            "too many concurrent streams");

    private ConnectionRetry()
    {
    }

    /**
     * Runs the given call and repeats it once if it failed because of a lost/recycled connection.
     *
     * @param action name of the call, used in the log message
     * @throws Exception the failure of the last attempt (also when it is not a connection failure)
     */
    public static <T> T call(String action, Call<T> call) throws Exception
    {
        return call(action, call, null);
    }

    /**
     * Runs the given call and repeats it once if it failed because of a lost/recycled connection.
     *
     * @param action  name of the call, used in the log message
     * @param onRetry called with the connection level failure that is about to be repeated, for callers that can
     *                add context to it (for example how many requests the lost connection had served)
     * @throws Exception the failure of the last attempt (also when it is not a connection failure)
     */
    public static <T> T call(String action, Call<T> call, Consumer<Exception> onRetry) throws Exception
    {
        for (int attempt = 1; ; attempt++)
        {
            try
            {
                return call.execute();
            }
            catch (Exception e)
            {
                if (attempt >= MAX_ATTEMPTS || !isConnectionFailure(e))
                {
                    throw e;
                }
                if (onRetry != null)
                {
                    onRetry.accept(e);
                }
                reconnectLog.warn("Lost connection to the Handy API in %s (%s), retrying on a new connection..."
                        .formatted(action, e.getMessage()));
            }
        }
    }

    /**
     * Tells whether an exception (or one of its causes) says that the connection to the API is gone, which is the
     * only kind of failure that can be repeated without any risk of the request having been processed already.
     */
    public static boolean isConnectionFailure(Throwable failure)
    {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
        {
            if (cause instanceof ConnectException || cause instanceof ClosedChannelException
                    || cause instanceof EOFException || cause instanceof HttpConnectTimeoutException)
            {
                return true;
            }
            String message = cause.getMessage();
            if (message == null)
            {
                continue;
            }
            String lowerCaseMessage = message.toLowerCase(Locale.ROOT);
            if (CONNECTION_FAILURE_MESSAGES.stream().anyMatch(lowerCaseMessage::contains))
            {
                return true;
            }
        }
        return false;
    }

    /** A call to the Handy API that may fail with a connection level error. */
    @FunctionalInterface
    public interface Call<T>
    {
        T execute() throws Exception;
    }
}
