package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigLoader;
import org.example.config.ConfigProperties;
import org.example.handy.ble.HandyBleAdapter;
import org.example.handy.ble.HandyClientBle;
import org.example.handy.common.HandyClient;
import org.example.handy.v3.HandyClientV3;
import org.example.oscquery.OscQueryService;
import org.example.oscquery.VrchatParameterScanner;
import org.example.processor.HdspParameterProcessor;
import org.example.processor.HspParameterProcessor;
import org.example.processor.ParameterProcessor;
import org.example.processor.SpsType;
import org.example.update.StartupUpdateCheck;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

@Slf4j
public class Main
{
    public static void main(String[] args)
    {
        try
        {
            runApp();
        }
        catch (Exception e)
        {
            delayedClosingWithLog("Error occurred while initializing: %s! Closing app...".formatted(e.getMessage()), e);
        }
    }

    private static void runApp() throws IOException
    {
        JLabel penetrationValueLabel = setupGui();
        var configLoader = new ConfigLoader(getAppConfigPath());
        ConfigProperties config = configLoader.readOrInitConfig();

        StartupUpdateCheck.runIfEnabled(config); // Runs in the background, so it never delays the startup

        HandyClient handyClient = initHandyClient(config);
        ParameterProcessor processor = initProcessor(handyClient, config);
        processor.setValueChangeListener(val -> penetrationValueLabel.setText(String.valueOf(val)));

        if (config.testMode())
        {
            log.info("Running in test mode - generating fake data instead of listening for OSC");
            TestDataSource testDataSource = new TestDataSource(testValueConsumer(config, processor));
            testDataSource.start();
        }
        else
        {
            OscListener osc = initOsc(config);
            registerParameterListeners(osc, config, processor);
        }
        processor.run();
    }

    /**
     * Registers listeners for the avatar parameters that drive the app. VRChat sends the root and the tip proximity
     * of a penetrator in the same OSC packet, so in ORIFICE mode both are passed to the processor together.
     */
    private static void registerParameterListeners(OscListener osc, ConfigProperties config, ParameterProcessor processor)
    {
        String rootParameter = config.avatarParameter();
        if (config.spsType() == SpsType.PENETRATOR)
        {
            osc.registerPacketListener(List.of(rootParameter), values -> processor.actOnValueChange(values.get(rootParameter)));
            return;
        }
        String tipParameter = config.penetratorTipParameter();
        osc.registerPacketListener(List.of(rootParameter, tipParameter),
                values -> processor.actOnProximityChange(values.get(rootParameter), values.get(tipParameter)));
    }

    /**
     * Test mode generates a 0-1 sawtooth. In PENETRATOR mode it is the fake penetration amount, while in ORIFICE mode
     * it is the fake insertion amount, which is converted into the root/tip proximity pair of a simulated penetrator
     * so that the length auto-detection is exercised as well.
     */
    private static Consumer<Float> testValueConsumer(ConfigProperties config, ParameterProcessor processor)
    {
        if (config.spsType() == SpsType.PENETRATOR)
        {
            return processor::actOnValueChange;
        }
        return insertionAmount ->
        {
            float rootProximity = 1.f - (1.f - insertionAmount) * TestDataSource.SIMULATED_PENETRATOR_LENGTH;
            processor.actOnProximityChange(rootProximity, rootProximity + TestDataSource.SIMULATED_PENETRATOR_LENGTH);
        };
    }

