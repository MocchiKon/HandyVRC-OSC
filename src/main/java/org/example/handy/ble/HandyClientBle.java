package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.HandyBaseResponseWithError;
import org.example.handy.common.HandyClient;
import org.example.handy.common.dto.*;

import java.util.List;
import java.util.Optional;
import java.util.Random;

@Slf4j
public class HandyClientBle implements HandyClient
{
    private final HandyBleAdapter ble;
    private final HandyRpcClient rpc;
    private int estimatedOffset = 0;

    public HandyClientBle(HandyBleAdapter ble)
    {
        this.ble = ble;
        this.rpc = new HandyRpcClient(ble);
    }

    private long estimatedServerTime()
    {
        return System.currentTimeMillis() + estimatedOffset;
    }

    @Override
    public HandyBaseResponseWithError changeMode(int mode)
    {
        // BLE does not require explicit mode change — mode is implicit in the commands sent
        log.info("BLE: changeMode({}) is a no-op, mode is implicit", mode);
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
        try
        {
            int streamId = new Random().nextInt(Integer.MAX_VALUE);
            log.info("BLE HSP Setup (streamId={})...", streamId);

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestHspSetup(Messages.RequestHspSetup.newBuilder()
                            .setStreamId(streamId)
                            .build()));

            if (resp.hasResponseHspSetup())
            {
                Constants.HspState state = resp.getResponseHspSetup().getState();
                log.info("BLE HSP state: {}, maxPoints={}, state={}", state.getPlayState(), state.getMaxPoints(), state);
            }
            // Return mocked response matching API format (no error = success)
            return new HandySetupResponse(null, new HandySetupResult((int) estimatedServerTime()));
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE hspSetup failed", e);
        }
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
//                            .setServerTime(estimatedServerTime())
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

            // Return mocked success response — BLE fire-and-forget has no result payload
            int currentTime = (int) (estimatedServerTime());
            int firstT = requestBody.points().getFirst().t();
            int lastT = requestBody.points().getLast().t();
            return new HandyHspAddResponse(null, new HspState(currentTime, firstT-1, lastT));
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
    public long syncClock()
    {
        try
        {
            log.info("Synchronizing BLE clock...");
            final int SYNC_SAMPLES = 30;
            double offsetSum = 0;
            double rtdSum = 0;

            for (int i = 0; i < SYNC_SAMPLES; i++)
            {
                long tSend = System.currentTimeMillis();
                HandyRpc.Response response = rpc.sendRequest(HandyRpc.Request.newBuilder()
                        .setRequestClockOffsetGet(Messages.RequestClockOffsetGet.getDefaultInstance()));
                long tReceive = System.currentTimeMillis();

                if (response.hasResponseClockOffsetGet())
                {
                    log.info("Recieved: {}", response.getResponseClockOffsetGet());
                    // clock_offset was 1777813502495 for BLE
                }

                long rtd = tReceive - tSend;
                offsetSum += rtd / 2.0;
                rtdSum += rtd;
                Thread.sleep(100); // ???
            }

            estimatedOffset = (int) Math.round(offsetSum / SYNC_SAMPLES);
            int avgRtd = (int) Math.round(rtdSum / SYNC_SAMPLES);
            log.info("BLE clock sync: avg RTD={}ms, offset={}ms", avgRtd, estimatedOffset);

            HandyRpc.Response resp = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestClockOffsetGet(Messages.RequestClockOffsetGet.getDefaultInstance()));

            int deviceTime = resp.getResponseClockOffsetGet().getTime();
            long clockOffset = estimatedServerTime() - deviceTime;

            HandyRpc.Response response = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestClockOffsetSet(Messages.RequestClockOffsetSet.newBuilder()
                            .setClockOffset(clockOffset)
                            .setRtd(avgRtd)
                            .build()));

            log.info("BLE clock synced (clockOffset={})", clockOffset);
            log.info("BLE clock SET response: {}", response);
            return estimatedOffset;
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE clock sync failed", e);
        }
    }
}