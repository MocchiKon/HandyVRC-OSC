package org.example.processor;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Auto-detects the length (in meters) of a penetrator by comparing the proximity of its root and tip
 * to an orifice receiver.
 * <p>
 * This is a port of the {@code GameDeviceLengthDetector} class from OSCGoesBrrr
 * (see {@code OscGoesBrrr/src/main/GameDevice.ts}). The proximity values come from the SPS "new pen"
 * avatar parameters (for example {@code PenOthersNewRoot} / {@code PenOthersNewTip}) where the
 * receiver spheres are 1m in size, so {@code tipProximity - rootProximity} is the penetrator length
 * in meters.
 * <p>
 * Root and tip proximity are updated independently and at unrelated times, so a single measurement
 * can be wrong (when only one of the two OSC values has been received). The detector therefore keeps
 * a small buffer of measurements and picks the two closest to each other, which are the most likely
 * to be correct. While the penetrator is actually penetrating, the tip value is pinned near 1.0 and
 * cannot be used for a clean measurement, so the largest length observed during penetration is kept
 * only as a fallback until enough clean samples are collected.
 * <p>
 * All methods are thread-safe because OSC values are delivered on a listener thread while the length
 * is read from the processing thread.
 */
@Slf4j
public class PenetratorLengthDetector
{
    /** Number of clean samples kept for length estimation. */
    private static final int MAX_SAMPLES = 8;
    /** Minimum number of clean samples required before the buffer is considered trustworthy. */
    private static final int MIN_SAMPLES = 4;
    /** Below this proximity the receiver considers nobody to be in range. */
    private static final float NOBODY_IN_RANGE_PROXIMITY = 0.01f;
    /** A root proximity above this cannot be measured from (root collider sitting in the orifice center). */
    private static final float MAX_USABLE_ROOT_PROXIMITY = 0.95f;
    /** Measurements shorter than this are ignored (broken or inverted setup). */
    private static final float MIN_PENETRATOR_LENGTH = 0.01f;
    /** Above this tip proximity the penetrator is considered to be penetrating right now. */
    private static final float TIP_PENETRATING_PROXIMITY = 0.99f;
    /** Minimum change (in meters) of the detected length that is worth applying and logging. */
    private static final float LENGTH_CHANGE_THRESHOLD = 0.001f;

    /** Most recent clean measurements, newest first. */
    private final Deque<Float> recentSamples = new ArrayDeque<>();
    private Float rootProximity;
    private Float tipProximity;
    private Float length;
    private Float badPenetratingSample;

    /**
     * Feeds the proximities of the penetrator root and tip. Both may be passed at once when they were received
     * together in a single OSC packet, or one of them can be {@code null} when it was not part of the received
     * packet (VRChat only sends parameters that changed) - the previously received value is kept in that case.
     */
    public synchronized void update(Float rootProximity, Float tipProximity)
    {
        if (rootProximity == null && tipProximity == null)
        {
            return;
        }
        if (rootProximity != null)
        {
            this.rootProximity = rootProximity;
        }
        if (tipProximity != null)
        {
            this.tipProximity = tipProximity;
        }
        update();
    }

    /**
     * @return Detected penetrator length in meters, or {@code null} when it has not been detected yet
     * (for example when nobody has been in range since the last reset).
     */
    public synchronized Float getLength()
    {
        return length;
    }

    /** Forgets all measurements */
    public synchronized void reset()
    {
        rootProximity = null;
        tipProximity = null;
        badPenetratingSample = null;
        length = null;
        recentSamples.clear();
    }

    private void update()
    {
        Float root = rootProximity;
        Float tip = tipProximity;
        if (root == null || tip == null)
        {
            // Missing data
            badPenetratingSample = null;
            saveSample(null);
            return;
        }
        if (root < NOBODY_IN_RANGE_PROXIMITY || tip < NOBODY_IN_RANGE_PROXIMITY)
        {
            // Nobody in radius, clear recorded length
            badPenetratingSample = null;
            saveSample(null);
            return;
        }
        if (root > MAX_USABLE_ROOT_PROXIMITY)
        {
            // This should be nearly impossible (their root collider is in the center of our orifice).
            // Just keep using whatever we recorded before.
            return;
        }

        // The receiver spheres are 1m in size, so this is in meters
        float measuredLength = tip - root;
        if (measuredLength < MIN_PENETRATOR_LENGTH)
        {
            // Too short (broken or backward?). Just keep using whatever we recorded before.
            return;
        }
        if (tip > TIP_PENETRATING_PROXIMITY)
        {
            // Penetrator is penetrating right now. Only use this length if we don't have anything better.
            if (badPenetratingSample == null || measuredLength > badPenetratingSample)
            {
                badPenetratingSample = measuredLength;
                updateLengthFromSamples();
            }
        }
        else
        {
            // Good to go
            saveSample(measuredLength);
        }
    }

    private void saveSample(Float sample)
    {
        if (sample == null)
        {
            recentSamples.clear();
        }
        else
        {
            recentSamples.addFirst(sample);
            while (recentSamples.size() > MAX_SAMPLES)
            {
                recentSamples.removeLast();
            }
        }
        updateLengthFromSamples();
    }

    private void updateLengthFromSamples()
    {
        Float newLength = calculateLengthFromSamples();
        if (!isLengthChangeSignificant(newLength))
        {
            // The length barely changed, so keep the current one instead of updating (and logging) it
            return;
        }
        length = newLength;
        if (newLength == null)
        {
            log.info("Auto-detected penetrator length cleared");
        }
        else
        {
            log.info("Auto-detected penetrator length: {}cm", Math.round(newLength * 1000) / 10.f);
        }
    }

    /**
     * @return Whether the newly calculated length differs enough from the currently detected one to be applied.
     * A cleared length is always applied so that the device stays in place until the length is detected again.
     */
    private boolean isLengthChangeSignificant(Float newLength)
    {
        if (newLength == null)
        {
            return length != null;
        }
        return length == null || Math.abs(newLength - length) >= LENGTH_CHANGE_THRESHOLD;
    }

    private Float calculateLengthFromSamples()
    {
        if (recentSamples.size() < MIN_SAMPLES)
        {
            return badPenetratingSample;
        }
        // Find the two samples closest to each other, and choose one as the winner.
        // All others are likely mis-measurements during times when we only received an update
        // for one of the OSC values and not the other.
        List<Float> sortedSamples = new ArrayList<>(recentSamples);
        sortedSamples.sort(Float::compare);
        float smallestDiff = 1.f;
        int smallestDiffIndex = -1;
        for (int i = 1; i < sortedSamples.size(); i++)
        {
            float diff = Math.abs(sortedSamples.get(i) - sortedSamples.get(i - 1));
            if (diff < smallestDiff)
            {
                smallestDiff = diff;
                smallestDiffIndex = i;
            }
        }
        if (smallestDiffIndex >= 0)
        {
            return sortedSamples.get(smallestDiffIndex);
        }
        return recentSamples.peekFirst();
    }
}
