package org.example.oscquery;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SpsParameterDetectorTest
{
    private static final String ORIFICE_ROOT = "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewRoot";
    private static final String ORIFICE_TIP = "/avatar/parameters/OGB/Orf/Blowjob/PenOthersNewTip";
    private static final String PENETRATOR = "/avatar/parameters/OGB/Pen/Blowjob/PenOthers";

    @Test
    void detectsOrificeProximityPairs()
    {
        var detection = SpsParameterDetector.detect(List.of(
                node(ORIFICE_ROOT), node(ORIFICE_TIP), node("/avatar/parameters/MuteSelf")));

        assertThat(detection.orificePairs())
                .containsExactly(new SpsParameterDetector.PenetratorPair(ORIFICE_ROOT, ORIFICE_TIP));
        assertThat(detection.incompleteOrificeParameters()).isEmpty();
        assertThat(detection.penetratorParameters()).isEmpty();
    }

    @Test
    void reportsOrificeParametersWhoseCounterpartIsMissing()
    {
        var detection = SpsParameterDetector.detect(List.of(node(ORIFICE_ROOT), node("/avatar/parameters/OGB/Orf/2/PenSelfNewTip")));

        assertThat(detection.orificePairs()).isEmpty();
        assertThat(detection.incompleteOrificeParameters())
                .containsExactlyInAnyOrder("/avatar/parameters/OGB/Orf/2/PenSelfNewTip", ORIFICE_ROOT);
    }

    @Test
    void detectsPenetratorParameters()
    {
        var detection = SpsParameterDetector.detect(List.of(
                node(PENETRATOR), node("/avatar/parameters/OGB/Pen/Blowjob/PenSelf"), node(ORIFICE_ROOT)));

        assertThat(detection.penetratorParameters())
                .containsExactly("/avatar/parameters/OGB/Pen/Blowjob/PenOthers", "/avatar/parameters/OGB/Pen/Blowjob/PenSelf");
    }

    @Test
    void listsOtherSpsLikeParametersWithoutDuplicatingCandidates()
    {
        var detection = SpsParameterDetector.detect(List.of(
                node("/avatar/parameters/OGB/Pen/Blowjob/PenOthers"), node("/avatar/parameters/OGB/Pen/Blowjob/PenOthersSPS")));

        assertThat(detection.otherSpsParameters()).containsExactly("/avatar/parameters/OGB/Pen/Blowjob/PenOthersSPS");
    }

    @Test
    void detectionIsEmptyWhenNoSpsParametersExist()
    {
        var detection = SpsParameterDetector.detect(List.of(node("/avatar/parameters/MuteSelf"), node("/avatar/parameters/Voice")));

        assertThat(detection.isEmpty()).isTrue();
    }

    @Test
    void matchingNodesSupportsOscWildcards()
    {
        var parameters = List.of(
                node(ORIFICE_ROOT), node(ORIFICE_TIP),
                node("/avatar/parameters/OGB/Orf/Blowjob/PenSelfNewRoot"),
                node("/avatar/parameters/OGB/Orf/Blowjob/PenSelfNewTip"));

        assertThat(SpsParameterDetector.matchingNodes("/avatar/parameters/OGB/Orf/*/PenOthersNewRoot", parameters))
                .extracting(OscQueryNode::path)
                .containsExactly(ORIFICE_ROOT);
    }

    @Test
    void matchingNodesReturnsNothingForBlankOrMissingPattern()
    {
        var parameters = List.of(node(ORIFICE_ROOT));

        assertThat(SpsParameterDetector.matchingNodes(null, parameters)).isEmpty();
        assertThat(SpsParameterDetector.matchingNodes("  ", parameters)).isEmpty();
        assertThat(SpsParameterDetector.matchingNodes("/avatar/parameters/NotThere", parameters)).isEmpty();
    }

    @Test
    void describesNodesWithTypeAndAccess()
    {
        var node = new OscQueryNode(ORIFICE_ROOT, "f", 3);

        assertThat(node.isNumeric()).isTrue();
        assertThat(node.isReadable()).isTrue();
        assertThat(node.describe()).isEqualTo(ORIFICE_ROOT + " (type=f, access=3)");
        assertThat(new OscQueryNode(ORIFICE_ROOT, null, null).isNumeric()).isFalse();
    }

    private static OscQueryNode node(String path)
    {
        return new OscQueryNode(path, "f", 3);
    }
}
