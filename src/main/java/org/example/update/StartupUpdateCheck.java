package org.example.update;

import lombok.extern.slf4j.Slf4j;
import org.example.config.ConfigProperties;

import java.util.Optional;

/**
 * Starts the update check at application startup. The check runs on a daemon thread, so a slow or unreachable
 * GitHub never delays or blocks the start of the app, and a check that is still running cannot keep the app
 * alive at shutdown.
 */
@Slf4j
public final class StartupUpdateCheck
{
    private StartupUpdateCheck()
    {
    }

    public static void runIfEnabled(ConfigProperties config)
    {
        runIfEnabled(config, new UpdateChecker());
    }

    static void runIfEnabled(ConfigProperties config, UpdateChecker updateChecker)
    {
        if (!config.checkForUpdates())
        {
            log.info("Update checking is disabled (checkForUpdates=false)");
            return;
        }
        Optional<AppVersion> currentVersion = AppVersionProvider.currentVersion();
        if (currentVersion.isEmpty())
        {
            log.warn("Skipping the update check because the version of this build is unknown");
            return;
        }
        Thread updateCheckThread = new Thread(() -> check(updateChecker, currentVersion.get()), "update-check");
        updateCheckThread.setDaemon(true);
        updateCheckThread.start();
    }

    private static void check(UpdateChecker updateChecker, AppVersion currentVersion)
    {
        try
        {
            UpdateCheckResult result = updateChecker.checkForUpdate(currentVersion);
            if (result.isUpdateAvailable())
            {
                UpdateNotifier.showUpdateAvailable(result);
            }
        }
        catch (Exception e) // UpdateChecker never throws, but a notification problem must not kill the thread silently
        {
            log.info("Update check failed: {}", e.toString());
            log.debug("Update check failure details", e);
        }
    }
}
