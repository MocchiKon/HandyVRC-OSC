package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;
import org.example.handy.ble.HandyBleAdapter;
import org.example.handy.ble.HandyClientBle;
import org.example.oscquery.OscQueryService;
import org.example.oscquery.OscQueryServiceProfile;
import org.example.oscquery.VrchatParameterScanner;
import org.example.processor.HdspParameterProcessor;
import org.example.processor.HdspSmoothedParameterProcessor;
import org.example.processor.ParameterProcessor;
import org.example.processor.SpsType;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * Starts and stops the app on request of the GUI: connects the Handy over Bluetooth, opens the OSC port (announced
 * to VRChat over OSCQuery), and runs the processor that turns the avatar parameters into device commands.
 * <p>
 * In test mode the avatar parameters do not come from VRChat but from the GUI ({@link #feedTestInput}), so the
 * mode and the connection to the device can be tried out without VRChat.
 * <p>
 * Every operation runs on one background thread, in the order it was requested, so the GUI never blocks (scanning
 * for the device can take several seconds) and two operations can never interfere with each other. The Bluetooth
 * connection is kept when the app is stopped or restarted with new settings, so applying a setting is quick.
 */
@Slf4j
public class AppController implements AutoCloseable
{
    private static final long CONNECTION_CHECK_INTERVAL_MS = 2_000;
    /**
     * Length (in the 0-1 proximity units of SPS) of the penetrator simulated in test mode in ORIFICE mode. Only its
     * root and tip proximity reach the processor, which detects the length from them like it does for a real one.
     */
    static final float TEST_PENETRATOR_LENGTH = 0.1f;

    public enum State
    {
        STOPPED, CONNECTING, RUNNING, ERROR
    }

    /**
     * @param message  what is going on, for the user
     * @param testMode whether the session is (or was being) started in test mode
     */
    public record Status(State state, String message, boolean testMode)
    {
        public Status(State state, String message)
        {
            this(state, message, false);
        }
    }

    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(runnable ->
    {
        Thread thread = new Thread(runnable, "app-controller");
        thread.setDaemon(true);
        return thread;
    });
    private final Consumer<Status> statusListener;
    private final IntConsumer positionListener;

    private volatile Status status = new Status(State.STOPPED, "");

    // Only used on the worker thread (volatile for the shutdown hook)
    private volatile HandyBleAdapter ble;
    private volatile HandyClientBle client;
    private boolean shutdownHookRegistered;
    private OscEndpoint osc;
    private ParameterProcessor processor;
    private Runnable unregisterParameterListener;
    private VrchatParameterScanner scanner;
    private Consumer<OscQueryServiceProfile> scannerDiscoveryListener;
    /** True while a test mode session is started or running. */
    private volatile boolean testMode;
    /** The processor of a running test mode session (null otherwise), fed by {@link #feedTestInput}. */
    private volatile ParameterProcessor testProcessor;
    private volatile SpsType testSpsType;

    /**
     * @param statusListener   notified (on a background thread) whenever the status changes
     * @param positionListener notified (on a background thread) with every position (0-100) sent to the device
     */
    public AppController(Consumer<Status> statusListener, IntConsumer positionListener)
    {
        this.statusListener = statusListener;
        this.positionListener = positionListener;
        worker.scheduleWithFixedDelay(this::checkDeviceConnection, CONNECTION_CHECK_INTERVAL_MS,
                CONNECTION_CHECK_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    public Status getStatus()
    {
        return status;
    }

    /** Connects the device (when it is not connected yet) and starts processing the avatar parameters. */
    public void start(ConfigProperties config)
    {
        worker.execute(() ->
        {
            if (status.state() == State.RUNNING || status.state() == State.CONNECTING)
            {
                return;
            }
            startSession(config, false);
        });
    }

    /**
     * Starts test mode: like {@link #start}, but instead of listening for VRChat the movement is taken from
     * {@link #feedTestInput}. A running session (normal or test) is stopped first.
     *
     * @param config the settings, with the SPS type the user wants to test
     */
    public void startTest(ConfigProperties config)
    {
        worker.execute(() ->
        {
            stopSession(true);
            startSession(config, true);
        });
    }

    /**
     * Moves the device in test mode. Ignored unless a test mode session is running.
     *
     * @param insertion how deep the penetrator is inside the orifice, 0 (outside) to 1 (fully inside)
     */
    public void feedTestInput(float insertion)
    {
        ParameterProcessor processor = testProcessor;
        if (processor == null)
        {
            return;
        }
        float depth = Math.clamp(insertion, 0f, 1f);
        if (testSpsType == SpsType.PENETRATOR)
        {
            processor.actOnValueChange(depth);
            return;
        }
        float rootProximity = testRootProximity(depth);
        processor.actOnProximityChange(rootProximity, rootProximity + TEST_PENETRATOR_LENGTH);
    }

    /**
     * @return the root proximity SPS reports for a penetrator of {@link #TEST_PENETRATOR_LENGTH} that is inserted
     * this deep (0-1); its tip proximity is this plus the length
     */
    static float testRootProximity(float depth)
    {
        return 1f - (1f - depth) * TEST_PENETRATOR_LENGTH;
    }

    /** Applies new settings: a running session is restarted with them, the Bluetooth connection is kept. */
    public void restart(ConfigProperties config)
    {
        worker.execute(() ->
        {
            if (status.state() != State.RUNNING)
            {
                return;
            }
            boolean test = testMode;
            stopSession(false);
            startSession(config, test);
        });
    }

    public void stop()
    {
        worker.execute(() ->
        {
            stopSession(true);
            testMode = false;
            setStatus(State.STOPPED, "Stopped");
        });
    }

    /** Stops everything and disconnects the device. Waits (briefly) until that is done. */
    @Override
    public void close()
    {
        worker.execute(() ->
        {
            stopSession(true);
            disconnectDevice();
        });
        worker.shutdown();
        try
        {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS))
            {
                log.warn("Shutting down took too long, exiting anyway");
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }
    }

    private void startSession(ConfigProperties config, boolean test)
    {
        testMode = test;
        String configError = config.validate();
        if (configError != null)
        {
            setStatus(State.ERROR, configError);
            return;
        }
        try
        {
            if (!isDeviceConnected())
            {
                setStatus(State.CONNECTING, "Searching for your Handy over Bluetooth...");
                connectDevice();
            }
            setStatus(State.CONNECTING, "Preparing your Handy...");
            processor = switch (config.processingAlgorithm())
            {
                case HDSP -> new HdspParameterProcessor(client, config);
                case HDSP_SMOOTHED -> new HdspSmoothedParameterProcessor(client, config);
            };
            processor.setValueChangeListener(positionListener::accept);
            if (test)
            {
                processor.run();
                testSpsType = config.spsType();
                testProcessor = processor;
                log.info("Test mode running ({} mode, moving the {})", config.processingAlgorithm().getLabel(),
                        config.spsType() == SpsType.PENETRATOR ? "penetrator" : "orifice");
                setStatus(State.RUNNING, "Test mode: move the %s in the Test mode window to move your Handy."
                        .formatted(config.spsType() == SpsType.PENETRATOR ? "penetrator" : "orifice"));
                return;
            }

            setStatus(State.CONNECTING, "Opening the OSC port...");
            openOscEndpoint(config);
            unregisterParameterListener = registerParameterListener(osc.listener(), config, processor);
            startParameterScanner(config);
            processor.run();
            log.info("Running ({} mode, {})", config.processingAlgorithm().getLabel(), config.spsType());
            setStatus(State.RUNNING, osc.describe());
        }
        catch (Exception e)
        {
            log.error("Could not start", e);
            stopSession(true);
            setStatus(State.ERROR, "Could not start: " + describe(e));
        }
    }

    /**
     * Stops sending commands and stops the move the device is on.
     *
     * @param closeOsc whether the OSC port is closed as well (a restart keeps it when the settings allow it)
     */
    private void stopSession(boolean closeOsc)
    {
        testProcessor = null;
        if (unregisterParameterListener != null)
        {
            unregisterParameterListener.run();
            unregisterParameterListener = null;
        }
        stopParameterScanner();
        if (processor != null)
        {
            processor.stop();
            processor = null;
            if (client != null)
            {
                client.hdspStop();
            }
        }
        if (closeOsc && osc != null)
        {
            osc.close();
            osc = null;
        }
        positionListener.accept(100); // The device rests at the top of the stroke
    }

    private void checkDeviceConnection()
    {
        if (status.state() == State.RUNNING && !isDeviceConnected())
        {
            log.error("Lost the Bluetooth connection to the Handy");
            stopSession(true);
            setStatus(State.ERROR, "Lost the connection to your Handy. Press Start to reconnect.");
        }
    }

    private boolean isDeviceConnected()
    {
        return client != null && ble != null && ble.isConnected();
    }

    private void connectDevice()
    {
        disconnectDevice(); // A connection that was lost is replaced by a new one
        boolean bluetoothEnabled;
        try
        {
            bluetoothEnabled = HandyBleAdapter.isBluetoothEnabled();
        }
        catch (Throwable e)
        {
            throw new IllegalStateException("Bluetooth is not available on this computer (" + e.getMessage() + ")", e);
        }
        if (!bluetoothEnabled)
        {
            throw new IllegalStateException("Bluetooth is turned off. Turn it on and press Start again.");
        }
        var newBle = new HandyBleAdapter();
        newBle.connect();
        ble = newBle;
        client = new HandyClientBle(newBle);
        log.info("Handy connected via BLE...");
        registerShutdownHook();
    }

    private void disconnectDevice()
    {
        HandyClientBle oldClient = client;
        HandyBleAdapter oldBle = ble;
        client = null;
        ble = null;
        try
        {
            if (oldClient != null)
            {
                // Fails the requests that still wait for a response before the connection goes away
                oldClient.close();
            }
            if (oldBle != null)
            {
                oldBle.disconnect();
            }
        }
        catch (Exception e)
        {
            log.warn("Error while disconnecting BLE: {}", e.getMessage());
        }
    }

    /** Disconnects the device when the app is ended in another way than by closing the window. */
    private void registerShutdownHook()
    {
        if (shutdownHookRegistered)
        {
            return;
        }
        shutdownHookRegistered = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() ->
        {
            if (ble == null)
            {
                return;
            }
            log.info("Shutdown hook: disconnecting BLE...");
            disconnectDevice();
        }, "BLE-Shutdown-thread"));
    }

    /**
     * Registers the listener for the avatar parameters that drive the app. VRChat sends the root and the tip
     * proximity of a penetrator in the same OSC packet, so in ORIFICE mode both are passed to the processor together.
     */
    private static Runnable registerParameterListener(OscListener osc, ConfigProperties config, ParameterProcessor processor)
    {
        String rootParameter = config.avatarParameter();
        if (config.spsType() == SpsType.PENETRATOR)
        {
            return osc.registerPacketListener(List.of(rootParameter), values -> processor.actOnValueChange(values.get(rootParameter)));
        }
        String tipParameter = config.penetratorTipParameter();
        log.info("Penetrator length will be auto-detected from root proximity '{}' and tip proximity '{}'", rootParameter, tipParameter);
        return osc.registerPacketListener(List.of(rootParameter, tipParameter),
                values -> processor.actOnProximityChange(values.get(rootParameter), values.get(tipParameter)));
    }

    /** Opens the OSC port, or keeps the one that is open when it was opened with the same settings. */
    private void openOscEndpoint(ConfigProperties config) throws IOException
    {
        if (osc != null && osc.matches(config))
        {
            return;
        }
        if (osc != null)
        {
            osc.close();
            osc = null;
        }
        osc = OscEndpoint.open(config);
    }

    /** Logs whether the avatar parameter matches one that VRChat reports (a validation aid, OSCQuery only). */
    private void startParameterScanner(ConfigProperties config)
    {
        OscQueryService oscQueryService = osc.oscQueryService();
        if (oscQueryService == null)
        {
            return;
        }
        scanner = VrchatParameterScanner.start(listener ->
        {
            scannerDiscoveryListener = listener;
            oscQueryService.addDiscoveryListener(listener);
        }, config);
    }

    private void stopParameterScanner()
    {
        if (scanner == null)
        {
            return;
        }
        scanner.close();
        if (osc != null && osc.oscQueryService() != null && scannerDiscoveryListener != null)
        {
            osc.oscQueryService().removeDiscoveryListener(scannerDiscoveryListener);
        }
        scanner = null;
        scannerDiscoveryListener = null;
    }

    private void setStatus(State state, String message)
    {
        status = new Status(state, message, testMode);
        try
        {
            statusListener.accept(status);
        }
        catch (Exception e)
        {
            log.error("Status listener failed", e);
        }
    }

    private static String describe(Throwable e)
    {
        Throwable cause = e;
        while (cause.getMessage() == null && cause.getCause() != null)
        {
            cause = cause.getCause();
        }
        return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
    }

    /**
     * The OSC port the app receives the avatar parameters on.
     *
     * @param oscQueryService null when OSCQuery is not used
     * @param port            port the listener is bound to
     */
    private record OscEndpoint(OscListener listener, OscQueryService oscQueryService, int port,
                               boolean useOscQuery, int configuredPort)
    {
        static OscEndpoint open(ConfigProperties config) throws IOException
        {
            if (!config.useOscQuery())
            {
                log.info("OSCQuery is disabled, using simple port listening on port {}", config.listenOnPort());
                return plain(config);
            }
            try
            {
                return openWithOscQuery(config);
            }
            catch (IOException e)
            {
                log.error("Could not start OSCQuery ({}), falling back to simple port listening on port {}. VRChat will"
                        + " only reach this app on that port if no other application uses it.", e.getMessage(),
                        config.listenOnPort());
                return plain(config);
            }
        }

        private static OscEndpoint plain(ConfigProperties config) throws IOException
        {
            try
            {
                return new OscEndpoint(new OscListener(config.listenOnPort()), null, config.listenOnPort(),
                        config.useOscQuery(), config.listenOnPort());
            }
            catch (IOException e)
            {
                throw new IOException("Port %d is already in use by another application. Turn on OSCQuery or choose"
                        .formatted(config.listenOnPort()) + " another port.", e);
            }
        }

        /**
         * Binds the OSC listener to a free port and lets VRChat know about it through OSCQuery. Because VRChat is
         * told where to send messages, this keeps working when another application already listens on
         * 'listenOnPort'.
         */
        private static OscEndpoint openWithOscQuery(ConfigProperties config) throws IOException
        {
            IOException lastError = null;
            for (int attempt = 0; attempt < 5; attempt++)
            {
                int port = OscQueryService.findFreePort();
                OscListener oscListener;
                try
                {
                    oscListener = new OscListener(port);
                }
                catch (IOException e)
                {
                    lastError = e; // The port was taken in the meantime, try another one
                    continue;
                }
                try
                {
                    OscQueryService oscQueryService = OscQueryService.start(port);
                    log.info("OSCQuery is enabled: VRChat will send OSC messages to port {} (the configured port {} is"
                            + " ignored while OSCQuery is used)", port, config.listenOnPort());
                    return new OscEndpoint(oscListener, oscQueryService, port, true, config.listenOnPort());
                }
                catch (IOException e)
                {
                    lastError = e;
                    oscListener.close();
                }
            }
            throw new IOException("Could not open a free OSC port and announce it through OSCQuery", lastError);
        }

        boolean matches(ConfigProperties config)
        {
            return useOscQuery == config.useOscQuery() && configuredPort == config.listenOnPort();
        }

        String describe()
        {
            return oscQueryService != null
                    ? "Receiving from VRChat on port %d (found through OSCQuery)".formatted(port)
                    : "Receiving from VRChat on port %d".formatted(port);
        }

        void close()
        {
            if (oscQueryService != null)
            {
                oscQueryService.close();
            }
            listener.close();
        }
    }
}
