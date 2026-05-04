package org.example.processor;


import org.assertj.core.util.Lists;
import org.example.config.ConfigProperties;
import org.example.handy.common.dto.HspPoint;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HspParameterProcessorTest
{
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