package org.example.handy.v3;

import handy.api.HspApi;
import handy.model.*;
import handy.model.HspState;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.*;
import org.example.handy.common.dto.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

// Firmware 4.x only
@Slf4j
public class HandyClientV3 extends HandyClient
{
    private final String deviceConnectionKey;
    private final HandyApiClients apiClients;
    /** Streaming produces one check per batch, so repeating problems are summarized instead of logged per batch. */
    private final RateLimitedLogger hspAddWarnings = new RateLimitedLogger(log);

    public HandyClientV3(String deviceConnectionKey, String applicationId)
    {
        this.deviceConnectionKey = deviceConnectionKey;
        this.apiClients = new HandyApiClients(applicationId);
    }

    // Tests only: talks to the given HSP API implementation instead of the real one
    HandyClientV3(HspApi hspApi)
    {
        this.deviceConnectionKey = null;
        this.apiClients = new HandyApiClients(new HandyApiClients.Clients(hspApi, null, null, null));
    }

    @Override
    public void close()
    {
        apiClients.close();
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError changeMode(DeviceModeValue mode)
    {
        var setModeRequest = new SetModeRequest();
        setModeRequest.setMode(mode);
        var response = apiClients.call("changeMode", clients -> clients.infoApi().setMode2(deviceConnectionKey, setModeRequest, null));
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
        var response = apiClients.call("hspPlay", clients -> clients.hspApi().hspPlay(deviceConnectionKey, playRequest, null));
        log.info("Started HSP stream (response=[{}])", response);
        return new HandyBaseResponseWithError(toHandyError(response.getError()));
    }

    @SneakyThrows
    @Override
    public HandyHspAddResponse hspAdd(HspAddRequest requestBody)
    {
        var hspAdd = new HspAdd();
        List<Point> points = new ArrayList<>(requestBody.points().size());
        for (MovementPoint point : requestBody.points())
        {
            var apiPoint = new Point();
            apiPoint.setT(point.t());
            apiPoint.setX(point.x());
            points.add(apiPoint);
        }
        hspAdd.setPoints(points);
        hspAdd.setFlush(requestBody.flush());

        log.trace("Sending points to HSP stream ({})", hspAdd);
        // A connection that the server closed in the meantime is not an error the caller has to handle: the batch
        // is simply sent again on a new connection (the points still carry their original timestamps).
        var response = apiClients.call("hspAdd", clients -> clients.hspApi().hspAdd(deviceConnectionKey, hspAdd, null));
        log.trace("Sent points to HSP stream (response={})", response);

        HandyError deviceError = toHandyError(response.getError());
        org.example.handy.common.dto.HspState state = toHspState(response.getResult());
        if (deviceError != null)
        {
            // The device rejected the batch, so the state it returned (usually empty) is not worth checking
            return new HandyHspAddResponse(deviceError, state);
        }

        // The server answers an accepted batch with the resulting device state, which is checked against the sent
        // points exactly like in Bluetooth mode: skipped points, a starving or stopped stream, a saturated buffer.
        HspAddCheck check = HspAddResponseChecker.check(requestBody.points(), state);
        check.warnings().forEach(hspAddWarnings::warn);
        log.trace("[HSP] Response check: {}", check);
        return new HandyHspAddResponse(check.error(), state);
    }

    @SneakyThrows
    @Override
    public HandySetupResponse hspSetup()
    {
        var setupRequest = new HspSetupRequest();
        setupRequest.setStreamId(1);
        log.info("Initializing HSP stream with id 1");
        var response = apiClients.call("hspSetup", clients -> clients.hspApi().hspSetup(deviceConnectionKey, null, setupRequest));
        // NOTE: current_time - ms since device boot
        log.info("Initialized HSP stream with id 1 ({})", response);
        return toSetupResponse(response);
    }

    @SneakyThrows
    @Override
    public HandyBaseResponseWithError hspFlush()
    {
        var response = apiClients.call("hspFlush", clients -> clients.hspApi().hspFlush(deviceConnectionKey, null));
        return new HandyBaseResponseWithError(toHandyError(response.getError()));
    }

    @SneakyThrows
    @Override
    public boolean checkConnectionStatus()
    {
        var response = apiClients.call("checkConnectionStatus", clients -> clients.infoApi().isConnected(deviceConnectionKey, null));
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
        var response = apiClients.call("getSliderSettings", clients -> clients.sliderApi().getStroke(deviceConnectionKey, null));
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
            var current = apiClients.call("getSliderSettings", clients -> clients.sliderApi().getStroke(deviceConnectionKey, null));
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
        apiClients.call("setSliderSettings", clients ->
        {
            clients.sliderApi().setStroke(deviceConnectionKey, strokeSettings, null);
            return null;
        });
    }

    @SneakyThrows
    public long getServerTime()
    {
        var response = apiClients.call("getServerTime", clients -> clients.utilsApi().getServerTime());
        return response.getServerTime().longValue();
    }

    @Override
    @SneakyThrows
    public void sendRequestForMessageDelayCalc()
    {
        getSliderSettings();
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
                state.getLastPointTime(),
                state.getPoints(),
                state.getMaxPoints() != null ? state.getMaxPoints().intValue() : null,
                state.getCurrentPoint(),
                toHspPlayState(state.getPlayState()));
    }

    private org.example.handy.common.dto.HspPlayState toHspPlayState(handy.model.HspPlayState state)
    {
        if (state == null)
        {
            return null;
        }
        return switch (state)
        {
            case NOT_INITIALIZED -> org.example.handy.common.dto.HspPlayState.NOT_INITIALIZED;
            case PLAYING -> org.example.handy.common.dto.HspPlayState.PLAYING;
            case STOPPED -> org.example.handy.common.dto.HspPlayState.STOPPED;
            case PAUSED -> org.example.handy.common.dto.HspPlayState.PAUSED;
            case STARVING -> org.example.handy.common.dto.HspPlayState.STARVING;
        };
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
