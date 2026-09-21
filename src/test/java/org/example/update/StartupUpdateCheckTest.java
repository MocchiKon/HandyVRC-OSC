package org.example.update;

import org.example.config.ConfigProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class StartupUpdateCheckTest
{
    @Test
    void doesNotAskGitHubWhenTheCheckIsDisabled() throws Exception
    {
        try (var github = new FakeGitHubApiServer())
        {
            StartupUpdateCheck.runIfEnabled(configWithUpdateCheck(false), checkerFor(github));

            Thread.sleep(300); // give a check that was started by mistake the chance to send its request
            assertThat(github.requests()).isZero();
        }
    }

    @Test
    void checksForUpdatesWhenEnabled() throws Exception
    {
        try (var github = new FakeGitHubApiServer())
        {
            // answering with the running version keeps the check successful without triggering a notification
            var currentVersion = AppVersionProvider.currentVersion().orElseThrow();
            github.respondWith(200, """
                    [{"tag_name":"%s","html_url":"https://example.test/releases"}]
                    """.formatted(currentVersion));

            StartupUpdateCheck.runIfEnabled(configWithUpdateCheck(true), checkerFor(github));

            assertThat(requestArrived(github)).isTrue();
        }
    }

    private static UpdateChecker checkerFor(FakeGitHubApiServer github)
    {
        return new UpdateChecker(github.releasesUri(), Duration.ofSeconds(5));
    }

    private static ConfigProperties configWithUpdateCheck(boolean checkForUpdates)
    {
        return ConfigProperties.builder()
                .checkForUpdates(checkForUpdates)
                .build();
    }

    private static boolean requestArrived(FakeGitHubApiServer github) throws InterruptedException
    {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline)
        {
            if (github.requests() > 0)
            {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
