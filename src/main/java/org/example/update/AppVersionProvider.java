package org.example.update;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.util.Optional;
import java.util.Properties;

/**
 * Reads the version of the running application. Maven writes it into {@code /version.properties} at build time;
 * when that resource is missing or unreadable, the version stored in the JAR manifest is used instead.
 */
@Slf4j
public final class AppVersionProvider
{
    static final String VERSION_RESOURCE = "/version.properties";
    static final String VERSION_PROPERTY = "version";

    private AppVersionProvider()
    {
    }

    /**
     * @return the version of this build, or an empty optional when it cannot be determined
     * (the update check is skipped in that case, because there is nothing to compare against)
     */
    public static Optional<AppVersion> currentVersion()
    {
        Optional<AppVersion> version = readVersionResource().or(AppVersionProvider::readManifestVersion).flatMap(AppVersion::parse);
        if (version.isEmpty())
        {
            log.warn("Could not determine the version of this application");
        }
        return version;
    }

    private static Optional<String> readVersionResource()
    {
        try (InputStream resource = AppVersionProvider.class.getResourceAsStream(VERSION_RESOURCE))
        {
            if (resource == null)
            {
                return Optional.empty();
            }
            var properties = new Properties();
            properties.load(resource);
            return Optional.ofNullable(properties.getProperty(VERSION_PROPERTY));
        }
        catch (IOException | RuntimeException e)
        {
            log.debug("Could not read {}: {}", VERSION_RESOURCE, e.toString());
            return Optional.empty();
        }
    }

    private static Optional<String> readManifestVersion()
    {
        try
        {
            Package appPackage = AppVersionProvider.class.getPackage();
            return Optional.ofNullable(appPackage.getImplementationVersion());
        }
        catch (RuntimeException e)
        {
            log.debug("Could not read the version from the JAR manifest: {}", e.toString());
            return Optional.empty();
        }
    }
}
