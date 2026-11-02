package org.example;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class LaunchModeTest
{
    @Test
    void detectsTheWindowsReleaseFromItsExecutable()
    {
        assertThat(LaunchMode.detect("C:\\HandyVRC-OSC\\HandyVRC-OSC.exe", "C:\\HandyVRC-OSC\\HandyVRC-OSC.exe"))
                .isEqualTo(LaunchMode.WINDOWS_EXE);
    }

    @Test
    void detectsTheWindowsReleaseFromTheClasspathWhenTheCodeSourceIsUnknown()
    {
        assertThat(LaunchMode.detect(null, "/opt/HandyVRC-OSC/HandyVRC-OSC.exe"))
                .isEqualTo(LaunchMode.WINDOWS_EXE);
    }

    @Test
    void detectsTheAllPlatformsReleaseFromItsJar()
    {
        assertThat(LaunchMode.detect("/opt/HandyVRC-OSC/HandyVRC-OSC.jar", "/opt/HandyVRC-OSC/HandyVRC-OSC.jar"))
                .isEqualTo(LaunchMode.ALL_PLATFORMS_JAR);
    }

    @Test
    void detectsADevelopmentBuildFromItsClassesDirectory(@TempDir Path classesDirectory)
    {
        assertThat(LaunchMode.detect(classesDirectory.toString(), classesDirectory.toString()))
                .isEqualTo(LaunchMode.DEVELOPMENT);
    }

    @Test
    void detectsNothingWhenTheLocationDoesNotSayHowTheAppWasStarted()
    {
        assertThat(LaunchMode.detect(null, null)).isEqualTo(LaunchMode.UNKNOWN);
    }

    @Test
    void prefersThePropertyTheWindowsWrapperSets()
    {
        String previous = System.getProperty(LaunchMode.MODE_PROPERTY);
        try
        {
            System.setProperty(LaunchMode.MODE_PROPERTY, "windows-exe");

            // The class location is not an exe here, so only the property can produce this result.
            assertThat(LaunchMode.detect()).isEqualTo(LaunchMode.WINDOWS_EXE);
        }
        finally
        {
            if (previous == null)
            {
                System.clearProperty(LaunchMode.MODE_PROPERTY);
            }
            else
            {
                System.setProperty(LaunchMode.MODE_PROPERTY, previous);
            }
        }
    }
}
