package org.example.handy.wifi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.HandyError;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Minimal client for the parts of the <a href="https://www.handyfeeling.com/api/handy-rest/v3/docs/">Handy API v3</a>
 * that the app needs.
 * <p>
 * Every device call sends the application ID as {@code X-Api-Key} and the device connection key as
 * {@code X-Connection-Key}. Requests go through {@link HandyHttpPool}, which keeps a warm connection ready and
 * rotates it before the Handy servers drop it. A request that fails on the network is retried once on a fresh
 * connection; the device calls used by the app are idempotent.
 * <p>
 * The API answers device problems with HTTP 200 and an {@code error} object (for example
 * {@code DeviceNotConnected}). Those are converted to {@link HandyApiException}, which carries the
 * {@link HandyError} used by the rest of the app.
 */
@Slf4j
final class HandyWifiApi implements AutoCloseable
{
    static final URI DEFAULT_BASE_URI = URI.create("https://www.handyfeeling.com/api/handy-rest/v3/");

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ERROR_BODY_LENGTH = 300;

    private final URI baseUri;
    private final String applicationId;
    private final String connectionKey;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HandyHttpPool pool;

    HandyWifiApi(URI baseUri, String applicationId, String connectionKey) throws IOException
    {
        String base = baseUri.toString();
        this.baseUri = URI.create(base.endsWith("/") ? base : base + "/");
        this.applicationId = applicationId;
        this.connectionKey = connectionKey;
        this.pool = new HandyHttpPool(this.baseUri);
    }

    /** @return the response body of a {@code GET} device endpoint */
    JsonNode get(String path) throws IOException
    {
        return execute(request(path).GET().build());
    }

    /** @return the response body of a {@code PUT} device endpoint */
    JsonNode put(String path, ObjectNode body) throws IOException
    {
        return execute(request(path)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                .build());
    }

    @Override
    public void close()
    {
        pool.close();
    }

    /**
     * The API uses {@code result} in the documented endpoints but answers some device commands with {@code Result},
     * so both spellings are accepted.
     */
    static JsonNode result(JsonNode response)
    {
        if (response == null)
        {
            return MissingNode.getInstance();
        }
        JsonNode result = response.get("result");
        if (result == null || result.isNull())
        {
            result = response.get("Result");
        }
        return result == null ? MissingNode.getInstance() : result;
    }

    private HttpRequest.Builder request(String path)
    {
        return HttpRequest.newBuilder(baseUri.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "HandyVRC-OSC")
                .header("X-Api-Key", applicationId)
                .header("X-Connection-Key", connectionKey);
    }

    private JsonNode execute(HttpRequest request) throws IOException
    {
        IOException lastIoError = null;
        for (int attempt = 0; attempt < 2; attempt++)
        {
            HandyHttpPool.Slot slot = pool.acquire();
            HttpResponse<String> response;
            try
            {
                response = slot.client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            }
            catch (InterruptedException e)
            {
                pool.release(slot);
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while talking to the Handy servers", e);
            }
            catch (IOException e)
            {
                // A dropped connection (GOAWAY from the servers, a network hiccup) is retried once on a new, warm
                // connection. The request body is repeatable, so the same command can be sent again safely.
                pool.invalidate(slot);
                pool.release(slot);
                lastIoError = e;
                if (attempt == 0)
                {
                    log.debug("Handy server request failed ({}), retrying on a fresh connection", e.getMessage());
                    continue;
                }
                throw new IOException("Could not reach the Handy servers: " + e.getMessage(), e);
            }
            pool.release(slot);
            if (response.statusCode() >= 500 && attempt == 0)
            {
                // A transient server error: retry once, but do not throw the connection away.
                continue;
            }
            return handleResponse(response);
        }
        throw new IOException("Could not reach the Handy servers", lastIoError);
    }

    private JsonNode handleResponse(HttpResponse<String> response) throws IOException
    {
        int status = response.statusCode();
        JsonNode json = parse(response.body());
        if (status == 401)
        {
            throw new HandyApiException(status, new HandyError(status, "Unauthenticated",
                    "The Handy application ID was rejected by the Handy servers. Check the Application ID in the"
                            + " Wi-Fi settings.", false));
        }
        if (status == 403)
        {
            throw new HandyApiException(status, new HandyError(status, "Forbidden",
                    "The Handy application ID is not allowed to control this device.", false));
        }
        if (status == 429)
        {
            throw new HandyApiException(status, new HandyError(status, "RateLimited",
                    "The Handy servers are rate limiting this app, commands may be dropped. Try again in a moment.",
                    true));
        }
        if (status < 200 || status >= 300)
        {
            HandyError deviceError = deviceError(json);
            if (deviceError != null)
            {
                throw new HandyApiException(status, deviceError);
            }
            String message = errorMessage(json, response.body());
            throw new HandyApiException(status, new HandyError(status, "HttpError",
                    "The Handy servers answered HTTP %d: %s".formatted(status, message), false));
        }
        HandyError deviceError = deviceError(json);
        if (deviceError != null)
        {
            throw new HandyApiException(status, deviceError);
        }
        return json == null ? mapper.nullNode() : json;
    }

    private JsonNode parse(String body)
    {
        if (body == null || body.isBlank())
        {
            return null;
        }
        try
        {
            return mapper.readTree(body);
        }
        catch (IOException e)
        {
            return null;
        }
    }

    private static HandyError deviceError(JsonNode json)
    {
        if (json == null)
        {
            return null;
        }
        JsonNode error = json.get("error");
        if (error == null || error.isNull() || !error.isObject())
        {
            return null;
        }
        int code = error.path("code").asInt(0);
        String name = error.path("name").asText("");
        String message = error.path("message").asText("");
        boolean connected = error.path("connected").asBoolean(false);
        if (message.isBlank())
        {
            message = name.isBlank() ? "The Handy reported an error" : name;
        }
        return new HandyError(code, name, message, connected);
    }

    private static String errorMessage(JsonNode json, String body)
    {
        if (json != null)
        {
            JsonNode message = json.path("error").path("message");
            if (message.isTextual() && !message.asText().isBlank())
            {
                return message.asText();
            }
            JsonNode error = json.get("error");
            if (error != null && error.isTextual() && !error.asText().isBlank())
            {
                return error.asText();
            }
        }
        String text = body == null ? "" : body.strip();
        if (text.isEmpty())
        {
            return "no response body";
        }
        return text.length() <= MAX_ERROR_BODY_LENGTH
                ? text
                : text.substring(0, MAX_ERROR_BODY_LENGTH) + "...";
    }

    /** An unsuccessful response of the Handy servers, including the device error the API returned. */
    static final class HandyApiException extends IOException
    {
        private final int status;
        private final HandyError error;

        HandyApiException(int status, HandyError error)
        {
            super(error.message());
            this.status = status;
            this.error = error;
        }

        int status()
        {
            return status;
        }

        HandyError error()
        {
            return error;
        }
    }
}
