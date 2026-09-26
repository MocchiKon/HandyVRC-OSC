package org.example.processor;


import org.assertj.core.util.Lists;
import org.example.config.ConfigProperties;
import org.example.handy.common.dto.MovementPoint;
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

    @Test
    void penetratorValueIsMappedToFullyPenetratedAtValue()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .fullyPenetratedAtValue(50f)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnValueChange(0.125f); // 12.5% -> 25% penetration -> position 75
        processor.actOnValueChange(0.25f); // 25% -> 50% penetration -> position 50
        processor.actOnValueChange(0.5f); // 50% counts as fully penetrated (100%) -> position 0
        processor.actOnValueChange(0.8f); // Above the configured value, capped at 100% -> position 0
        processor.actOnValueChange(1.f);

        assertThat(positions).containsExactly(75, 50, 0, 0, 0);
    }

    @Test
    void unusableFullyPenetratedAtValueIsIgnored()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.PENETRATOR)
                .fullyPenetratedAtValue(0f)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);

        processor.actOnValueChange(0.25f);

        assertThat(positions).containsExactly(75); // Value used as it is
    }

    @Test
    void orificePenetrationIsMappedToFullyPenetratedAtValue()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .spsType(SpsType.ORIFICE)
                .fullyPenetratedAtValue(50f)
                .build());
        var positions = new ArrayList<Integer>();
        processor.setValueChangeListener(positions::add);
        for (int i = 0; i < 4; i++)
        {
            processor.actOnProximityChange(0.1f, 0.6f); // 0.5m long penetrator, length is trusted after a few samples
        }
        positions.clear();

        processor.actOnProximityChange(0.625f, null); // Calculated penetration 25% -> mapped 50% -> position 50
        processor.actOnProximityChange(0.75f, null); // Calculated penetration 50% -> mapped 100% -> position 0

        assertThat(positions).containsExactly(50, 0);
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
        List<MovementPoint> result = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 0),
                new MovementPoint(100, 50),
                new MovementPoint(200, 100)
        ));

        assertThat(result).containsExactly(
                new MovementPoint(200, 100)
        );
    }

    @Test
    void clampPointsForOnePointOnly()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 50)
        ));

        assertThat(result).containsExactly(
                new MovementPoint(0, 50)
        );
    }

    @Test
    void clampPointsForOnePointOnlyMessages()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 50)
        ));
        List<MovementPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(100, 30)
        ));
        List<MovementPoint> result3 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(200, 20)
        ));
        List<MovementPoint> result4 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(300, 10)
        ));
        List<MovementPoint> result5 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(400, 80)
        ));
        List<MovementPoint> result6 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(500, 100),
                new MovementPoint(600, 100)
        ));

        assertThat(result1).containsExactly(
                new MovementPoint(0, 50)
        );
        assertThat(result2).containsExactly(
                new MovementPoint(100, 30)
        );
        assertThat(result3).containsExactly(
                new MovementPoint(200, 20)
        );
        assertThat(result4).containsExactly(
                new MovementPoint(300, 10)
        );
        assertThat(result5).containsExactly(
                new MovementPoint(400, 80)
        );
        assertThat(result6).containsExactly(
                new MovementPoint(500, 100)
        );
    }

    @Test
    void clampPointsForFirstMessageWithTurnInside()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 0),
                new MovementPoint(100, 50),
                new MovementPoint(200, 30),
                new MovementPoint(300, 20),
                new MovementPoint(400, 10),
                new MovementPoint(500, 70),
                new MovementPoint(600, 80)
        ));

        assertThat(result).containsExactly(
                new MovementPoint(100, 50),
                new MovementPoint(400, 10),
                new MovementPoint(600, 80)
        );
    }

    @Test
    void clampPointsOverMultipleMessagesWithTurnBetween()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 0),
                new MovementPoint(100, 50),
                new MovementPoint(200, 100)
        ));
        List<MovementPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(300, 80),
                new MovementPoint(400, 50),
                new MovementPoint(500, 30)
        ));

        assertThat(result1).containsExactly(
                new MovementPoint(200, 100)
        );
        assertThat(result2).containsExactly(
                new MovementPoint(500, 30)
        );
    }

    @Test
    void clampPointsOverMultipleMessagesWithTurnBetween2()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 0),
                new MovementPoint(100, 50),
                new MovementPoint(200, 100)
        ));
        List<MovementPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(300, 80),
                new MovementPoint(400, 100),
                new MovementPoint(500, 50),
                new MovementPoint(600, 30)
        ));

        assertThat(result1).containsExactly(
                new MovementPoint(200, 100)
        );
        assertThat(result2).containsExactly(
                new MovementPoint(300, 80),
                new MovementPoint(400, 100),
                new MovementPoint(600, 30)
        );
    }

    @Test
    void clampPointsOverMultipleMessages()
    {
        var processor = new HspParameterProcessor(ConfigProperties.builder()
                .clamp(true)
                .build());
        List<MovementPoint> result1 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(0, 0),
                new MovementPoint(100, 20)
        ));
        List<MovementPoint> result2 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(200, 30),
                new MovementPoint(300, 40),
                new MovementPoint(400, 50)
        ));
        List<MovementPoint> result3 = processor.clampPoints(Lists.newArrayList(
                new MovementPoint(500, 60),
                new MovementPoint(600, 70)
        ));

        assertThat(result1).containsExactly(
                new MovementPoint(100, 20)
        );
        assertThat(result2).containsExactly(
                new MovementPoint(400, 50)
        );
        assertThat(result3).containsExactly(
                new MovementPoint(600, 70)
        );
    }
}