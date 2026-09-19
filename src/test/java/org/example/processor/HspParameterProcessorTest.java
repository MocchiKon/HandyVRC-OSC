package org.example.processor;


import org.assertj.core.util.Lists;
import org.example.config.ConfigProperties;
import org.example.handy.common.dto.HspPoint;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HspParameterProcessorTest
{
    @Test
    void orificeStaysFullyOutUntilPenetratorLengthIsDetected()
    {
        var processor = orificeProcessor();
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnProximityChange(1.f, 1.f); // Fully inserted, but the length is not detected yet

        assertThat(positions).containsExactly(100); // 100 = top = fully out
    }

    @Test
    void orificeUsesAutoDetectedPenetratorLength()
    {
        float rootProximity = 0.5f;
        float tipProximity = 0.55f; // 5cm long penetrator, both values sent in one OSC packet
        var processor = orificeProcessor();
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);
        for (int i = 0; i < 4; i++) // Length is trusted after a few consistent samples
        {
            processor.actOnProximityChange(rootProximity, tipProximity);
        }
        positions.clear();

        processor.actOnProximityChange(1.f, tipProximity); // Nothing is exposed, so fully inserted
        processor.actOnProximityChange(rootProximity, tipProximity); // 0.5m exposed of a 0.05m long penetrator

        assertThat(positions).containsExactly(0, 100); // 0 = bottom = fully inserted
    }

    @Test
    void orificeMovesOnPacketsWithoutTipProximity()
    {
        float rootProximity = 0.5f;
        float tipProximity = 0.55f;
        var processor = orificeProcessor();
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);
        for (int i = 0; i < 4; i++)
        {
            processor.actOnProximityChange(rootProximity, tipProximity);
        }
        positions.clear();

        // VRChat does not resend a parameter that did not change, so only the root proximity arrives
        processor.actOnProximityChange(1.f, null);

        assertThat(positions).containsExactly(0);
    }

    @Test
    void orificeDoesNotMoveOnPacketsWithoutRootProximity()
    {
        var processor = orificeProcessor();
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnProximityChange(null, 0.9f); // Only the tip proximity changed

        assertThat(positions).isEmpty(); // Movement is driven by the root proximity only
    }

    @Test
    void penetratorUsesValueAsPenetrationAndIgnoresProximities()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnProximityChange(0.9f, 0.95f); // Only used for orifice
        processor.actOnValueChange(0.25f);

        assertThat(positions).containsExactly(75);
    }

    private static HspParameterProcessor orificeProcessor()
    {
        return new HspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.ORIFICE)
                .build());
    }

    @Test
    void clampPointsForFirstMessage()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 0),
                new HspPoint(100, 50),
                new HspPoint(200, 100)
        ));

        assertThat(result).containsExactly(
                new HspPoint(200, 100)
        );
    }

    @Test
    void clampPointsForOnePointOnly()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 50)
        ));

        assertThat(result).containsExactly(
                new HspPoint(0, 50)
        );
    }

    @Test
    void clampPointsForOnePointOnlyMessages()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 50)
        ));
        List<HspPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(100, 30)
        ));
        List<HspPoint> result3 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(200, 20)
        ));
        List<HspPoint> result4 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(300, 10)
        ));
        List<HspPoint> result5 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(400, 80)
        ));
        List<HspPoint> result6 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(500, 100),
                new HspPoint(600, 100)
        ));

        assertThat(result1).containsExactly(
                new HspPoint(0, 50)
        );
        assertThat(result2).containsExactly(
                new HspPoint(100, 30)
        );
        assertThat(result3).containsExactly(
                new HspPoint(200, 20)
        );
        assertThat(result4).containsExactly(
                new HspPoint(300, 10)
        );
        assertThat(result5).containsExactly(
                new HspPoint(400, 80)
        );
        assertThat(result6).containsExactly(
                new HspPoint(500, 100)
        );
    }

    @Test
    void clampPointsForFirstMessageWithTurnInside()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 0),
                new HspPoint(100, 50),
                new HspPoint(200, 30),
                new HspPoint(300, 20),
                new HspPoint(400, 10),
                new HspPoint(500, 70),
                new HspPoint(600, 80)
        ));

        assertThat(result).containsExactly(
                new HspPoint(100, 50),
                new HspPoint(400, 10),
                new HspPoint(600, 80)
        );
    }

    @Test
    void clampPointsOverMultipleMessagesWithTurnBetween()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 0),
                new HspPoint(100, 50),
                new HspPoint(200, 100)
        ));
        List<HspPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(300, 80),
                new HspPoint(400, 50),
                new HspPoint(500, 30)
        ));

        assertThat(result1).containsExactly(
                new HspPoint(200, 100)
        );
        assertThat(result2).containsExactly(
                new HspPoint(500, 30)
        );
    }

    @Test
    void clampPointsOverMultipleMessagesWithTurnBetween2()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 0),
                new HspPoint(100, 50),
                new HspPoint(200, 100)
        ));
        List<HspPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(300, 80),
                new HspPoint(400, 100),
                new HspPoint(500, 50),
                new HspPoint(600, 30)
        ));

        assertThat(result1).containsExactly(
                new HspPoint(200, 100)
        );
        assertThat(result2).containsExactly(
                new HspPoint(300, 80),
                new HspPoint(400, 100),
                new HspPoint(600, 30)
        );
    }

    @Test
    void clampPointsOverMultipleMessages()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<HspPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(0, 0),
                new HspPoint(100, 20)
        ));
        List<HspPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(200, 30),
                new HspPoint(300, 40),
                new HspPoint(400, 50)
        ));
        List<HspPoint> result3 = processor.clampPoints(Lists.newArrayList(
                new HspPoint(500, 60),
                new HspPoint(600, 70)
        ));

        assertThat(result1).containsExactly(
                new HspPoint(100, 20)
        );
        assertThat(result2).containsExactly(
                new HspPoint(400, 50)
        );
        assertThat(result3).containsExactly(
                new HspPoint(600, 70)
        );
    }
}