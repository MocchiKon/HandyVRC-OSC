package org.example.handy.ble;

import dev.handy.proto.Constants;
import dev.handy.proto.HandyRpc;
import dev.handy.proto.Messages;
import handy.model.DeviceModeValue;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.example.handy.common.*;
import org.example.handy.common.dto.*;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeoutException;

@Slf4j
public class HandyClientBle extends HandyClient
{
    /** How long to wait for the device to confirm a batch of HSP points before treating its response as lost. */
    static final long DEFAULT_HSP_ADD_RESPONSE_TIMEOUT_MS = 1_000;
    /**
     * Upper bound for HSP adds that wait for their response at the same time. Responses are matched by id, so
     * this only limits how many of them are in flight: when the device stops answering, further batches are
     * streamed without waiting for a response instead of piling up threads and pending requests forever.
     */
    private static final int MAX_CONCURRENT_HSP_ADD_CHECKS = 16;

    private final HandyBleAdapter ble;
    private final HandyRpcClient rpc;
    private final long hspAddResponseTimeoutMs;
    private final Semaphore hspAddResponseSlots = new Semaphore(MAX_CONCURRENT_HSP_ADD_CHECKS);
    /** Streaming produces one check per batch, so repeating problems are summarized instead of logged per batch. */
    private final RateLimitedLogger hspAddWarnings = new RateLimitedLogger(log);
    private final RateLimitedLogger notificationWarnings = new RateLimitedLogger(log);

    public HandyClientBle(HandyBleAdapter ble)
    {
        this(ble, DEFAULT_HSP_ADD_RESPONSE_TIMEOUT_MS);
    }

    // Tests only: allows shortening the response timeout
    HandyClientBle(HandyBleAdapter ble, long hspAddResponseTimeoutMs)
    {
        this.ble = ble;
        this.hspAddResponseTimeoutMs = hspAddResponseTimeoutMs;
        this.rpc = new HandyRpcClient(ble);
        this.rpc.setNotificationListener(this::onNotification);
    }

    @Override
    public void close()
    {
        rpc.close();
    }

    @Override
    public HandyBaseResponseWithError changeMode(DeviceModeValue mode)
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
    public HandyBaseResponseWithError hspFlush()
    {
        try
        {
            HandyRpc.Response response = rpc.sendRequest(HandyRpc.Request.newBuilder()
                    .setRequestHspFlush(Messages.RequestHspFlush.newBuilder()));
            if (response.hasResponseHspFlush())
            {
                Constants.HspState state = response.getResponseHspFlush().getState();
                log.info("BLE HSP flushed, state={}", state);
            }
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            throw new RuntimeException("BLE hspFlush failed", e);
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
                            .setPlaybackRate(1.0f)
                            .setLoop(false)
                            .setPauseOnStarving(pauseOnStarving)
                            .build()));

            if (resp.hasResponseHspPlay())
            {
                Constants.HspState state = resp.getResponseHspPlay().getState();
                log.info("BLE HSP playing, state={}", state);
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
        List<Constants.Point> points = requestBody.points().stream()
                .map(point -> Constants.Point.newBuilder()
                        .setT(point.t())
                        .setX(point.x())
                        .build())
                .toList();

        HandyRpc.Request.Builder request = HandyRpc.Request.newBuilder()
                .setRequestHspAdd(Messages.RequestHspAdd.newBuilder()
                        .addAllPoints(points)
                        .setFlush(requestBody.flush()));

        log.trace("[BLE HSP] Sending {} points", points.size());

        if (!hspAddResponseSlots.tryAcquire())
        {
            hspAddWarnings.warn("Not waiting for the response of %d HSP point(s): %d add request(s) are still unanswered, streaming without a response check"
                    .formatted(points.size(), MAX_CONCURRENT_HSP_ADD_CHECKS));
            return sendHspAddWithoutResponseCheck(request);
        }

        try
        {
            // The device answers every request, so the batch can be verified instead of assuming it was accepted.
            HandyRpc.Response response = rpc.sendRequestRaw(request, hspAddResponseTimeoutMs);
            return handleHspAddResponse(requestBody, response);
        }
        catch (TimeoutException e)
        {
            return new HandyHspAddResponse(new HandyError(0, "HSP_ADD_TIMEOUT",
                    "No response for %d HSP point(s) within %dms (%s)".formatted(points.size(), hspAddResponseTimeoutMs, e.getMessage()), true), null);
        }
        catch (Exception e)
        {
            log.error("Exception when sending BLE HSP points: {}", e.getMessage());
            return new HandyHspAddResponse(new HandyError(0, "HSP_ADD_FAILED", e.getMessage(), true), null);
        }
        finally
        {
            hspAddResponseSlots.release();
        }
    }

    /** Sends the batch without waiting for (and therefore without checking) its response. */
    private HandyHspAddResponse sendHspAddWithoutResponseCheck(HandyRpc.Request.Builder request)
    {
        try
        {
            rpc.sendRequestFireAndForget(request);
            return new HandyHspAddResponse(null, null);
        }
        catch (Exception e)
        {
            log.error("Exception when sending BLE HSP points: {}", e.getMessage());
            return new HandyHspAddResponse(new HandyError(0, "HSP_ADD_FAILED", e.getMessage(), true), null);
        }
    }

