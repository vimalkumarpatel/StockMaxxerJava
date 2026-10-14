package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnginesTest {

    @TempDir
    Path dir;

    static final List<String> SPY = Market.UNIVERSES.get("SPY");

    @Test
    void targetsSumToExactlyOneAndTheLlmEngineOnlyTrims() {
        TestDb.World w = TestDb.seeded(dir);
        for (int day = 0; day < 15; day++) {
            LocalDate today = w.market().today();
            Engines.Target only = Engines.target(Engines.QUANT_ONLY, today, w.market(), SPY, Map.of());
            Engines.Target llm = Engines.target(Engines.QUANT_LLM, today, w.market(), SPY, flags(day));

            // compareTo, not equals: equals would call 1.000000 and 1 different.
            assertThat(Engines.sum(only.weights().values())).isEqualByComparingTo("1");
            assertThat(Engines.sum(llm.weights().values())).isEqualByComparingTo("1");
            assertThat(only.weights()).hasSize(Engines.TOP_N + 1);   // 8 names + CASH
            assertThat(only.weights().get(Engines.CASH)).isBetween(new BigDecimal("0.02"), new BigDecimal("0.0201"));
            assertThat(llm.weights().get(Engines.CASH)).isLessThanOrEqualTo(Engines.CASH_CAP);
            assertThat(llm.flags()).as("some made-up flags hit held names").isNotEmpty();

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
            TestDb.nextDay(w);
        }
    }

    @Test
    void withNoFlagsTheLlmEngineIsQuantOnly() {
        TestDb.World w = TestDb.seeded(dir);
        LocalDate today = w.market().today();
        Map<String, BigDecimal> llm = Engines.target(Engines.QUANT_LLM, today, w.market(), SPY, Map.of()).weights();
        Map<String, BigDecimal> only = Engines.target(Engines.QUANT_ONLY, today, w.market(), SPY, Map.of()).weights();
        assertThat(llm.keySet()).isEqualTo(only.keySet());
        only.forEach((symbol, weight) -> assertThat(llm.get(symbol)).as(symbol).isEqualByComparingTo(weight));
    }

    /** Made-up filing flags: severity 0-5 spread over the universe, shifting each day. */
    private static Map<String, Signals.Flag> flags(int day) {
        Map<String, Signals.Flag> flags = new TreeMap<>();
        for (int k = 0; k < SPY.size(); k++) {
            flags.put(SPY.get(k), new Signals.Flag((k + day) % 6, "acc-" + k, "10-Q", LocalDate.of(2025, 11, 1), "A risk."));
        }
        return flags;
    }
}
