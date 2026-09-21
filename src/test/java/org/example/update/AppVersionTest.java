package org.example.update;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AppVersionTest
{
    @Test
    void parsesVersionsWithAndWithoutVPrefix()
    {
        assertThat(AppVersion.parse("0.2")).contains(new AppVersion(0, 2, 0));
        assertThat(AppVersion.parse("v0.2.1")).contains(new AppVersion(0, 2, 1));
        assertThat(AppVersion.parse("V1.2.3")).contains(new AppVersion(1, 2, 3));
        assertThat(AppVersion.parse("  0.3  ")).contains(new AppVersion(0, 3, 0));
    }

    @Test
    void ignoresPrereleaseAndBuildSuffixes()
    {
        assertThat(AppVersion.parse("0.3.1-rc1")).contains(new AppVersion(0, 3, 1));
        assertThat(AppVersion.parse("v0.3+build5")).contains(new AppVersion(0, 3, 0));
        assertThat(AppVersion.parse("0.3b")).contains(new AppVersion(0, 3, 0));
    }

    @Test
    void rejectsTextWithoutAVersion()
    {
        assertThat(AppVersion.parse(null)).isEmpty();
        assertThat(AppVersion.parse("")).isEmpty();
        assertThat(AppVersion.parse("v")).isEmpty();
        assertThat(AppVersion.parse("nightly")).isEmpty();
        assertThat(AppVersion.parse("1.2.3.4")).isEmpty();
        assertThat(AppVersion.parse("${project.version}")).isEmpty();
        assertThat(AppVersion.parse("99999999999999999999")).isEmpty();
    }

    @Test
    void comparesPartsAsNumbers()
    {
        assertThat(parse("0.10")).isGreaterThan(parse("0.9"));
        assertThat(parse("1.0")).isGreaterThan(parse("0.99.99"));
        assertThat(parse("0.2.1")).isGreaterThan(parse("0.2"));
        assertThat(parse("0.2")).isEqualByComparingTo(parse("0.2.0"));
    }

    @Test
    void isNewerThanOnlyAcceptsHigherVersions()
    {
        assertThat(parse("0.3").isNewerThan(parse("0.2"))).isTrue();
        assertThat(parse("0.2").isNewerThan(parse("0.2"))).isFalse();
        assertThat(parse("0.1").isNewerThan(parse("0.2"))).isFalse();
    }

    @Test
    void printsVersionWithoutTrailingZeros()
    {
        assertThat(parse("0.2")).hasToString("0.2");
        assertThat(parse("v0.2.1")).hasToString("0.2.1");
        assertThat(parse("1.0.0")).hasToString("1.0");
    }

    private static AppVersion parse(String text)
    {
        Optional<AppVersion> version = AppVersion.parse(text);
        assertThat(version).isPresent();
        return version.get();
    }
}
