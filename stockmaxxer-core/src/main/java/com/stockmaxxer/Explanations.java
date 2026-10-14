package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * A 2-3 sentence explanation of one line of a recommendation, for a reader who is not a specialist.
 * Written to the ASD-STE100 (Simplified Technical English) writing rules: at most 25 words a sentence,
 * active voice, one idea a sentence, simple words. Every number comes from the decision itself, so an
 * explanation cannot disagree with the trade. Simplification: fixed templates instead of an LLM.
 */
final class Explanations {

    private Explanations() {}

    static String explain(String symbol, BigDecimal prior, BigDecimal target, Engines.Target t) {
        if (symbol.equals(Engines.CASH)) {
            return cash(prior, target, t);
        }
        if (t.noRiskFactors().contains(symbol)) {
            return "The latest 10-K of " + symbol + " has no Item 1A (Risk Factors) section, so the system does not"
                    + " use this stock. " + change(prior, target);
        }
        int rank = rankOf(symbol, t.ranked());
        if (rank == 0) {
            return "There is not sufficient price history for " + symbol + ". " + change(prior, target);
        }
        Engines.Stat stat = t.ranked().get(rank - 1);
        String why = symbol + " is number " + rank + " of " + t.ranked().size()
                + " stocks for price change in the last 20 trading days (" + signedPct(stat.momentum()) + ")";
        if (target.signum() == 0) {
            return why + ". It is not in the top " + Engines.TOP_N + " now. " + change(prior, target);
        }
        return why + ", so it is in the top " + Engines.TOP_N + ". " + size(symbol, stat, t) + " " + change(prior, target);
    }

    /** Why the stock has this part of the portfolio: a risk trim, the cap, or how much its price moves. */
    private static String size(String symbol, Engines.Stat stat, Engines.Target t) {
        Signals.Flag flag = t.flags().get(symbol);
        if (flag != null) {
            return "Its " + flag.form() + " of " + flag.filedOn() + " adds a risk at level " + flag.severity()
                    + " of 5, so the system makes its part smaller.";
        }
        if (t.capped().contains(symbol)) {
            return "Its part is at the " + limit(Engines.NAME_CAP) + " limit for one stock.";
        }
        List<Engines.Stat> top = t.ranked().subList(0, Math.min(Engines.TOP_N, t.ranked().size()));
        long moveMore = top.stream().filter(o -> o.vol().compareTo(stat.vol()) > 0).count();
        return moveMore * 2 >= top.size() - 1   // at least half of the other top stocks move more
                ? "Its price moves less each day than most of the other top stocks, so it gets a larger part."
                : "Its price moves more each day than most of the other top stocks, so it gets a smaller part.";
    }

    /** What the system does with the stock. */
    private static String change(BigDecimal prior, BigDecimal target) {
        if (target.signum() == 0) {
            return "The system sells all of it: its part changes from " + pct(prior) + " to 0%.";
        }
        if (prior.signum() == 0) {
            return "The system buys it: " + pct(target) + " of the portfolio.";
        }
        int c = target.compareTo(prior);
        if (c > 0) {
            return "The system buys more: its part changes from " + pct(prior) + " to " + pct(target) + ".";
        }
        if (c < 0) {
            return "The system sells some: its part changes from " + pct(prior) + " to " + pct(target) + ".";
        }
        return "Its part stays at " + pct(target) + ".";
    }

    private static String cash(BigDecimal prior, BigDecimal target, Engines.Target t) {
        boolean trims = t.severity().values().stream().anyMatch(s -> s > 0);
        String reason = !trims
                ? "The system keeps a minimum of " + limit(BigDecimal.ONE.subtract(Engines.INVESTED)) + " in cash."
                : t.trimScale().compareTo(BigDecimal.ONE) < 0
                        ? "Risk trims move money from stocks to cash, but cash cannot be more than "
                                + limit(Engines.CASH_CAP) + ", so the trims are made smaller."
                        : "Risk trims move money from stocks to cash.";
        String change = target.compareTo(prior) == 0
                ? "Cash stays at " + pct(target) + "."
                : "Cash changes from " + pct(prior) + " to " + pct(target) + ".";
        return "Cash is the money that is not in stocks. " + reason + " " + change;
    }

    private static int rankOf(String symbol, List<Engines.Stat> ranked) {
        for (int i = 0; i < ranked.size(); i++) {
            if (ranked.get(i).symbol().equals(symbol)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** A weight as a percentage: 0.062 -> "6.20%". */
    private static String pct(BigDecimal weight) {
        return weight.movePointRight(2).setScale(2, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    /** A price change with its sign: 0.042 -> "+4.2%". */
    private static String signedPct(BigDecimal change) {
        return (change.signum() > 0 ? "+" : "") + change.movePointRight(2).setScale(1, RoundingMode.HALF_EVEN).toPlainString() + "%";
    }

    /** A limit as a whole percentage: 0.15 -> "15%". */
    private static String limit(BigDecimal weight) {
        return weight.movePointRight(2).stripTrailingZeros().toPlainString() + "%";
    }
}
