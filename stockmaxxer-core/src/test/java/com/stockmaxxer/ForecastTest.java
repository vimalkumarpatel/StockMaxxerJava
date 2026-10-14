package com.stockmaxxer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.stockmaxxer.Model.ForecastRange;
import com.stockmaxxer.Model.Portfolio;

class ForecastTest {

    @TempDir
    Path dir;

    @Test
    void anAllCashPortfolioDoesNotMove() {
        TestDb.World w = TestDb.seeded(dir);
        Portfolio p = w.portfolios().create("Cash", null, null, null);
        ForecastRange f = w.portfolios().forecast(p.id());
        assertThat(f.p10()).isEqualByComparingTo("1000.00");
        assertThat(f.p50()).isEqualByComparingTo("1000.00");
        assertThat(f.p90()).isEqualByComparingTo("1000.00");
    }

    @Test
    void theRangeIsOrderedAndTheSameEveryTime() {
        TestDb.World w = TestDb.seeded(dir);
        Portfolio p = w.portfolios().create("Held", null, null, Engines.QUANT_ONLY);
        w.portfolios().accept(p.id(), w.portfolios().propose(p.id()).id());
        TestDb.nightly(w);   // fills at the next open

        ForecastRange f = w.portfolios().forecast(p.id());
        assertThat(f.p10()).isLessThan(f.p50());
        assertThat(f.p50()).isLessThan(f.p90());
        assertThat(w.portfolios().forecast(p.id())).isEqualTo(f);
    }

    @Test
    void halfInCashHalvesTheSpread() {
        double[][] returns = {{0.02, -0.01, 0.015, -0.02, 0.01, 0.005, -0.012, 0.018}};
        Forecast.Range full = Forecast.range(1000, new double[] {1.0}, returns);
        Forecast.Range half = Forecast.range(1000, new double[] {0.5}, returns);
        assertThat(half.p90() - half.p10()).isLessThan(full.p90() - full.p10());
    }
}
