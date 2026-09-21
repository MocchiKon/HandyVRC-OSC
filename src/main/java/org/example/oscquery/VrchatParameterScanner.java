package org.example.oscquery;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;

import java.io.Closeable;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Reads the avatar parameters that VRChat exposes through OSCQuery and logs the penetrator/orifice candidates
 * found among them, so that the configured {@code avatarParameter} can be validated without guessing.
 * <p>
 * The scan runs on its own daemon thread and never touches the OSC message path: OSC values are still received and
 * processed exactly as before, and the tree is only re-read when the set of parameters changed (which happens when
 * an avatar is loaded or switched).
 */
@Slf4j
public class VrchatParameterScanner implements Closeable
{
    /** Reading the tree of VRChat must never delay anything, so every request has a short timeout. */
    private static final Duration HTTP_TIMEOUT = Duration.ofSeconds(3);
    /** Retry interval while no avatar parameters could be read yet (VRChat or the avatar may still be loading). */
    private static final long SCAN_INTERVAL_WITHOUT_PARAMETERS_MS = 10_000;
    /** Interval used afterwards: parameters only change when another avatar is loaded. */
    private static final long SCAN_INTERVAL_WITH_PARAMETERS_MS = 60_000;
    private static final String VRCHAT_SERVICE_NAME_MARKER = "vrchat";

    private static final String STATE_WAITING_FOR_SERVICE = "waiting-for-service";
    private static final String STATE_WAITING_FOR_PARAMETERS = "waiting-for-parameters";
    private static final String STATE_REPORTED = "reported";

    private final ConfigProperties config;
    private final OscQueryTreeClient treeClient;
    private final BiConsumer<OscQueryServiceProfile, List<OscQueryNode>> reportConsumer;
    private final Map<String, OscQueryServiceProfile> discoveredServices = new ConcurrentHashMap<>();
    private final Thread scanThread;

    private volatile boolean running = true;
    private volatile boolean parametersFound;
    private volatile int lastReportedFingerprint;
    private String lastLoggedState = "";

    private VrchatParameterScanner(ConfigProperties config,
                                   BiConsumer<OscQueryServiceProfile, List<OscQueryNode>> reportConsumer)
    {
        this.config = config;
        this.reportConsumer = reportConsumer;
        this.treeClient = new OscQueryTreeClient(HTTP_TIMEOUT);
        this.scanThread = new Thread(this::scanLoop, "OSCQuery-ParameterScanner");
        this.scanThread.setDaemon(true);
    }

    /**
     * Starts scanning in the background. Services that were already discovered are picked up as well.
     *
     * @param discoverySource registers the listener that is called for every discovered OSCQuery service
     *                        (usually {@code oscQueryService::addDiscoveryListener})
     */
    public static VrchatParameterScanner start(Consumer<Consumer<OscQueryServiceProfile>> discoverySource,
                                               ConfigProperties config)
    {
        return start(discoverySource, config, (profile, parameters) ->
        {
            log.info("Received {} avatar parameter(s) from the OSCQuery service '{}' - validating config against them",
                    parameters.size(), profile.name());
            SpsParameterValidator.logReport(parameters, config.avatarParameter(), config.penetratorTipParameter(),
                    config.spsType());
        });
    }

    /** Visible for tests: the report consumer decides what happens with the avatar parameters that were read. */
    static VrchatParameterScanner start(Consumer<Consumer<OscQueryServiceProfile>> discoverySource,
                                        ConfigProperties config,
                                        BiConsumer<OscQueryServiceProfile, List<OscQueryNode>> reportConsumer)
    {
        var scanner = new VrchatParameterScanner(config, reportConsumer);
        discoverySource.accept(scanner::onServiceDiscovered);
        scanner.scanThread.start();
        return scanner;
    }

    @Override
    public void close()
    {
        running = false;
        scanThread.interrupt();
    }

    private void onServiceDiscovered(OscQueryServiceProfile profile)
    {
        discoveredServices.put(profile.name(), profile);
    }

    private void scanLoop()
    {
        while (running)
        {
            try
            {
                scanOnce();
            }
            catch (Exception e)
            {
                log.debug("OSCQuery parameter scan failed: {}", e.getMessage());
            }
            sleep(parametersFound ? SCAN_INTERVAL_WITH_PARAMETERS_MS : SCAN_INTERVAL_WITHOUT_PARAMETERS_MS);
        }
    }

    /** Visible for tests. */
    void scanOnce()
    {
        List<OscQueryServiceProfile> candidates = candidates();
        if (candidates.isEmpty())
        {
            logStateOnce(STATE_WAITING_FOR_SERVICE,
                    "Waiting for VRChat's OSCQuery service to appear (it only exists while VRChat runs with OSC"
                            + " enabled)");
            return;
        }
        for (OscQueryServiceProfile profile : candidates)
        {
            List<OscQueryNode> parameters = treeClient.fetchAvatarParameters(profile);
            if (!parameters.isEmpty())
            {
                parametersFound = true;
                reportIfChanged(profile, parameters);
                return;
            }
        }
        logStateOnce(STATE_WAITING_FOR_PARAMETERS, ("Found %d OSCQuery service(s) but no avatar parameters yet - load an"
                + " avatar in VRChat to validate the configured parameters").formatted(candidates.size()));
    }

    /** VRChat's own service is preferred, but any service that exposes avatar parameters can be used. */
    private List<OscQueryServiceProfile> candidates()
    {
        return discoveredServices.values().stream()
                .sorted(Comparator
                        .comparing((OscQueryServiceProfile profile) ->
                                !profile.name().toLowerCase().contains(VRCHAT_SERVICE_NAME_MARKER))
                        .thenComparing(OscQueryServiceProfile::name))
                .toList();
    }

    private void reportIfChanged(OscQueryServiceProfile profile, List<OscQueryNode> parameters)
    {
        int fingerprint = fingerprint(parameters);
        if (fingerprint == lastReportedFingerprint)
        {
            return; // Same avatar parameters as last time, no need to log (and validate) them again
        }
        lastReportedFingerprint = fingerprint;
        lastLoggedState = STATE_REPORTED;
        reportConsumer.accept(profile, parameters);
    }

    private static int fingerprint(List<OscQueryNode> parameters)
    {
        return parameters.stream()
                .map(node -> node.path() + "|" + node.type() + "|" + node.access())
                .sorted()
                .toList()
                .hashCode();
    }

    private void logStateOnce(String state, String message)
    {
        if (state.equals(lastLoggedState))
        {
            return;
        }
        lastLoggedState = state;
        log.info(message);
    }

    private void sleep(long millis)
    {
        try
        {
            Thread.sleep(millis);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            running = false;
        }
    }
}
