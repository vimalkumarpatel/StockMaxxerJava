package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import com.stockmaxxer.Model.EngineInfo;

/** The two recommendation engines. Each turns market data into target weights that sum to exactly 1. */
public final class Engines {

    public static final String QUANT_ONLY = "quant_only";
    public static final String QUANT_LLM = "quant_llm";
    public static final String CASH = "CASH";

    public static final List<EngineInfo> ALL = List.of(
            new EngineInfo(QUANT_LLM, "quant_only, then SIMULATED severity trims (a stand-in for the "
                    + "filing-diff LLM layer); trimmed weight goes to cash, capped at 15%"),
            new EngineInfo(QUANT_ONLY, "Top 8 of the benchmark's 12 largest holdings by 20-day return, weights proportional to "
                    + "1/volatility, 2% cash"));

    // Simplification: 20-day windows and a top 8 (instead of 60/252 days and a top 15), kept from the first
    // slice. The sector cap is skipped (12 names per universe is too few for it), and so is the
    // no-trade band (an accepted recommendation simply rebalances to its target).
    static final int LOOKBACK = 20;
    static final int TOP_N = 8;
    static final BigDecimal INVESTED = new BigDecimal("0.98");           // the other 2% is the cash floor
    static final BigDecimal NAME_CAP = new BigDecimal("0.15");
    static final BigDecimal CASH_CAP = new BigDecimal("0.15");
    static final BigDecimal TRIM_PER_SEVERITY = new BigDecimal("0.10");  // severity 5 trims half the weight
    static final int WEIGHT_SCALE = 6;
    static final MathContext MC = MathContext.DECIMAL64;

    /** One stock's 20-day price change and its daily price movement (standard deviation of daily returns). */
    public record Stat(String symbol, BigDecimal momentum, BigDecimal vol) {}

    /**
     * Target weights by symbol, including CASH, plus the facts behind them that the explanations use:
     * every eligible stock ranked by 20-day price change, the names held at the 15% cap, the severity
     * behind each trim, and the scale applied to the trims when the cash cap bound (1 if it didn't).
     */
    public record Target(Map<String, BigDecimal> weights, Map<String, Integer> severity, List<Stat> ranked,
                         Set<String> capped, BigDecimal trimScale) {}

    /** quant_only's weights (without CASH), the ranking they came from, and the names pinned at the cap. */
    record Base(Map<String, BigDecimal> weights, List<Stat> ranked, Set<String> capped) {}

    /** Final weights including CASH, the severity behind each trim, and the trim scale. */
    record Weights(Map<String, BigDecimal> weights, Map<String, Integer> severity, BigDecimal trimScale) {}

    private Engines() {}

    public static boolean exists(String engine) {
        return ALL.stream().anyMatch(e -> e.id().equals(engine));
    }

    /** Target weights for one universe (the portfolio's benchmark picks it). */
    public static Target target(String engine, LocalDate asOf, Market market, List<String> universe) {
        Base base = quantOnly(asOf, market, universe);
        Weights w = switch (engine) {
            case QUANT_ONLY -> withCash(base.weights(), Map.of(), BigDecimal.ONE);
            case QUANT_LLM -> trimmed(base.weights(), asOf);
            default -> throw new IllegalArgumentException("unknown engine: " + engine);
        };
        return new Target(w.weights(), w.severity(), base.ranked(), base.capped(), w.trimScale());
    }

