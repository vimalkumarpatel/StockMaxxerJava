package com.stockmaxxer;

import java.util.Arrays;
import java.util.Random;

/**
 * The forecast range: a 5-day block bootstrap of the portfolio's own past daily returns at its current weights.
 * It shows how much the NAV has moved over 63 trading days in the past, so it is historical dispersion, not a
 * prediction. Simplification: doubles, not BigDecimal; this is a statistic, not money, rounded to cents at the end.
 */
public final class Forecast {

    public static final int HORIZON = 63;     // trading days, about 3 months
    public static final int LOOKBACK = 252;   // daily returns drawn from, about 1 year
    static final int BLOCK = 5;               // consecutive days drawn together, to keep some autocorrelation
    static final int PATHS = 10_000;
    static final long SEED = 42;              // fixed, so the same portfolio on the same day gives the same range

    /** p10, p50 and p90 of the simulated NAV after HORIZON days. */
    public record Range(double p10, double p50, double p90) {}

    private Forecast() {}

    /**
     * {@code returns[s][t]} is symbol s's return on day t (all symbols on the same days), {@code weights[s]} its
     * share of NAV. Cash is the rest of the NAV and returns 0. The weights stay fixed (rebalanced daily).
     */
    public static Range range(double nav, double[] weights, double[][] returns) {
        int days = returns.length == 0 ? 0 : returns[0].length;
        if (days < BLOCK) {
            return new Range(nav, nav, nav);   // all cash, or not enough history: nothing moves
        }
        double[] daily = new double[days];     // the portfolio's return on each past day
        for (int s = 0; s < weights.length; s++) {
            for (int t = 0; t < days; t++) {
                daily[t] += weights[s] * returns[s][t];
            }
        }
        Random rng = new Random(SEED);
        double[] ends = new double[PATHS];
        for (int p = 0; p < PATHS; p++) {
            double value = nav;
            int filled = 0;
            while (filled < HORIZON) {
                int start = rng.nextInt(days - BLOCK + 1);
                for (int k = 0; k < BLOCK && filled < HORIZON; k++, filled++) {
                    value *= 1 + daily[start + k];
                }
            }
            ends[p] = value;
        }
        Arrays.sort(ends);
        return new Range(ends[PATHS / 10], ends[PATHS / 2], ends[PATHS * 9 / 10]);
    }
}
