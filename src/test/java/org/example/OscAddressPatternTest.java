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

    @Test
    void interactWithBothMatchesSelfAndOthers()
    {
        var penetrator = new OscAddressPattern("/avatar/parameters/OGB/Pen/*/{PenSelf,PenOthers}");
        var orifice = new OscAddressPattern("/avatar/parameters/OGB/Orf/*/{PenSelf,PenOthers}NewRoot");

        assertThat(penetrator.matches("/avatar/parameters/OGB/Pen/Big Pen/PenSelf")).isTrue();
        assertThat(penetrator.matches("/avatar/parameters/OGB/Pen/Big Pen/PenOthers")).isTrue();
        assertThat(penetrator.matches("/avatar/parameters/OGB/Pen/Big Pen/FrotOthers")).isFalse();
        assertThat(orifice.matches("/avatar/parameters/OGB/Orf/1/PenSelfNewRoot")).isTrue();
        assertThat(orifice.matches("/avatar/parameters/OGB/Orf/1/PenOthersNewRoot")).isTrue();
        assertThat(orifice.matches("/avatar/parameters/OGB/Orf/1/PenOthersNewTip")).isFalse();
    }

    @Test
    void curlyBraceGroupsAreExpandedIntoTheirAlternatives()
    {
        assertThat(OscAddressPattern.expandAlternatives("/x/{a,b}/{c,d}e"))
                .containsExactly("/x/a/ce", "/x/a/de", "/x/b/ce", "/x/b/de");
        assertThat(OscAddressPattern.expandAlternatives("/x/{a,b")).containsExactly("/x/{a,b");
        assertThat(OscAddressPattern.expandAlternatives("/x/*/PenOthers")).containsExactly("/x/*/PenOthers");
    }
}
