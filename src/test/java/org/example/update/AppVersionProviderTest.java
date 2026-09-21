package org.example.update;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionProviderTest
{
    @Test
    void readsTheVersionMavenPutIntoTheBuild()
    {
        // Guards the resource filtering in pom.xml: an unfiltered version.properties would contain
        // "${project.version}", which cannot be parsed, and the update check would silently never run.
        assertThat(AppVersionProvider.currentVersion()).isPresent();
    }
}
