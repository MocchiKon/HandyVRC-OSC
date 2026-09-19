package org.example.processor;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class PenetratorLengthDetectorTest
{
    /** Length of the simulated penetrator used by the tests (in meters). */
    private static final float LENGTH = 0.072f;
    /** Root proximity of the simulated penetrator while it is not fully inserted. */
    private static final float ROOT_PROXIMITY = 0.5f;

    @Test
    void detectsLengthOnceEnoughConsistentSamplesAreCollected()
    {
        var detector = new PenetratorLengthDetector();

        updateCleanSample(detector, ROOT_PROXIMITY);
        updateCleanSample(detector, ROOT_PROXIMITY + 0.1f);
        updateCleanSample(detector, ROOT_PROXIMITY + 0.2f);
        assertThat(detector.getLength())
                .as("Length is not trusted until %d samples are collected", 4)
                .isNull();

        updateCleanSample(detector, ROOT_PROXIMITY + 0.3f);
        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));
    }

    @Test
    void ignoresLengthChangesBelowThreshold()
    {
        var detector = new PenetratorLengthDetector();
        // Fill the sample buffer with measurements of a fixed length
        for (int i = 0; i < 8; i++)
        {
            updateCleanSample(detector, ROOT_PROXIMITY);
        }
        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));

        // Replace all samples with measurements of a barely different length (0.5mm, below the 1mm threshold)
        float barelyDifferentLength = LENGTH + 0.0005f;
        for (int i = 0; i < 8; i++)
        {
            detector.update(ROOT_PROXIMITY, ROOT_PROXIMITY + barelyDifferentLength);
        }

        assertThat(detector.getLength())
                .as("A length change below %sm is ignored", 0.001f)
                .isCloseTo(LENGTH, within(0.0001f));
    }

    @Test
    void appliesLengthChangesAboveThreshold()
    {
        var detector = new PenetratorLengthDetector();
        // Fill the sample buffer with measurements of a fixed length
        for (int i = 0; i < 8; i++)
        {
            updateCleanSample(detector, ROOT_PROXIMITY);
        }

        // Replace all samples with measurements of a clearly different length (5mm, above the 1mm threshold)
        float differentLength = LENGTH + 0.005f;
        for (int i = 0; i < 8; i++)
        {
            detector.update(ROOT_PROXIMITY, ROOT_PROXIMITY + differentLength);
        }

        assertThat(detector.getLength()).isCloseTo(differentLength, within(0.0001f));
    }

    @Test
    void keepsUsingTheLastReceivedValueWhenOnlyOneOfTheProximitiesIsUpdated()
    {
        var detector = new PenetratorLengthDetector();

        updateCleanSample(detector, ROOT_PROXIMITY);
        updateCleanSample(detector, ROOT_PROXIMITY + 0.1f);
        updateCleanSample(detector, ROOT_PROXIMITY + 0.2f);
        // VRChat only sends parameters that changed, so the tip proximity can be missing from a packet
        detector.update(ROOT_PROXIMITY + 0.3f, null);
        detector.update(null, ROOT_PROXIMITY + 0.3f + LENGTH);

        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));
    }

    @Test
    void picksTheTwoClosestSamplesToFilterMismatchedRootAndTipUpdates()
    {
        var detector = new PenetratorLengthDetector();
        // Root and tip proximity may arrive in separate packets, which is measured against a stale value
        detector.update(0.1f, null);
        detector.update(null, 0.5f); // Wrong measurement of 0.4m

        // Consistent measurements of a LENGTH meter long penetrator, one value per packet
        detector.update(0.6f, null);
        detector.update(null, 0.6f + LENGTH);
        detector.update(0.7f, null);
        detector.update(null, 0.7f + LENGTH);
        detector.update(0.8f, null);
        detector.update(null, 0.8f + LENGTH);

        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));
    }

    @Test
    void usesLengthMeasuredWhilePenetratingOnlyAsFallback()
    {
        var detector = new PenetratorLengthDetector();

        // Penetrator is penetrating right now (tip proximity is pinned at 1), so this measurement
        // cannot be trusted but it is better than nothing
        detector.update(1.f - LENGTH, 1.f);

        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));
    }

    @Test
    void clearsLengthWhenNobodyIsInRange()
    {
        var detector = new PenetratorLengthDetector();
        for (int i = 0; i < 4; i++)
        {
            updateCleanSample(detector, ROOT_PROXIMITY);
        }
        assertThat(detector.getLength()).isCloseTo(LENGTH, within(0.0001f));

        detector.update(0.f, null); // Nobody in radius

        assertThat(detector.getLength()).isNull();
    }

    @Test
    void ignoresTooShortMeasurements()
    {
        var detector = new PenetratorLengthDetector();

        for (int i = 0; i < 8; i++)
        {
            // Only 1cm apart (broken or backward setup)
            detector.update(ROOT_PROXIMITY, ROOT_PROXIMITY + 0.01f);
        }

        assertThat(detector.getLength()).isNull();
    }

    @Test
    void ignoresRootSittingInTheOrificeCenter()
    {
        var detector = new PenetratorLengthDetector();

        // Root collider is in the center of the orifice, which cannot be measured from
        detector.update(0.99f, 1.f);

        assertThat(detector.getLength()).isNull();
    }

    @Test
    void resetForgetsDetectedLength()
    {
        var detector = new PenetratorLengthDetector();
        for (int i = 0; i < 4; i++)
        {
            updateCleanSample(detector, ROOT_PROXIMITY);
        }
        assertThat(detector.getLength()).isNotNull();

        detector.reset();

        assertThat(detector.getLength()).isNull();
    }

    @Test
    void detectsLengthFromCapturedVrchatProximities()
    {
        // Captured updates of /avatar/parameters/OGB/Orf/Blowjob/PenSelfNewRoot and PenSelfNewTip.
        // VRChat sends both of them in one OSC packet and stops sending the tip once it stops changing.
        var detector = new PenetratorLengthDetector();

        detector.update(0.78796864f, 0.9566813f);
        detector.update(0.7936803f, 0.962393f);
        detector.update(0.801732f, 0.9704447f);
        detector.update(0.8181735f, 0.9868862f);
        detector.update(0.8330573f, 1.f); // Tip proximity is saturated (penetrator fully inserted)
        detector.update(0.8446779f, null);
        detector.update(0.84687924f, null);
        detector.update(0.8472556f, null);

        assertThat(detector.getLength()).isCloseTo(0.1687f, within(0.0001f));
    }

    /** Feeds a consistent root/tip proximity pair of a {@link #LENGTH} meter long penetrator. */
    private static void updateCleanSample(PenetratorLengthDetector detector, float rootProximity)
    {
        detector.update(rootProximity, rootProximity + LENGTH);
    }
}
