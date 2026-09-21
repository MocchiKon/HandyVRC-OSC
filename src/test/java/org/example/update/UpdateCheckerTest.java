package org.example.update;

import org.example.update.UpdateCheckResult.Status;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The update check talks to a network service that can be unreachable, rate limited, broken or gone. None of
 * these may throw or report an update, because the app has to stay usable without update information.
 */
class UpdateCheckerTest
{
    private static final AppVersion CURRENT_VERSION = version("0.2");
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    @Test
    void reportsTheNewestReleaseAsAvailableUpdate() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, releases(release("0.1"), release("0.3"), release("0.2")));

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UPDATE_AVAILABLE);
            assertThat(result.isUpdateAvailable()).isTrue();
            assertThat(result.currentVersion()).isEqualTo(CURRENT_VERSION);
            assertThat(result.latestVersion()).isEqualTo(version("0.3"));
            assertThat(result.releaseUrl()).isEqualTo("https://example.test/releases/0.3");
            assertThat(result.downloadUrl()).isEqualTo("https://example.test/releases/0.3");
        }
    }

    @Test
    void doesNotReportAnUpdateWhenTheNewestReleaseIsTheRunningVersion() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, releases(release("0.2")));

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UP_TO_DATE);
            assertThat(result.isUpdateAvailable()).isFalse();
            assertThat(result.latestVersion()).isEqualTo(CURRENT_VERSION);
        }
    }

    @Test
    void doesNotReportAnUpdateWhenOnlyOlderVersionsAreReleased() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            // this is the state of the real repository while a new version is being developed
            github.respondWith(200, releases(release("0.1")));

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UP_TO_DATE);
            assertThat(result.latestVersion()).isEqualTo(version("0.1"));
        }
    }

    @Test
    void ignoresDraftsAndPrereleases() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, releases(
                    release("0.5", true, false),
                    release("0.4", false, true),
                    release("0.1")));

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UP_TO_DATE);
            assertThat(result.latestVersion()).isEqualTo(version("0.1"));
        }
    }

    @Test
    void ignoresReleasesWithAnUnparsableTag() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, releases(release("nightly"), release("latest")));

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UP_TO_DATE);
            assertThat(result.latestVersion()).isNull();
        }
    }

    @Test
    void handlesARepositoryWithoutAnyRelease() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, "[]");

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.UP_TO_DATE);
            assertThat(result.latestVersion()).isNull();
        }
    }

    @Test
    void handlesADeletedRepository() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(404, "{\"message\":\"Not Found\"}");

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(result.detail()).contains("404");
            assertThat(result.latestVersion()).isNull();
        }
    }

    @Test
    void handlesRateLimiting() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(403, "{\"message\":\"API rate limit exceeded\"}");

            assertThat(check(github).detail()).contains("rate limit");
        }
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(429, "{}");

            assertThat(check(github).status()).isEqualTo(Status.FAILED);
        }
    }

    @Test
    void handlesServerErrors() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(500, "Internal Server Error");

            var result = check(github);

            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(result.detail()).contains("500");
        }
    }

    @Test
    void handlesAResponseThatIsNotJson() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, "<html>maintenance</html>");

            assertThat(check(github).status()).isEqualTo(Status.FAILED);
        }
    }

    @Test
    void handlesAJsonResponseThatIsNotAListOfReleases() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, "{\"message\":\"Not Found\"}");

            assertThat(check(github).status()).isEqualTo(Status.FAILED);
        }
    }

    @Test
    void handlesAnUnreachableServer() throws IOException
    {
        URI releasesUri;
        try (var github = new FakeGitHubApiServer())
        {
            releasesUri = github.releasesUri();
        } // the server is gone now, connecting to it fails

        // the server is gone now, connecting to it fails
        // (would fail this test with an exception if the checker let connection problems through)
        var result = new UpdateChecker(releasesUri, TIMEOUT).checkForUpdate(CURRENT_VERSION);

        assertThat(result.status()).isEqualTo(Status.FAILED);
        assertThat(result.detail()).contains("could not reach");
    }

    @Test
    void givesUpOnAConnectionThatNeverAnswers() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.hang().respondWith(200, "[]");
            var checker = new UpdateChecker(github.releasesUri(), Duration.ofMillis(500));

            long start = System.nanoTime();
            var result = checker.checkForUpdate(CURRENT_VERSION);
            var elapsed = Duration.ofNanos(System.nanoTime() - start);

            assertThat(result.status()).isEqualTo(Status.FAILED);
            assertThat(elapsed).isLessThan(TIMEOUT);
            assertThat(github.requests()).isEqualTo(1); // the request was sent and the response was awaited
        }
    }

    @Test
    void sendsTheHeadersGitHubRequires() throws IOException
    {
        try (var github = new FakeGitHubApiServer())
        {
            github.respondWith(200, "[]");

            check(github);

            assertThat(github.lastUserAgent()).isNotBlank();
            assertThat(github.lastAccept()).contains("github");
            assertThat(github.lastRequestTarget()).contains("MocchiKon/HandyVRC-OSC");
        }
    }

    private static UpdateCheckResult check(FakeGitHubApiServer github)
    {
        return new UpdateChecker(github.releasesUri(), TIMEOUT).checkForUpdate(CURRENT_VERSION);
    }

    private static String releases(String... releases)
    {
        return "[" + String.join(",", releases) + "]";
    }

    private static String release(String tag)
    {
        return release(tag, false, false);
    }

    private static String release(String tag, boolean draft, boolean prerelease)
    {
        return """
                {"tag_name":"%s","name":"%s","html_url":"https://example.test/releases/%s",\
                "draft":%s,"prerelease":%s}"""
                .formatted(tag, tag, tag, draft, prerelease);
    }

    private static AppVersion version(String text)
    {
        return AppVersion.parse(text).orElseThrow();
    }
}