    /**
     * Turns the response of an HSP add into a result: device errors and an unexpected (empty) response are
     * reported as an error, while the state the device returns is checked against the sent points so that
     * problems that would otherwise silently degrade the movement (skipped points, a starving or stopped
     * stream, a saturated point buffer) are reported.
     */
    private HandyHspAddResponse handleHspAddResponse(HspAddRequest requestBody, HandyRpc.Response response)
    {
        if (response.hasError() && response.getError().getCode() != 0)
        {
            return new HandyHspAddResponse(new HandyError(response.getError().getCode(), "HSP_ADD_FAILED",
                    "Device rejected the points: code=%d msg=%s".formatted(response.getError().getCode(), response.getError().getMessage()), true), null);
        }
        if (!response.hasResponseHspAdd())
        {
            return new HandyHspAddResponse(new HandyError(0, "HSP_ADD_EMPTY_RESPONSE",
                    "Device answered the HSP add with %s instead of a state".formatted(response.getResultCase()), true), null);
        }

        // A state that is missing entirely is reported by the checker as HSP_ADD_NO_STATE, like in API mode
        HspState state = response.getResponseHspAdd().hasState() ? toHspState(response.getResponseHspAdd().getState()) : null;
        HspAddCheck check = HspAddResponseChecker.check(requestBody.points(), state);
        check.warnings().forEach(hspAddWarnings::warn);
        log.trace("[BLE HSP] Response check: {}", check);
        return new HandyHspAddResponse(check.error(), state);
    }

    @Override
    public HandyBaseResponseWithError hdspXpt(float xp, int t, boolean stopOnTarget)
    {
        try
        {
            // Fire-and-forget: HDSP commands are sent continuously, waiting for a response would stall the stream.
            // The position is already normalized (0.0-1.0) by the processor, which owns the HDSP position unit.
            rpc.sendRequestFireAndForget(HandyRpc.Request.newBuilder()
                    .setRequestHdspXpTSet(Messages.RequestHdspXpTSet.newBuilder()
                            .setXp(xp)
                            .setT(t)
                            .setStopOnTarget(stopOnTarget)
                            .build()));

            log.trace("[BLE HDSP] Sent XPT (xp={}, t={}ms, stopOnTarget={})", xp, t, stopOnTarget);
            return new HandyBaseResponseWithError(null);
        }
        catch (Exception e)
        {
            log.error("Exception when sending BLE HDSP XPT command: {}", e.getMessage());
            return new HandyBaseResponseWithError(new HandyError(0, "HDSP_XPT_FAILED", e.getMessage(), true));
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

    /**
     * Handles the notifications the device pushes. The HSP ones (starving, paused, state changed) and the
     * low-memory ones explain why points are skipped or responses are missing, so they are reported instead of
     * being thrown away by whichever request happens to be waiting.
     */
    private void onNotification(HandyRpc.Notification notification)
    {
        switch (notification.getNotificationCase())
        {
            case NOTIFICATION_HSP_STARVING -> notificationWarnings.warn(
                    "[BLE HSP] Device ran out of points (starving notification): points arrive too late, increase 'pointsOffset'");
            case NOTIFICATION_HSP_PAUSED_ON_STARVING -> notificationWarnings.warn(
                    "[BLE HSP] Device paused because it ran out of points (pause_on_starving)");
            case NOTIFICATION_HSP_RESUMED_ON_NON_STARVING -> log.debug("[BLE HSP] Device resumed playing after new points arrived");
            case NOTIFICATION_HSP_THRESHOLD_REACHED -> log.trace("[BLE HSP] Device played the point it was asked to report");
            case NOTIFICATION_HSP_STATE_CHANGED -> log.trace("[BLE HSP] State changed: {}", notification.getNotificationHspStateChanged().getState());
            case NOTIFICATION_HSP_LOOPING -> log.trace("[BLE HSP] Stream is looping");
            case NOTIFICATION_LOW_MEMORY_ERROR -> notificationWarnings.warn(
                    "[BLE] Device dropped a message of %d bytes because it ran out of memory (available heap %d, largest free block %d)"
                            .formatted(notification.getNotificationLowMemoryError().getDiscardedMsgSize(),
                                    notification.getNotificationLowMemoryError().getAvailableHeap(),
                                    notification.getNotificationLowMemoryError().getLargestFreeBlock()));
            case NOTIFICATION_LOW_MEMORY_WARNING -> notificationWarnings.warn(
                    "[BLE] Device memory is running low (available heap %d)".formatted(notification.getNotificationLowMemoryWarning().getAvailableHeap()));
            case NOTIFICATION_ERROR -> notificationWarnings.warn(
                    "[BLE] Device reported an error: code=%d msg=%s".formatted(notification.getNotificationError().getCode(),
                            notification.getNotificationError().getMessage()));
            case NOTIFICATION_TEMP_HIGH -> notificationWarnings.warn("[BLE] Device is overheating and may stop moving");
            case NOTIFICATION_SLIDER_BLOCKED -> notificationWarnings.warn("[BLE] Slider is blocked");
            case NOTIFICATION_IDLE_TIMEOUT -> log.warn("[BLE] Device is idle and will disconnect/sleep soon");
            default -> log.trace("[BLE] Notification: {}", notification.getNotificationCase());
        }
    }

    private static HspState toHspState(Constants.HspState state)
    {
        return new HspState(state.getCurrentTime(),
                state.getFirstPointTime(),
                state.getLastPointTime(),
                state.getPoints(),
                state.getMaxPoints(),
                state.getCurrentPoint(),
                toHspPlayState(state.getPlayState()));
    }

    private static HspPlayState toHspPlayState(Constants.HspPlayState state)
    {
        // Mapped by number so that a state added by a newer firmware is not mistaken for a known one
        return switch (state.getNumber())
        {
            case 0 -> HspPlayState.NOT_INITIALIZED;
            case 1 -> HspPlayState.PLAYING;
            case 2 -> HspPlayState.STOPPED;
            case 3 -> HspPlayState.PAUSED;
            case 4 -> HspPlayState.STARVING;
            default -> HspPlayState.UNKNOWN;
        };
    }
}