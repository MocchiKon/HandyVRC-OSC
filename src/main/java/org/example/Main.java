package org.example;

import lombok.extern.slf4j.Slf4j;
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
    public static void main(String[] args) throws IOException
    {
        JLabel penetrationValueLabel = setupGui();
        var configLoader = new ConfigLoader(getAppConfigPath());
        ConfigProperties config = configLoader.readOrInitConfig();
        ParameterProcessor processor = initProcessorAndHandyClient(config);
        OscListener OSC = initOsc(config);
        OSC.registerListener(config.avatarParameter(), processor::actOnValueChange);
        processor.setValueChangeListener(val -> penetrationValueLabel.setText(String.valueOf(val)));
        processor.run();
//        configLoader.runReloading(processor::refreshConfig);
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

    private static ParameterProcessor initProcessorAndHandyClient(ConfigProperties config)
    {
        return switch (config.processingAlgorithm())
        {
            case HSP -> new HspParameterProcessor(getHandyClientV3AndValidateConnection(config), config);
        };
    }

    private static HandyClientV3 getHandyClientV3AndValidateConnection(ConfigProperties config)
    {
        return validateHandyConnection(new HandyClientV3(config.deviceConnectionKey(), config.handyApplicationId()));
    }

    private static <T extends HandyClient> T validateHandyConnection(T handyClient)
    {
        if (!handyClient.checkConnectionStatus())
        {
            log.error("Handy not connected! Check your connection and 'deviceConnectionKey' in config. Closing app...");
            System.exit(1);
        }
        log.info("Handy connected...");
        return handyClient;
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
}