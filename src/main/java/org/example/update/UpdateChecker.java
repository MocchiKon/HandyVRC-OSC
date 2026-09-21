package org.example.update;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Asks the GitHub releases API for the newest released version of the application and compares it with the
 * version that is running. The check is best effort only: the application is fully usable without it, so every
 * problem (offline machine, GitHub unreachable or rate limited, repository renamed or deleted, unexpected
 * response) is reported as {@link UpdateCheckResult.Status#FAILED} instead of an exception.
 */
@Slf4j
public class UpdateChecker
{
    /** GitHub API endpoint listing the releases of the project. */
    public static final URI DEFAULT_RELEASES_URI =
            URI.create("https://api.github.com/repos/MocchiKon/HandyVRC-OSC/releases?per_page=100");

    /** Where a user without a release link (or without a browser) can look for new versions. */
    static final String PROJECT_RELEASES_PAGE = "https://github.com/MocchiKon/HandyVRC-OSC/releases";

    /** GitHub answers requests without a User-Agent header with 403, so one has to be sent. */
    private static final String USER_AGENT = "HandyVRC-OSC";
    private static final String GITHUB_ACCEPT_HEADER = "application/vnd.github+json";
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private static final int HTTP_OK = 200;
    private static final int HTTP_FORBIDDEN = 403;
    private static final int HTTP_NOT_FOUND = 404;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final URI releasesUri;
    private final Duration timeout;
    private final HttpClient httpClient;

    public UpdateChecker()
    {
        this(DEFAULT_RELEASES_URI, DEFAULT_TIMEOUT);
    }

    UpdateChecker(URI releasesUri, Duration timeout)
    {
        this(releasesUri, timeout, HttpClient.newBuilder()
                .connectTimeout(timeout)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    UpdateChecker(URI releasesUri, Duration timeout, HttpClient httpClient)
    {
        this.releasesUri = releasesUri;
        this.timeout = timeout;
        this.httpClient = httpClient;
    }

    /**
     * Checks whether a version newer than {@code currentVersion} has been released.
     * This method never throws.
     */
    public UpdateCheckResult checkForUpdate(AppVersion currentVersion)
    {
        log.debug("Checking for a newer version at {}", releasesUri);
        try
        {
            HttpResponse<String> response = httpClient.send(createRequest(), HttpResponse.BodyHandlers.ofString());
            return evaluateResponse(response.statusCode(), response.body(), currentVersion);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            return failed(currentVersion, "the update check was interrupted", e);
        }
        catch (Exception e) // connection refused, unknown host, TLS problems, timeouts, ...
        {
            return failed(currentVersion, "could not reach " + releasesUri.getHost() + " (" + e + ")", e);
        }
    }

    private HttpRequest createRequest()
    {
        return HttpRequest.newBuilder(releasesUri)
                .header("Accept", GITHUB_ACCEPT_HEADER)
                .header("User-Agent", USER_AGENT)
                .timeout(timeout)
                .GET()
                .build();
    }

    private UpdateCheckResult evaluateResponse(int statusCode, String body, AppVersion currentVersion)
    {
        String httpFailure = describeHttpFailure(statusCode);
        if (httpFailure != null)
        {
            return failed(currentVersion, httpFailure, null);
        }
        Optional<Release> newestRelease;
        try
        {
            newestRelease = findNewestRelease(body);
        }
        catch (IOException | RuntimeException e) // not JSON at all or not shaped like the GitHub API
        {
            return failed(currentVersion, "unexpected response from " + releasesUri.getHost() + " (" + e + ")", e);
        }
        if (newestRelease.isEmpty())
        {
            log.info("The repository has no released version yet, so there is nothing to compare this build with");
            return UpdateCheckResult.upToDate(currentVersion, null);
        }
        AppVersion latestVersion = newestRelease.get().version();
        if (latestVersion.isNewerThan(currentVersion))
        {
            log.info("A newer version of the app is available: {} (running {})", latestVersion, currentVersion);
            return UpdateCheckResult.updateAvailable(currentVersion, latestVersion, newestRelease.get().url());
        }
        log.info("The app is up to date: newest release is {} and this build is {}", latestVersion, currentVersion);
        return UpdateCheckResult.upToDate(currentVersion, latestVersion);
    }

    /**
     * @return a message describing why the response cannot contain release information, or null for HTTP 200
     */
    private String describeHttpFailure(int statusCode)
    {
        if (statusCode == HTTP_OK)
        {
            return null;
        }
        if (statusCode == HTTP_NOT_FOUND)
        {
            return "the GitHub repository or its releases were not found (HTTP 404), "
                    + "the project may have been renamed or deleted";
        }
        if (statusCode == HTTP_FORBIDDEN || statusCode == HTTP_TOO_MANY_REQUESTS)
        {
            return "GitHub refused the request (HTTP %d), the API rate limit may have been reached".formatted(statusCode);
        }
        return "GitHub answered with HTTP %d".formatted(statusCode);
    }

    /**
     * Picks the newest release out of the GitHub releases response. Drafts and pre-releases are ignored, and so are
     * releases whose tag is not a version this app understands.
     */
    private Optional<Release> findNewestRelease(String responseBody) throws IOException
    {
        JsonNode releases = OBJECT_MAPPER.readTree(responseBody);
        if (releases == null || !releases.isArray())
        {
            throw new IOException("expected a JSON array of releases");
        }
        Release newest = null;
        for (JsonNode release : releases)
        {
            if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean())
            {
                continue;
            }
            String tag = firstNonBlank(textOf(release, "tag_name"), textOf(release, "name"));
            Optional<AppVersion> version = AppVersion.parse(tag);
            if (version.isEmpty())
            {
                log.debug("Ignoring a release that is not a parsable version: '{}'", tag);
                continue;
            }
            if (newest == null || version.get().isNewerThan(newest.version()))
            {
                newest = new Release(version.get(), textOf(release, "html_url"));
            }
        }
        return Optional.ofNullable(newest);
    }

    private UpdateCheckResult failed(AppVersion currentVersion, String detail, Exception cause)
    {
        log.info("Update check failed: {} - the app keeps running without update information", detail);
        if (cause != null)
        {
            log.debug("Update check failure details", cause);
        }
        return UpdateCheckResult.failed(currentVersion, detail);
    }

    private static String textOf(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        return value == null || !value.isTextual() ? null : value.asText();
    }

    private static String firstNonBlank(String first, String second)
    {
        return first == null || first.isBlank() ? second : first;
    }

    private record Release(AppVersion version, String url)
    {
    }
}
