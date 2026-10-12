package org.example;

import org.example.processor.PenetratorLengthDetector;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class AppControllerTestModeTest
{
    /**
     * In ORIFICE test mode the depth is sent as the proximities of a simulated penetrator: the processor has to
     * detect its length and arrive at the same depth again.
     */
    @Test
    void simulatedProximitiesDescribeTheDepthAndTheLengthOfTheTestPenetrator()
    {
        var detector = new PenetratorLengthDetector();
        for (float depth : new float[]{0f, 0.25f, 0.5f, 0.75f, 1f})
        {
            float root = AppController.testRootProximity(depth);
            detector.update(root, root + AppController.TEST_PENETRATOR_LENGTH);

            // The same formula the processors use: penetration = 1 - exposed length / penetrator length
            float exposed = 1f - root;
            assertThat(1f - exposed / AppController.TEST_PENETRATOR_LENGTH).isCloseTo(depth, within(0.0001f));
        }
        assertThat(detector.getLength()).isCloseTo(AppController.TEST_PENETRATOR_LENGTH, within(0.005f));
    }
}
