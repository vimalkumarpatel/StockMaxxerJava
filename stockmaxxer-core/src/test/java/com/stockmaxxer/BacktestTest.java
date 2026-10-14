package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BacktestTest {

    @TempDir
    Path dir;

    @Test
    void theSameRunGivesTheSameResultAndNoFlagsMeansNoDifference() {
        TestDb.World w = TestDb.seeded(dir);
        for (int i = 0; i < 10; i++) {
            TestDb.nextDay(w);
        }
        Backtest backtest = new Backtest(w.market(), w.signals());
        List<Backtest.Result> first = backtest.run("SPY", w.market().today().minusDays(14));

        assertThat(backtest.run("SPY", w.market().today().minusDays(14))).isEqualTo(first);
        assertThat(first).extracting(Backtest.Result::name)
                .containsExactly(Engines.QUANT_ONLY, Engines.QUANT_LLM, "SPY (buy and hold)");
        // No filings in the test database: quant_llm trims nothing, so it is quant_only.
        assertThat(first.get(1).endNav()).isEqualByComparingTo(first.get(0).endNav());
        assertThat(first.get(0).averageCash()).isBetween(new BigDecimal("0.015"), new BigDecimal("0.025"));   // 2% at the open, drifts by the close
        assertThat(first.get(0).maxDrawdown()).isNotNegative();
    }

    @Test
    void bucketsAreOneRowPerFilingGroupedBySeverity() {
        List<Evals.Row> rows = List.of(
                new Evals.Row("a", 0, new BigDecimal("0.02")),
                new Evals.Row("b", 4, new BigDecimal("-0.03")),
                new Evals.Row("c", 4, new BigDecimal("-0.01")));
        assertThat(Evals.buckets(rows)).containsExactly(
                new Evals.Bucket(0, 1, new BigDecimal("0.020000")),
                new Evals.Bucket(4, 2, new BigDecimal("-0.020000")));
    }
}
