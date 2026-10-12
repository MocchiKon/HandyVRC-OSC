package org.example;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigLoader;
import org.example.config.ConfigProperties;
import org.example.gui.LogCapture;
import org.example.gui.MainWindow;
import org.example.gui.Theme;
import org.example.update.StartupUpdateCheck;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;

@Slf4j
public class Main
{
    public static void main(String[] args)
    {
        Theme.prepareRendering(); // Has to happen before the first window is created
        LogCapture.install(); // Before anything is logged, so that the Logs tab shows the startup as well
        try
        {
            var configLoader = new ConfigLoader(getAppConfigPath());
            ConfigProperties config = configLoader.readOrInitConfig();

            StartupUpdateCheck.runIfEnabled(config); // Runs in the background, so it never delays the startup

            SwingUtilities.invokeLater(() -> MainWindow.open(configLoader, config));
        }
        catch (Exception e)
        {
            delayedClosingWithLog("Error occurred while initializing: %s! Closing app...".formatted(e.getMessage()), e);
        }
    }

    private static Path getAppConfigPath()
    {
        File jarPath = new File(Main.class.getProtectionDomain().getCodeSource().getLocation().getPath());
        String propertiesPath = jarPath.getParentFile().getAbsolutePath();
        return Path.of(propertiesPath + "/" + "app.properties");
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
