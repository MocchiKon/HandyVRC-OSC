package org.example.update;

import org.apache.commons.lang3.StringUtils;

/**
 * Result of an update check. A failed check is a normal outcome (no internet connection, GitHub unavailable,
 * repository gone) and only means that no update information is available - the application keeps working.
 *
 * @param status        what the check found out
 * @param currentVersion version this application is running
 * @param latestVersion  newest released version, or null when the repository has no released version at all
 * @param releaseUrl     page of that release, or null when unknown
 * @param detail         reason of a failure, null for a successful check
 */
public record UpdateCheckResult(
        Status status,
        AppVersion currentVersion,
        AppVersion latestVersion,
        String releaseUrl,
        String detail
)
{
    public enum Status
    {
        UPDATE_AVAILABLE,
        UP_TO_DATE,
        FAILED
    }

    static UpdateCheckResult updateAvailable(AppVersion currentVersion, AppVersion latestVersion, String releaseUrl)
    {
        return new UpdateCheckResult(Status.UPDATE_AVAILABLE, currentVersion, latestVersion, releaseUrl, null);
    }

    static UpdateCheckResult upToDate(AppVersion currentVersion, AppVersion latestVersion)
    {
        return new UpdateCheckResult(Status.UP_TO_DATE, currentVersion, latestVersion, null, null);
    }

    static UpdateCheckResult failed(AppVersion currentVersion, String detail)
    {
        return new UpdateCheckResult(Status.FAILED, currentVersion, null, null, detail);
    }

    public boolean isUpdateAvailable()
    {
        return status == Status.UPDATE_AVAILABLE;
    }

    /** Page the user should open to get the new version, never blank for an available update. */
    public String downloadUrl()
    {
        return StringUtils.isBlank(releaseUrl) ? UpdateChecker.PROJECT_RELEASES_PAGE : releaseUrl;
    }
}
