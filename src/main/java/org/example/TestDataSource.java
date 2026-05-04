package org.example;

import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * Generates fake sawtooth wave value changes for testing without VRC/OSC.
 * Produces a 0->1->0 sawtooth pattern.
 */
@Slf4j
public class TestDataSource
{
    private static final int INTERVAL_MS = 50;
    private static final int CYCLE_MS = 800;
    private static final int HALF_CYCLE = CYCLE_MS / 2;

    private final Consumer<Float> valueConsumer;

    public TestDataSource(Consumer<Float> valueConsumer)
    {
        this.valueConsumer = valueConsumer;
    }

    public void start()
    {
        log.info("Test mode active: generating sawtooth wave data (cycle={}ms, interval={}ms)", CYCLE_MS, INTERVAL_MS);
        Thread.startVirtualThread(this::generateLoop);
    }

    private void generateLoop()
    {
        long startTime = System.currentTimeMillis();
        while (true)
        {
            try
            {
                long elapsed = System.currentTimeMillis() - startTime;
                float value = calculateSawtoothValue(elapsed);
                valueConsumer.accept(value);
                Thread.sleep(INTERVAL_MS);
            }
            catch (InterruptedException e)
            {
                log.error("Test data generation interrupted");
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception e)
            {
                log.error("Error in test data generation", e);
            }
        }
    }

    // Currently supports only PENETRATOR spsType, ORIFICE requires penetratorLength = 1.0
    private float calculateSawtoothValue(long elapsedMs)
    {
        long timeInCycle = elapsedMs % CYCLE_MS;
        float posInHalfCycle = (float) timeInCycle / HALF_CYCLE;
        if (timeInCycle <= HALF_CYCLE)
        {
            return posInHalfCycle; // 0 -> 1
        }
        else
        {
            return 2.f - posInHalfCycle; // Mirror for descending part of sawtooth 1 -> 0
        }
    }
}
