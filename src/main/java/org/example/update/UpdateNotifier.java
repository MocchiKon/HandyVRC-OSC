package org.example.update;

import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;
import java.net.URI;

/**
 * Tells the user that a newer version has been released: it is written to the log and, when the app runs with a
 * graphical environment, shown in a dialog that offers to open the download page. Notification problems (no
 * display, no browser, broken desktop integration) must never affect the running application, so everything
 * here is best effort.
 */
@Slf4j
public final class UpdateNotifier
{
    private static final String OPEN_DOWNLOAD_PAGE = "Open download page";
    private static final String CLOSE = "Close";

    private UpdateNotifier()
    {
    }

    public static void showUpdateAvailable(UpdateCheckResult result)
    {
        String downloadUrl = result.downloadUrl();
        log.warn("A newer version of HandyVRC-OSC is available: {} (this build is {}). Download it from {} "
                        + "(set checkForUpdates=false in app.properties to stop this check)",
                result.latestVersion(), result.currentVersion(), downloadUrl);
        showDialogSafely(notificationMessage(result), downloadUrl);
    }

    static String notificationMessage(UpdateCheckResult result)
    {
        return """
                A newer version of HandyVRC-OSC is available.

                Newest version: %s
                Your version: %s

                %s

                You can turn this startup check off with checkForUpdates=false in app.properties."""
                .formatted(result.latestVersion(), result.currentVersion(), result.downloadUrl());
    }

    private static void showDialogSafely(String message, String downloadUrl)
    {
        try
        {
            if (GraphicsEnvironment.isHeadless())
            {
                log.info("No graphical environment available, the update notification is only logged");
                return;
            }
            SwingUtilities.invokeLater(() -> showDialog(message, downloadUrl));
        }
        catch (Exception e) // e.g. a broken AWT implementation
        {
            log.info("Could not show the update notification dialog: {}", e.toString());
            log.debug("Update notification failure", e);
        }
    }

    private static void showDialog(String message, String downloadUrl)
    {
        try
        {
            Object[] options = {OPEN_DOWNLOAD_PAGE, CLOSE};
            int choice = JOptionPane.showOptionDialog(null, message, "Update available", JOptionPane.DEFAULT_OPTION,
                    JOptionPane.INFORMATION_MESSAGE, null, options, options[0]);
            if (choice == 0)
            {
                openInBrowser(downloadUrl);
            }
        }
        catch (Exception e)
        {
            log.info("Could not show the update notification dialog: {}", e.toString());
            log.debug("Update notification failure", e);
        }
    }

    static boolean openInBrowser(String url)
    {
        try
        {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE))
            {
                log.info("Opening a browser is not supported here, open {} manually", url);
                return false;
            }
            desktop.browse(URI.create(url));
            return true;
        }
        catch (Exception e) // no browser installed, invalid url, headless environment
        {
            log.info("Could not open {} in a browser ({}) - please open it manually", url, e.toString());
            log.debug("Opening the download page failed", e);
            return false;
        }
    }
}
