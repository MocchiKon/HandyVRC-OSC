package org.example.handy.v3;

import handy.api.HspApi;
import handy.api.InfoApi;
import handy.api.SliderApi;
import handy.api.UtilsApi;
import handy.invoker.ApiClient;
import handy.model.*;
import handy.model.HspState;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.HandyError;
import org.example.handy.common.dto.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

// Firmware 4.x only
@Slf4j
public class HandyClientV3 extends HandyClient
{
    private final String deviceConnectionKey;
    private final String applicationId;
    private volatile HspApi hspApi;
    private volatile InfoApi infoApi;
    private volatile SliderApi sliderApi;
    private volatile UtilsApi utilsApi;
    private final AtomicInteger requestCount = new AtomicInteger(0);

    public HandyClientV3(String deviceConnectionKey, String applicationId)
    {
        this.deviceConnectionKey = deviceConnectionKey;
        this.applicationId = applicationId;
        rebuildApiClients();
    }

    private synchronized void rebuildApiClients()
    {
        ApiClient authenticatedClient = newAuthenticatedApiClient();
        this.hspApi = new HspApi(authenticatedClient);
        this.infoApi = new InfoApi(authenticatedClient);
        this.sliderApi = new SliderApi(authenticatedClient);
        this.utilsApi = new UtilsApi(authenticatedClient);
    }

