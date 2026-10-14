package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;

/**
 * The backtest: both engines and the benchmark over the revealed days, in memory. Every day each engine's target
 * is filled at the next open and marked at that day's close, as if every recommendation were accepted. The fill
 * rule is the settle job's: fractional shares rounded down, no costs, the rest in cash.
 */
@Service
public class Backtest {

    static final BigDecimal START_NAV = new BigDecimal("1000.00");

    /** One leg's result. Returns, drawdown and cash are fractions (0.05 = 5%). */
    public record Result(String name, BigDecimal endNav, BigDecimal totalReturn, BigDecimal maxDrawdown,
                         BigDecimal averageCash, int days) {}

    private final Market market;
    private final Signals signals;

    public Backtest(Market market, Signals signals) {
        this.market = market;
        this.signals = signals;
    }

    /** quant_only, quant_llm and buy-and-hold of the benchmark, from {@code from} to the simulated day. */
    public List<Result> run(String benchmark, LocalDate from) {
        List<String> universe = Market.UNIVERSES.get(benchmark);
        if (universe == null) {
            throw new IllegalArgumentException("benchmark must be one of " + Market.BENCHMARKS);
        }
        List<LocalDate> days = market.tradingDays(from, market.today());
        if (days.size() < 2) {
            throw new IllegalArgumentException("need at least 2 revealed trading days from " + from);
        }
        return List.of(engine(Engines.QUANT_ONLY, universe, days), engine(Engines.QUANT_LLM, universe, days),
                buyAndHold(benchmark, days));
    }

    private Result engine(String engine, List<String> universe, List<LocalDate> days) {
        BigDecimal cash = START_NAV;
        Map<String, BigDecimal> shares = new TreeMap<>();
        List<BigDecimal> navs = new ArrayList<>();
        BigDecimal cashSum = BigDecimal.ZERO;
        for (int i = 0; i + 1 < days.size(); i++) {
            LocalDate day = days.get(i);
            LocalDate next = days.get(i + 1);
            Engines.Target target = Engines.target(engine, day, market, universe, signals.flags(universe, day),
                    signals.withRiskFactors(universe, day));
            // Fill at the next open: value everything, then buy each target weight.
            BigDecimal navAtOpen = cash;
            for (var e : shares.entrySet()) {
                navAtOpen = navAtOpen.add(e.getValue().multiply(market.bar(e.getKey(), next).open()));
            }
            shares.clear();
            cash = navAtOpen;
            for (var e : target.weights().entrySet()) {
                if (e.getKey().equals(Engines.CASH) || e.getValue().signum() == 0) {
                    continue;
                }
                BigDecimal open = market.bar(e.getKey(), next).open();
                BigDecimal n = e.getValue().multiply(navAtOpen).divide(open, 6, RoundingMode.DOWN);
                shares.put(e.getKey(), n);
                cash = cash.subtract(n.multiply(open));
            }
            // Mark at that day's close.
            BigDecimal nav = cash;
            for (var e : shares.entrySet()) {
                nav = nav.add(e.getValue().multiply(market.bar(e.getKey(), next).close()));
            }
            navs.add(nav);
            cashSum = cashSum.add(cash.divide(nav, 6, RoundingMode.HALF_EVEN));
        }
        return result(engine, navs, cashSum.divide(BigDecimal.valueOf(navs.size()), 6, RoundingMode.HALF_EVEN));
    }

    /** The benchmark, bought at the first fill's open and held. */
    private Result buyAndHold(String benchmark, List<LocalDate> days) {
        BigDecimal shares = START_NAV.divide(market.bar(benchmark, days.get(1)).open(), 6, RoundingMode.DOWN);
        List<BigDecimal> navs = new ArrayList<>();
        for (LocalDate day : days.subList(1, days.size())) {
            navs.add(shares.multiply(market.bar(benchmark, day).close()));
        }
        return result(benchmark + " (buy and hold)", navs, BigDecimal.ZERO);
    }

    /** End NAV, total return, and the largest fall from a peak. */
    private static Result result(String name, List<BigDecimal> navs, BigDecimal averageCash) {
        BigDecimal peak = START_NAV;
        BigDecimal maxDrawdown = BigDecimal.ZERO;
        for (BigDecimal nav : navs) {
            peak = peak.max(nav);
            maxDrawdown = maxDrawdown.max(peak.subtract(nav).divide(peak, 6, RoundingMode.HALF_EVEN));
        }
        BigDecimal end = navs.getLast().setScale(2, RoundingMode.HALF_EVEN);
        return new Result(name, end, end.divide(START_NAV, 6, RoundingMode.HALF_EVEN).subtract(BigDecimal.ONE),
                maxDrawdown, averageCash, navs.size());
    }
}
