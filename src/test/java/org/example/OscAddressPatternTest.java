package org.example;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OscAddressPatternTest
{
    @Test
    void wildcardMatchesAddressWithSpace()
    {
        assertThat(new OscAddressPattern("/avatar/parameters/OGB/Pen/*/PenOthers")
                .matches("/avatar/parameters/OGB/Pen/Big Pen/PenOthers")).isTrue();
    }

    @Test
    void literalSpaceInPatternMatchesAddressWithSpace()
    {
        assertThat(new OscAddressPattern("/avatar/parameters/OGB/Pen/Big Pen/PenOthers")
                .matches("/avatar/parameters/OGB/Pen/Big Pen/PenOthers")).isTrue();
    }

    @Test
    void patternUsedAsKeyIsNotChanged()
    {
        String pattern = "/avatar/parameters/OGB/Pen/Big Pen/PenOthers";

        assertThat(new OscAddressPattern(pattern).pattern()).isEqualTo(pattern);
    }

    @Test
    void otherWildcardsStillWork()
    {
        assertThat(new OscAddressPattern("/avatar/parameters/OGB/Pen/Pe?/PenOthers")
                .matches("/avatar/parameters/OGB/Pen/Pen/PenOthers")).isTrue();
        assertThat(new OscAddressPattern("/avatar/parameters/OGB/Pen/[PR]en/PenOthers")
                .matches("/avatar/parameters/OGB/Pen/Ren/PenOthers")).isTrue();
    }

    @Test
    void addressWithSpaceDoesNotMatchADifferentPattern()
    {
        assertThat(new OscAddressPattern("/avatar/parameters/OGB/Pen/*/PenOthers")
                .matches("/avatar/parameters/OGB/Pen/Big Pen/PenSelf")).isFalse();
    }
}