    private static JLabel setupGui()
    {
        var frame = new JFrame();
        frame.setSize(200, 200);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        var label = new JLabel("0", SwingConstants.CENTER);
        label.setFont(getFontWithCalculatedFontSize(frame));
        label.setToolTipText("Current penetration value collected via OSC");
        frame.add(label);

        frame.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                super.componentResized(e);
                label.setFont(getFontWithCalculatedFontSize(frame));
            }
        });
        frame.setLocationRelativeTo(null); // Center window
        frame.setVisible(true);
        return label;
    }

    private static Font getFontWithCalculatedFontSize(JFrame frame)
    {
        return new Font("Arial", Font.BOLD, calculateFontSize(frame));
    }

    private static int calculateFontSize(JFrame frame)
    {
        int size = Math.min(frame.getBounds().height, frame.getBounds().width);
        return Math.max((int) (size * 0.5f), 10);
    }

    private static Path getAppConfigPath()
    {
        File jarPath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        String propertiesPath = jarPath.getParentFile().getAbsolutePath();
        return Path.of(propertiesPath + "/" + "app.properties");
    }

    private static HandyClient initHandyClient(ConfigProperties config)
    {
        return switch (config.connectionMode())
        {
            case API -> initApiHandyClient(config);
            case BLUETOOTH -> initBleHandyClient();
        };
    }

    private static HandyClient initApiHandyClient(ConfigProperties config)
    {
        var client = new HandyClientV3(config.deviceConnectionKey(), config.handyApplicationId());
        if (!client.checkConnectionStatus())
        {
            delayedClosingWithLog("Handy not connected! Check your connection and 'deviceConnectionKey' in config. Closing app...");
        }
        log.info("Handy connected via API...");
        return client;
    }

    private static HandyClient initBleHandyClient()
    {
        // NOTE: NativeLibraryLoader.loadLibrary("simplejavable"); is not necessary as it is loaded during start-up by simplejavable Adapter class
        if (!HandyBleAdapter.isBluetoothEnabled())
        {
            delayedClosingWithLog("Bluetooth is not enabled! Please enable Bluetooth and restart the app. Closing app...");
        }

        HandyBleAdapter ble = new HandyBleAdapter();

        ble.connect();
        log.info("Handy connected via BLE...");
        HandyClientBle client = new HandyClientBle(ble);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook: disconnecting BLE...");
            try
            {
                // Fails the requests that still wait for a response before the connection goes away
                client.close();
                ble.disconnect();
            }
            catch (Exception e)
            {
                log.error("Error while disconnecting BLE in shutdown hook", e);
            }
            log.info("BLE disconnected in shutdown hook.");
            }, "BLE-Shutdown-thread"));

        return client;
    }

    private static ParameterProcessor initProcessor(HandyClient handyClient, ConfigProperties config)
    {
        return switch (config.processingAlgorithm())
        {
            case HSP -> new HspParameterProcessor(handyClient, config);
            case HDSP -> new HdspParameterProcessor(handyClient, config);
        };
    }

    private static OscListener initOsc(ConfigProperties config) throws IOException
    {
        if (!config.useOscQuery())
        {
            log.info("OSCQuery is disabled, using simple port listening on port {}", config.listenOnPort());
            return new OscListener(config.listenOnPort());
        }
        try
        {
            return initOscWithOscQuery(config);
        }
        catch (IOException e)
        {
            log.error("Could not start OSCQuery ({}), falling back to simple port listening on port {}. VRChat will"
                    + " only reach this app on that port if no other application uses it.", e.getMessage(),
                    config.listenOnPort());
            return new OscListener(config.listenOnPort());
        }
    }

    /**
     * Binds the OSC listener to a free port and lets VRChat know about it through OSCQuery. Because VRChat is told
     * where to send messages, this keeps working when another application already listens on 'listenOnPort'.
     */
    private static OscListener initOscWithOscQuery(ConfigProperties config) throws IOException
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
                VrchatParameterScanner.start(oscQueryService::addDiscoveryListener, config);
                log.info("OSCQuery is enabled: VRChat will send OSC messages to port {} (port {} from app.properties is"
                        + " ignored while OSCQuery is used)", port, config.listenOnPort());
                return oscListener;
            }
            catch (IOException e)
            {
                lastError = e;
                oscListener.close();
            }
        }
        throw new IOException("Could not open a free OSC port and announce it through OSCQuery", lastError);
    }

    public static void delayedClosingWithLog(String msg)
    {
        delayedClosingWithLog(msg, null);
    }

    public static void delayedClosingWithLog(String msg, Exception exception)
    {
        try
        {
            log.error(msg, exception);
            JOptionPane.showMessageDialog(null, msg, "Error", JOptionPane.ERROR_MESSAGE); // This waits until user closes error pop-up
        }
        catch (Exception e)
        {
            System.exit(1);
        }
        System.exit(1);
    }
}