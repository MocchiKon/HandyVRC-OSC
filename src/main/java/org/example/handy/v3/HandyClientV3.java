package org.example.handy.v3;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.v3.dto.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// Firmware 4.x only
@Slf4j
public class HandyClientV3 implements HandyClient
{
    private static final String BASE_URI = "https://www.handyfeeling.com/api/handy-rest/v3/";
    public static final String DEVICE_CONNECTION_KEY_HEADER = "X-Connection-Key";
    public static final String APPLICATION_ID_KEY_HEADER = "X-Api-Key";
    private final String deviceConnectionKey;
    private final String applicationId;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper;
    int requestSum = 0;
    int requestNo = 0;
    private final AtomicInteger requestCount = new AtomicInteger(0);

    public HandyClientV3(String deviceConnectionKey, String applicationId)
    {
        this.httpClient = HttpClient.newHttpClient();
        this.deviceConnectionKey = deviceConnectionKey;
        this.applicationId = applicationId;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError changeMode(int mode)
    {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "mode"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"mode\":%s}".formatted(mode)))
                .build();
        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return objectMapper.readValue(httpResponse.body(), HandyBaseResponseWithError.class);
    }

    @SneakyThrows
    public HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving)
    {
        String body = "{\"start_time\":%s,\"server_time\":%s,\"playback_rate\":1,\"pause_on_starving\":%s,\"loop\":false}".formatted(startTime, serverTime, pauseOnStarving);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "hsp/play"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        log.info("Starting HSP stream ({})", body);
        var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Started HSP stream (response=[{}])", httpResponse.body());
        return objectMapper.readValue(httpResponse.body(), HandyBaseResponseWithError.class);
    }

    @SneakyThrows
    public HandyHspAddResponse hspAdd(HspAddRequest requestBody)
    {
        String body = objectMapper.writeValueAsString(requestBody);
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "hsp/add"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        log.trace("Sending points to HSP stream ({})", body);
        long start = System.currentTimeMillis();
        var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        attemptRefreshingHttpClient();
        if (log.isTraceEnabled())
        {
            long tookMs = System.currentTimeMillis() - start;
            requestSum += (int) tookMs;
            requestNo++;
            log.trace("Request took {} ms, current average {} ms", tookMs, requestSum / requestNo);
        }
        HandyHspAddResponse handyHspAddResponse = objectMapper.readValue(httpResponse.body(), HandyHspAddResponse.class);
        log.trace("Sent points to HSP stream (response={})", httpResponse.body());
        if (handyHspAddResponse.error() == null && handyHspAddResponse.result() == null)
        {
            log.error("Recieved potentially empty response ({})", httpResponse.body());
        }
        return handyHspAddResponse;
    }

    @SneakyThrows
    public HandySetupResponse hspSetup()
    {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "hsp/setup"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .PUT(HttpRequest.BodyPublishers.ofString("{\"stream_id\":1}"))
                .build();
        log.info("Initializing HSP stream with id 1");
        var httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Initialized HSP stream with id 1 ({})", httpResponse.body());
        return objectMapper.readValue(httpResponse.body(), HandySetupResponse.class);
    }

    @SneakyThrows
    @Override
    public boolean checkConnectionStatus()
    {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "connected"))
                .header("accept", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .GET()
                .build();
        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        ConnectionStatusResponse response = objectMapper.readValue(httpResponse.body(), ConnectionStatusResponse.class);
        if (response.result() == null)
        {
            log.error("Error when checking connection to Handy! (reason: {})", response);
            return false;
        }
        return response.result().connected();
    }

    @SneakyThrows
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "slider/stroke"))
                .header("accept", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .GET()
                .build();
        HttpResponse<String> httpResponse = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        SliderSettingsResponse response = objectMapper.readValue(httpResponse.body(), SliderSettingsResponse.class);
        if (response.result() == null)
        {
            log.error("Error when checking slider settings! (reason: {})", response);
            return Optional.empty();
        }
        return Optional.of(response.result());
    }

    @SneakyThrows
    public void setSliderSettings(Float min, Float max)
    {
        String body;
        if (ObjectUtils.allNotNull(min, max))
        {
            body = "{\"min\":%s, \"max\":%s}".formatted(min, max);
        }
        else if (min != null)
        {
            body = "{\"min\":%s}".formatted(min);
        }
        else if (max != null)
        {
            body = "{\"max\":%s}".formatted(max);
        }
        else
        {
            return;
        }

        var request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URI + "slider/stroke"))
                .header("accept", "application/json")
                .header("Content-Type", "application/json")
                .header(DEVICE_CONNECTION_KEY_HEADER, deviceConnectionKey)
                .header(APPLICATION_ID_KEY_HEADER, applicationId)
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        log.info("Setting slider limits {}...", body);
        httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private boolean attemptRefreshingHttpClient() // Prevent GOAWAY (every 94 requests on my machine)
    {
        int count = requestCount.incrementAndGet();
        if (count >= 80)// TODO Param config?
        {
            requestCount.set(0);
            httpClient = HttpClient.newHttpClient();
            log.trace("Refreshed http client");
            return true;
        }
        return false;
    }
}