    /** Rank by 20-day return, keep the top 8, weight each in proportion to 1/volatility, scaled to 0.98, capped at 15%. */
    static Base quantOnly(LocalDate asOf, Market market, List<String> universe) {
        List<Stat> stats = new ArrayList<>();
        for (String symbol : universe) {
            List<BigDecimal> closes = market.closes(symbol, asOf, LOOKBACK + 1);
            if (closes.size() < LOOKBACK + 1) {
                continue;   // not enough history yet
            }
            List<BigDecimal> returns = new ArrayList<>();
            for (int i = 1; i < closes.size(); i++) {
                returns.add(closes.get(i).divide(closes.get(i - 1), MC).subtract(BigDecimal.ONE));
            }
            BigDecimal momentum = closes.getLast().divide(closes.getFirst(), MC).subtract(BigDecimal.ONE);
            stats.add(new Stat(symbol, momentum, stdev(returns)));
        }
        List<Stat> ranked = stats.stream()
                .sorted(Comparator.comparing(Stat::momentum).reversed().thenComparing(Stat::symbol))
                .toList();
        List<Stat> top = ranked.stream().limit(TOP_N).toList();
        Set<String> capped = new TreeSet<>();
        Map<String, BigDecimal> inverse = new TreeMap<>();
        for (Stat s : top) {
            inverse.put(s.symbol(), BigDecimal.ONE.divide(s.vol(), MC));
        }
        // Names over the 15% cap are pinned at it and the excess is spread over the rest, as in the
        // original. It repeats, because spreading the excess can push another name over the cap.
        Map<String, BigDecimal> weights = new TreeMap<>();
        BigDecimal budget = INVESTED;
        while (true) {
            Map<String, BigDecimal> free = new TreeMap<>(inverse);
            free.keySet().removeAll(weights.keySet());
            BigDecimal total = sum(free.values());
            BigDecimal left = budget;
            List<String> over = free.keySet().stream()
                    .filter(s -> left.multiply(free.get(s)).divide(total, MC).compareTo(NAME_CAP) > 0)
                    .toList();
            if (over.isEmpty()) {
                for (var e : free.entrySet()) {
                    // Rounded down, so the invested total never exceeds 0.98 and cash never drops below its floor.
                    weights.put(e.getKey(), left.multiply(e.getValue()).divide(total, MC)
                            .setScale(WEIGHT_SCALE, RoundingMode.DOWN));
                }
                return new Base(weights, ranked, capped);
            }
            for (String s : over) {
                weights.put(s, NAME_CAP);
                capped.add(s);
                budget = budget.subtract(NAME_CAP);
            }
        }
    }

    /** Sample standard deviation (n - 1). Not annualised: a constant factor cancels out in the weighting. */
    static BigDecimal stdev(List<BigDecimal> xs) {
        BigDecimal n = BigDecimal.valueOf(xs.size());
        BigDecimal mean = sum(xs).divide(n, MC);
        BigDecimal squares = sum(xs.stream().map(x -> x.subtract(mean).pow(2)).toList());
        return squares.divide(n.subtract(BigDecimal.ONE), MC).sqrt(MC);
    }

    /**
     * quant_llm. Simplification: a SIMULATED severity (0-5) per symbol and day stands in for the
     * filing-diff LLM layer, which this slice doesn't build. The rules around it are the real design's:
     * a trim can only lower a weight, trimmed weight becomes cash, and cash is capped at 15% by scaling
     * every trim down pro-rata.
     */
    static Weights trimmed(Map<String, BigDecimal> base, LocalDate asOf) {
        Map<String, Integer> severity = new TreeMap<>();
        Map<String, BigDecimal> trim = new TreeMap<>();
        for (var e : base.entrySet()) {
            int s = simulatedSeverity(e.getKey(), asOf);
            severity.put(e.getKey(), s);
            trim.put(e.getKey(), e.getValue().multiply(TRIM_PER_SEVERITY).multiply(BigDecimal.valueOf(s)));
        }
        BigDecimal totalTrim = sum(trim.values());
        BigDecimal room = CASH_CAP.subtract(BigDecimal.ONE.subtract(sum(base.values()))).max(BigDecimal.ZERO);
        BigDecimal scale = totalTrim.compareTo(room) > 0 ? room.divide(totalTrim, MC) : BigDecimal.ONE;

        Map<String, BigDecimal> weights = new TreeMap<>();
        for (var e : base.entrySet()) {
            BigDecimal w = e.getValue().subtract(trim.get(e.getKey()).multiply(scale, MC));
            // Rounded UP: still never above the base weight (both sit on the 6-dp grid), and it stops
            // rounding dust from pushing cash past the cap.
            weights.put(e.getKey(), w.setScale(WEIGHT_SCALE, RoundingMode.UP));
        }
        return withCash(weights, severity, scale);
    }

    static int simulatedSeverity(String symbol, LocalDate asOf) {
        Random rng = new Random(Objects.hash("severity", symbol, asOf.toEpochDay()));
        return rng.nextInt(10) < 7 ? 0 : 1 + rng.nextInt(5);   // about 70% of days carry no event
    }

    /** Cash is the residual, so the weights including CASH sum to exactly 1 by construction. */
    static Weights withCash(Map<String, BigDecimal> weights, Map<String, Integer> severity, BigDecimal trimScale) {
        Map<String, BigDecimal> all = new TreeMap<>(weights);
        all.put(CASH, BigDecimal.ONE.subtract(sum(weights.values())));
        return new Weights(all, severity, trimScale);
    }

    static BigDecimal sum(Collection<BigDecimal> xs) {
        return xs.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
