package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnginesTest {

    @TempDir
    Path dir;

    @Test
    void targetsSumToExactlyOneAndTheLlmEngineOnlyTrims() {
        TestDb.World w = TestDb.seeded(dir);
        int daysWithTrims = 0;
        for (int day = 0; day < 15; day++) {
            LocalDate today = w.market().today();
            Engines.Target only = Engines.target(Engines.QUANT_ONLY, today, w.market(), Market.UNIVERSES.get("SPY"));
            Engines.Target llm = Engines.target(Engines.QUANT_LLM, today, w.market(), Market.UNIVERSES.get("SPY"));

            // compareTo, not equals: equals would call 1.000000 and 1 different.
            assertThat(Engines.sum(only.weights().values())).isEqualByComparingTo("1");
            assertThat(Engines.sum(llm.weights().values())).isEqualByComparingTo("1");
            assertThat(only.weights()).hasSize(Engines.TOP_N + 1);   // 8 names + CASH
            assertThat(only.weights().get(Engines.CASH)).isBetween(new BigDecimal("0.02"), new BigDecimal("0.0201"));
            assertThat(llm.weights().get(Engines.CASH)).isLessThanOrEqualTo(Engines.CASH_CAP);

            for (var e : only.weights().entrySet()) {
                if (e.getKey().equals(Engines.CASH)) {
                    continue;
                }
                assertThat(e.getValue()).as("15%% name cap: %s on %s", e.getKey(), today)
                        .isLessThanOrEqualTo(Engines.NAME_CAP);
                assertThat(llm.weights().get(e.getKey()))
                        .as("a trim never raises a weight: %s on %s", e.getKey(), today)
                        .isLessThanOrEqualTo(e.getValue())
                        .isPositive();
            }
            if (llm.severity().values().stream().anyMatch(s -> s > 0)) {
                daysWithTrims++;
            }
            TestDb.nextDay(w);
        }
        assertThat(daysWithTrims).as("the simulated severity must actually trim on some days").isPositive();
    }
}
