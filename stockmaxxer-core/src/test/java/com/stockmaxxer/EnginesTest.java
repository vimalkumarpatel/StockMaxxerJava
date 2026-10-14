package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnginesTest {

    @TempDir
    Path dir;

    static final List<String> SPY = Market.UNIVERSES.get("SPY");
    static final Set<String> ALL = Set.copyOf(SPY);   // every stock has an Item 1A

    @Test
    void targetsSumToExactlyOneAndTheLlmEngineOnlyTrims() {
        TestDb.World w = TestDb.seeded(dir);
        for (int day = 0; day < 15; day++) {
            LocalDate today = w.market().today();
            Engines.Target only = Engines.target(Engines.QUANT_ONLY, today, w.market(), SPY, Map.of(), ALL);
            Engines.Target llm = Engines.target(Engines.QUANT_LLM, today, w.market(), SPY, flags(day), ALL);

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
        Map<String, BigDecimal> llm = Engines.target(Engines.QUANT_LLM, today, w.market(), SPY, Map.of(), ALL).weights();
        Map<String, BigDecimal> only = Engines.target(Engines.QUANT_ONLY, today, w.market(), SPY, Map.of(), ALL).weights();
        assertThat(llm.keySet()).isEqualTo(only.keySet());
        only.forEach((symbol, weight) -> assertThat(llm.get(symbol)).as(symbol).isEqualByComparingTo(weight));
    }

    @Test
    void aStockWhoseLatest10kHasNoItem1aIsNeverRecommended() {
        TestDb.World w = TestDb.seeded(dir);
        List<String> qqq = Market.UNIVERSES.get("QQQ");
        // INTC's newer 10-K has only a contents line, like Intel's real ones.
        new Edgar(w.db(), "", dir.toString()).store("INTC", 50863, "10-K", LocalDate.of(2025, 6, 2), "intc-k", "https://x",
                "<p>Item 1A. Risk Factors Pages 37-51</p><p>Item 1B. Unresolved Staff Comments Page 52</p>");
        Set<String> allowed = w.signals().withRiskFactors(qqq, w.market().today());
        assertThat(allowed).hasSize(11).doesNotContain("INTC");

        for (int day = 0; day < 10; day++) {
            LocalDate today = w.market().today();
            for (String engine : List.of(Engines.QUANT_ONLY, Engines.QUANT_LLM)) {
                Engines.Target t = Engines.target(engine, today, w.market(), qqq, Map.of(), allowed);
                assertThat(t.weights()).doesNotContainKey("INTC");
                assertThat(t.noRiskFactors()).containsExactly("INTC");
                String why = Explanations.explain("INTC", new BigDecimal("0.10"), BigDecimal.ZERO, t);
                assertThat(why).startsWith("The latest 10-K of INTC has no Item 1A").contains("sells all of it");
                for (String sentence : why.split("(?<=\\.)\\s+")) {
                    assertThat(sentence.split("\\s+").length).as(sentence).isLessThanOrEqualTo(25);
                }
            }
            TestDb.nextDay(w);
        }
    }

    /** Made-up filing flags: severity 0-5 spread over the universe, shifting each day. */
    private static Map<String, Signals.Flag> flags(int day) {
        Map<String, Signals.Flag> flags = new TreeMap<>();
        for (int k = 0; k < SPY.size(); k++) {
            flags.put(SPY.get(k), new Signals.Flag((k + day) % 6, "acc-" + k, "10-Q", LocalDate.of(2025, 11, 1), "A risk.", null, null));
        }
        return flags;
    }
}
