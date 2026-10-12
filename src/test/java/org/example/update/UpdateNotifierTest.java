package org.example.update;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateNotifierTest
{
    @Test
    void urlIsOpenedWithTheBrowserToolOfTheOperatingSystem()
    {
        String url = "https://example.test/releases/0.3";

        assertThat(UpdateNotifier.systemBrowseCommand("Linux", url)).containsExactly("xdg-open", url);
        assertThat(UpdateNotifier.systemBrowseCommand("Mac OS X", url)).containsExactly("open", url);
        assertThat(UpdateNotifier.systemBrowseCommand("Windows 11", url))
                .containsExactly("rundll32", "url.dll,FileProtocolHandler", url);
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
