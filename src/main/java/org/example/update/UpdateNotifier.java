package org.example.update;

import lombok.extern.slf4j.Slf4j;
import org.example.gui.UpdateDialog;

import javax.swing.*;
import java.awt.*;
import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Tells the user that a newer version has been released: it is written to the log and, when the app runs with a
 * graphical environment, shown in a dialog that offers to open the download page. Notification problems (no
 * display, no browser, broken desktop integration) must never affect the running application, so everything
 * here is best effort.
 */
@Slf4j
public final class UpdateNotifier
{
    private UpdateNotifier()
    {
    }

    public static void showUpdateAvailable(UpdateCheckResult result)
    {
        String downloadUrl = result.downloadUrl();
        log.warn("A newer version of HandyVRC-OSC is available: {} (this build is {}). Download it from {} "
                        + "(turn off 'Check for updates' in the Advanced settings to stop this check)",
                result.latestVersion(), result.currentVersion(), downloadUrl);
        showDialogSafely(result);
    }

    private static void showDialogSafely(UpdateCheckResult result)
    {
        try
        {
            if (GraphicsEnvironment.isHeadless())
            {
                log.info("No graphical environment available, the update notification is only logged");
                return;
            }
            SwingUtilities.invokeLater(() -> showDialog(result));
        }
        catch (Exception e) // e.g. a broken AWT implementation
        {
            log.info("Could not show the update notification dialog: {}", e.toString());
            log.debug("Update notification failure", e);
        }
    }

    private static void showDialog(UpdateCheckResult result)
    {
        try
        {
            String downloadUrl = result.downloadUrl();
            UpdateDialog.show(result.latestVersion().toString(), result.currentVersion().toString(), downloadUrl,
                    () -> openInBrowser(downloadUrl));
        }
        catch (Exception e)
        {
            log.info("Could not show the update notification dialog: {}", e.toString());
            log.debug("Update notification failure", e);
        }
    }

    static boolean openInBrowser(String url)
    {
        if (browseWithDesktop(url) || browseWithSystemCommand(url))
        {
            return true;
        }
        log.info("Could not open a browser, please open {} manually", url);
        return false;
    }

    private static boolean browseWithDesktop(String url)
    {
        try
        {
            Desktop desktop = Desktop.isDesktopSupported() ? Desktop.getDesktop() : null;
            if (desktop == null || !desktop.isSupported(Desktop.Action.BROWSE))
            {
                log.debug("Opening a browser through java.awt.Desktop is not supported here");
                return false;
            }
            desktop.browse(URI.create(url));
            return true;
        }
        catch (Exception e) // no browser installed, invalid url, headless environment
        {
            log.debug("Opening {} through java.awt.Desktop failed", url, e);
            return false;
        }
    }

    /**
     * java.awt.Desktop cannot browse on many Linux desktops (it needs GNOME libraries), so the URL is handed to the
     * tool of the operating system that opens it in the default browser.
     */
    private static boolean browseWithSystemCommand(String url)
    {
        List<String> command = systemBrowseCommand(System.getProperty("os.name", ""), url);
        try
        {
            new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            return true;
        }
        catch (Exception e) // the tool is not installed
        {
            log.debug("Opening {} with {} failed", url, command, e);
            return false;
        }
    }

    static List<String> systemBrowseCommand(String osName, String url)
    {
        String os = osName.toLowerCase(Locale.ROOT);
        if (os.contains("win"))
        {
            return List.of("rundll32", "url.dll,FileProtocolHandler", url);
        }
        if (os.contains("mac"))
        {
            return List.of("open", url);
        }
        return List.of("xdg-open", url);
    }
}