    private ApiClient newAuthenticatedApiClient()
    {
        var client = new ApiClient();
        HandyApiClientAuth.applyApiKey(client, applicationId);
        return client;
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError changeMode(DeviceModeValue mode)
    {
        var setModeRequest = new SetModeRequest();
        setModeRequest.setMode(mode);
        var response = infoApi.setMode2(deviceConnectionKey, setModeRequest, null);
        return new HandyBaseResponseWithError(toHandyError(response.getError()));
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving)
    {
        var playRequest = new HspPlayRequest();
        playRequest.setStartTime(Math.toIntExact(startTime));
        playRequest.setServerTime(Math.toIntExact(serverTime));
        playRequest.setPlaybackRate(BigDecimal.ONE);
        playRequest.setPauseOnStarving(pauseOnStarving);
        playRequest.setLoop(false);
        log.info("Starting HSP stream ({})", playRequest);
        var response = hspApi.hspPlay(deviceConnectionKey, playRequest, null);
        log.info("Started HSP stream (response=[{}])", response);
        return new HandyBaseResponseWithError(toHandyError(response.getError()));
    }

    @SneakyThrows
    @Override
    public HandyHspAddResponse hspAdd(HspAddRequest requestBody)
    {
        var hspAdd = new HspAdd();
        List<Point> points = new ArrayList<>(requestBody.points().size());
        for (HspPoint point : requestBody.points())
        {
            var apiPoint = new Point();
            apiPoint.setT(point.t());
            apiPoint.setX(point.x());
            points.add(apiPoint);
        }
        hspAdd.setPoints(points);
        hspAdd.setFlush(requestBody.flush());

        log.trace("Sending points to HSP stream ({})", hspAdd);
        var response = hspApi.hspAdd(deviceConnectionKey, hspAdd, null);
        attemptRefreshingApiClients();
        var handyHspAddResponse = new HandyHspAddResponse(toHandyError(response.getError()), toHspState(response.getResult()));
        log.trace("Sent points to HSP stream (response={})", response);
        if (handyHspAddResponse.error() == null && handyHspAddResponse.result() == null)
        {
            log.error("Recieved potentially empty response ({})", response);
        }
        return handyHspAddResponse;
    }

    @SneakyThrows
    @Override
    public HandySetupResponse hspSetup()
    {
        var setupRequest = new HspSetupRequest();
        setupRequest.setStreamId(1);
        log.info("Initializing HSP stream with id 1");
        var response = hspApi.hspSetup(deviceConnectionKey, null, setupRequest);
        // NOTE: current_time - ms since device boot
        log.info("Initialized HSP stream with id 1 ({})", response);
        return toSetupResponse(response);
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError hspFlush()
    {
        var response = hspApi.hspFlush(deviceConnectionKey, null);
        return new HandyBaseResponseWithError(toHandyError(response.getError()));
    }

    @SneakyThrows
    @Override
    public boolean checkConnectionStatus()
    {
        var response = infoApi.isConnected(deviceConnectionKey, null);
        if (response.getResult() == null)
        {
            log.error("Error when checking connection to Handy! (reason: {})", response);
            return false;
        }
        return Boolean.TRUE.equals(response.getResult().getConnected());
    }

    @SneakyThrows
    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        var response = sliderApi.getStroke(deviceConnectionKey, null);
        if (response.getResult() == null)
        {
            log.error("Error when checking slider settings! (reason: {})", response);
            return Optional.empty();
        }
        return Optional.of(new SliderSettingsResult(toPlainString(response.getResult().getMin()), toPlainString(response.getResult().getMax())));
    }

    @SneakyThrows
    @Override
    public void setSliderSettings(Float min, Float max)
    {
        if (min == null && max == null)
        {
            return;
        }

        BigDecimal resolvedMin = min != null ? BigDecimal.valueOf(min.doubleValue()) : null;
        BigDecimal resolvedMax = max != null ? BigDecimal.valueOf(max.doubleValue()) : null;
        if (resolvedMin == null || resolvedMax == null)
        {
            var current = sliderApi.getStroke(deviceConnectionKey, null);
            if (current.getResult() == null)
            {
                log.error("Error when reading slider settings before update! (reason: {})", current);
                return;
            }
            if (resolvedMin == null)
            {
                resolvedMin = current.getResult().getMin();
            }
            if (resolvedMax == null)
            {
                resolvedMax = current.getResult().getMax();
            }
        }

        var strokeSettings = new StrokeSettings();
        strokeSettings.setMin(resolvedMin);
        strokeSettings.setMax(resolvedMax);
        log.info("Setting slider limits min={}, max={}...", resolvedMin, resolvedMax);
        sliderApi.setStroke(deviceConnectionKey, strokeSettings, null);
    }

    @SneakyThrows
    public long getServerTime()
    {
        var response = utilsApi.getServerTime();
        return response.getServerTime().longValue();
    }

    @Override
    @SneakyThrows
    public void sendRequestForMessageDelayCalc()
    {
        getSliderSettings();
    }

    private boolean attemptRefreshingApiClients() // Prevent GOAWAY (every 94 requests on my machine)
    {
        int count = requestCount.incrementAndGet();
        if (count >= 30)
        {
            requestCount.set(0);
            rebuildApiClients();
            log.trace("Refreshed API clients");
            return true;
        }
        return false;
    }

    private HandyError toHandyError(DeviceError error)
    {
        if (error == null)
        {
            return null;
        }
        return new HandyError(error.getCode() != null ? error.getCode() : 0,
                error.getName(),
                error.getMessage(),
                Boolean.TRUE.equals(error.getConnected()));
    }

    private org.example.handy.common.dto.HspState toHspState(HspState state)
    {
        if (state == null)
        {
            return null;
        }
        return new org.example.handy.common.dto.HspState(state.getCurrentTime() != null ? state.getCurrentTime() : 0,
                state.getFirstPointTime(),
                state.getLastPointTime());
    }

    private HandySetupResponse toSetupResponse(GetHsspState200Response response)
    {
        if (response == null)
        {
            return new HandySetupResponse(null, null);
        }
        HandySetupResult result = null;
        if (response.getResult() != null && response.getResult().getCurrentTime() != null)
        {
            result = new HandySetupResult(response.getResult().getCurrentTime());
        }
        return new HandySetupResponse(toHandyError(response.getError()), result);
    }

    private String toPlainString(BigDecimal value)
    {
        return value != null ? value.toPlainString() : null;
    }
}
