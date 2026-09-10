package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.*;

import java.util.List;
import java.util.Optional;

@Slf4j
public class HandyClientBle extends HandyClient
{
    private final HandyBleAdapter ble;
    private final HandyRpcClient rpc;

    public HandyClientBle(HandyBleAdapter ble)
    {
        this.ble = ble;
        this.rpc = new HandyRpcClient(ble);
    }

    @Override
    public HandyBaseResponseWithError changeMode(int mode)
    {
        // BLE does not require explicit mode change - mode is implicit in the commands sent
        log.debug("BLE: skipping changeMode({}), mode is implicit", mode);
        return new HandyBaseResponseWithError(null);
    }

    @Override
    public boolean checkConnectionStatus()
    {
        return ble.isConnected();
    }

    @Override
    public HandySetupResponse hspSetup()
    {
        // BLE does not require hsp setup
        log.debug("BLE: skipping HSP Setup");
        return new HandySetupResponse(null, new HandySetupResult(0));
//        try
//        {
//            int streamId = new Random().nextInt(Integer.MAX_VALUE);
//            log.info("BLE HSP Setup (streamId={})...", streamId);
//
//            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
//                    .setRequestHspSetup(Messages.RequestHspSetup.newBuilder()
//                            .setStreamId(streamId)
//                            .build()));
//
//            if (resp.hasResponseHspSetup())
//            {
//                Constants.HspState state = resp.getResponseHspSetup().getState();
//                log.info("BLE HSP state: {}, maxPoints={}, state={}", state.getPlayState(), state.getMaxPoints(), state);
//            }
//            // Return mocked response matching API format (no error = success)
//            return new HandySetupResponse(null, new HandySetupResult(0));
//        }
//        catch (Exception e)
//        {
//            throw new RuntimeException("BLE hspSetup failed", e);
//        }
    }

    @Override
    public HandyBaseResponseWithError hspPlay(long startTime, long serverTime, boolean pauseOnStarving)
    {
        try
        {
            log.info("BLE HSP Play...");

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestHspPlay(Messages.RequestHspPlay.newBuilder()
                            .setStartTime((int) startTime)
                            .setServerTime(serverTime)
                            .setPlaybackRate(1.0f)
                            .setLoop(false)
                            .setPauseOnStarving(pauseOnStarving)
                            .build()));

            if (resp.hasResponseHspPlay())
            {
                Constants.HspState state = resp.getResponseHspPlay().getState();
                log.info("BLE HSP playing, state={}", state.getPlayState());
            }
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE hspPlay failed", e);
        }
    }

    @Override
    public HandyHspAddResponse hspAdd(HspAddRequest requestBody)
    {
        try
        {
            List<Constants.Point> points = requestBody.points().stream()
                    .map(point -> Constants.Point.newBuilder()
                            .setT(point.t())
                            .setX(point.x())
                            .build())
                    .toList();

            rpc.sendRequestFireAndForget(HandyRpc.Request.newBuilder()
                    .setRequestHspAdd(Messages.RequestHspAdd.newBuilder()
                            .addAllPoints(points)
                            .setFlush(requestBody.flush())
                            .build()));

            log.trace("[BLE HSP] Sent {} points", points.size());

            // Return mocked success response - BLE fire-and-forget has no result payload
            return new HandyHspAddResponse(null, new HspState(1, 0, 2));
        }
        catch (Exception e)
        {
            log.error("Exception when sending BLE HSP points: {}", e.getMessage());
            return new HandyHspAddResponse(null, null);
        }
    }

    @Override
    public void setSliderSettings(Float min, Float max)
    {
        try
        {
            log.info("BLE SliderSet...");

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestSliderStrokeSet(Messages.RequestSliderStrokeSet.newBuilder()
                            .setMin(min)
                            .setMax(max)
                            .build()));

            if (resp.hasResponseSliderStrokeSet())
            {
                var state = resp.getResponseSliderStrokeSet();
                log.info("SliderSet Response {}", state);
            }
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE SliderSet failed", e);
        }
    }

    @Override
    public Optional<SliderSettingsResult> getSliderSettings()
    {
        try
        {
            log.info("BLE SliderGet...");

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestSliderStrokeGet(Messages.RequestSliderStrokeGet.newBuilder()
                            .build()));

            if (resp.hasResponseSliderStrokeGet())
            {
                var state = resp.getResponseSliderStrokeGet();
                log.info("SliderGet Response {}", state);
                return Optional.of(new SliderSettingsResult(String.valueOf(state.getMin()), String.valueOf(state.getMax())));
            }
            return Optional.empty();
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE SliderGet failed", e);
        }
    }

    @Override
    @SneakyThrows
    public void sendRequestForMessageDelayCalc()
    {
        rpc.sendRequest(HandyRpc.Request.newBuilder()
                .setRequestClockOffsetGet(Messages.RequestClockOffsetGet.getDefaultInstance()));
    }
}