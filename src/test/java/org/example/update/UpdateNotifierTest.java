package org.example.update;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateNotifierTest
{
    @Test
    void messageShowsBothVersionsTheDownloadPageAndHowToTurnTheCheckOff()
    {
        var result = UpdateCheckResult.updateAvailable(version("0.2"), version("0.3"),
                "https://example.test/releases/0.3");

        assertThat(UpdateNotifier.notificationMessage(result))
                .contains("0.3")
                .contains("0.2")
                .contains("https://example.test/releases/0.3")
                .contains("checkForUpdates=false");
    }

    @Test
    void downloadUrlFallsBackToTheProjectReleasesPage()
    {
        assertThat(UpdateCheckResult.updateAvailable(version("0.2"), version("0.3"), null).downloadUrl())
                .isEqualTo(UpdateChecker.PROJECT_RELEASES_PAGE);
        assertThat(UpdateCheckResult.updateAvailable(version("0.2"), version("0.3"), "  ").downloadUrl())
                .isEqualTo(UpdateChecker.PROJECT_RELEASES_PAGE);
    }

    private static AppVersion version(String text)
    {
        return AppVersion.parse(text).orElseThrow();
    }
}
