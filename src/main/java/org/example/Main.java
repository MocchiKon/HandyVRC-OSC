package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigLoader;
import org.example.config.ConfigProperties;
import org.example.handy.ble.HandyBleAdapter;
import org.example.handy.ble.HandyClientBle;
import org.example.handy.common.HandyClient;
import org.example.handy.v3.HandyClientV3;
import org.example.processor.HspParameterProcessor;
import org.example.processor.ParameterProcessor;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.io.File;
import java.io.IOException;
import java.nio.file.Path;

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

        HandyClient handyClient = initHandyClient(config);
        ParameterProcessor processor = initProcessor(handyClient, config);
//        processor.syncClock();
        processor.setValueChangeListener(val -> penetrationValueLabel.setText(String.valueOf(val)));

        if (config.testMode())
        {
            log.info("Running in test mode - generating fake data instead of listening for OSC");
            TestDataSource testDataSource = new TestDataSource(processor::actOnValueChange);
            testDataSource.start();
        }
        else
        {
            OscListener OSC = initOsc(config);
            OSC.registerListener(config.avatarParameter(), processor::actOnValueChange);
        }
        processor.run();
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

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            log.info("Shutdown hook: disconnecting BLE...");
            try
            {
                ble.disconnect();
            }
            catch (Exception e)
            {
                log.error("Error while disconnecting BLE in shutdown hook", e);
            }
            log.info("BLE disconnected in shutdown hook.");
            }, "BLE-Shutdown-thread"));

        ble.connect();
        log.info("Handy connected via BLE...");
        return new HandyClientBle(ble);
    }

    private static ParameterProcessor initProcessor(HandyClient handyClient, ConfigProperties config)
    {
        return switch (config.processingAlgorithm())
        {
            case HSP -> new HspParameterProcessor(handyClient, config);
        };
    }

    private static OscListener initOsc(ConfigProperties config) throws IOException
    {
        try
        {
            return new OscListener(config.listenOnPort());
        }
        catch (IOException e)
        {
            log.error("Could not initialize OSC Listener!", e);
            throw e;
        }
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