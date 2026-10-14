package com.stockmaxxer;

import static java.time.DayOfWeek.SATURDAY;
import static java.time.DayOfWeek.SUNDAY;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Random;
import java.util.stream.Stream;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Market data and the simulated clock: the trading calendar, synthetic prices, and the bars table. */
@Component
public class Market {

    /** Simplification: a fixed universe instead of the SIC/10-K/ADV screen, which needs filings and volume data. */
    public static final List<String> UNIVERSE = List.of(
            "SYN01", "SYN02", "SYN03", "SYN04", "SYN05", "SYN06",
            "SYN07", "SYN08", "SYN09", "SYN10", "SYN11", "SYN12");

    /** The curated benchmark list. These are synthetic series too, so every benchmark has data by construction. */
    public static final List<String> BENCHMARKS = List.of("SPY", "QQQ", "IWM", "DIA");

    static final LocalDate SEED_START = LocalDate.of(2026, 1, 5);   // a Monday
    static final int SEED_DAYS = 30;                                 // enough history for the 20-day lookback

    public record Bar(BigDecimal open, BigDecimal close) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;

    public Market(JdbcClient db, TransactionTemplate tx) {
        this.db = db;
        this.tx = tx;
    }

    /** Simplification: every weekday is a trading day; a holiday calendar would change nothing in the lifecycle. */
    public static LocalDate nextTradingDay(LocalDate day) {
        LocalDate next = day.plusDays(1);
        while (next.getDayOfWeek() == SATURDAY || next.getDayOfWeek() == SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }

    /** The simulated trading day. */
    public LocalDate today() {
        return db.sql("SELECT today FROM sim_clock WHERE id = 1").query(String.class).optional()
                .map(LocalDate::parse)
                .orElseThrow(() -> new IllegalStateException("no simulated day yet: run the seed job first"));
    }

    /** The advance job: move the simulated clock to the next trading day. */
    public LocalDate advance() {
        LocalDate next = nextTradingDay(today());
        db.sql("UPDATE sim_clock SET today = ? WHERE id = 1").param(next.toString()).update();
        return next;
    }

    /** The seed job: a month of price history, with the clock set to its last day. Does nothing if already seeded. */
    public void seed() {
        tx.executeWithoutResult(status -> {
            if (db.sql("SELECT COUNT(*) FROM sim_clock").query(Integer.class).single() > 0) {
                return;
            }
            LocalDate day = SEED_START;
            for (int i = 1; i <= SEED_DAYS; i++) {
                writeBars(day);
                if (i < SEED_DAYS) {
                    day = nextTradingDay(day);
                }
            }
            db.sql("INSERT INTO sim_clock (id, today) VALUES (1, ?)").param(day.toString()).update();
        });
    }

    /** The prices job: write every symbol's bar for one day. INSERT OR IGNORE makes a re-run harmless. */
    public void writeBars(LocalDate day) {
        tx.executeWithoutResult(status -> {
            for (String symbol : allSymbols()) {
                BigDecimal prevClose = db.sql("""
                                SELECT close FROM bars WHERE symbol = ? AND trade_date < ?
                                ORDER BY trade_date DESC LIMIT 1""")
                        .params(symbol, day.toString())
                        .query(String.class).optional().map(BigDecimal::new).orElse(null);
                Bar bar = synthetic(symbol, day, prevClose);
                db.sql("INSERT OR IGNORE INTO bars (symbol, trade_date, open, close) VALUES (?, ?, ?, ?)")
                        .params(symbol, day.toString(), bar.open().toPlainString(), bar.close().toPlainString())
                        .update();
            }
        });
    }

    public Bar bar(String symbol, LocalDate day) {
        return db.sql("SELECT open, close FROM bars WHERE symbol = ? AND trade_date = ?")
                .params(symbol, day.toString())
                .query((rs, row) -> new Bar(new BigDecimal(rs.getString("open")), new BigDecimal(rs.getString("close"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "no bar for " + symbol + " on " + day + ": run the prices job first"));
    }

    /**
     * The last {@code count} closes up to and including {@code asOf}, oldest first. Simplification: no
     * look-ahead guard, because the prices job never writes a bar past the simulated day, so future data
     * doesn't exist.
     */
    public List<BigDecimal> closes(String symbol, LocalDate asOf, int count) {
        List<BigDecimal> newestFirst = db.sql("""
                        SELECT close FROM bars WHERE symbol = ? AND trade_date <= ?
                        ORDER BY trade_date DESC LIMIT ?""")
                .params(symbol, asOf.toString(), count)
                .query((rs, row) -> new BigDecimal(rs.getString("close")))
                .list();
        List<BigDecimal> oldestFirst = new ArrayList<>(newestFirst);
        Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    static List<String> allSymbols() {
        return Stream.concat(UNIVERSE.stream(), BENCHMARKS.stream()).toList();
    }

    /**
     * Simplification: synthetic prices instead of Yahoo/EDGAR ingest. Each symbol is a random walk with
     * its own drift and volatility, seeded by (symbol, date), so every run produces the same world.
     * There are no splits or dividends, so close-to-close is the total return.
     */
    static Bar synthetic(String symbol, LocalDate day, BigDecimal prevClose) {
        double[] p = params(symbol);   // start price, daily volatility, daily drift
        double prev = prevClose == null ? p[0] : prevClose.doubleValue();
        Random rng = new Random(Objects.hash(symbol, day.toEpochDay()));
        double close = prev * (1 + p[2] + p[1] * rng.nextGaussian());
        double open = prev * (1 + 0.3 * p[1] * rng.nextGaussian());
        return new Bar(price(open), price(close));
    }

    private static BigDecimal price(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN);
    }

    private static double[] params(String symbol) {
        return switch (symbol) {
            case "SPY" -> new double[] {600, 0.009, 0.0004};
            case "QQQ" -> new double[] {520, 0.012, 0.0006};
            case "IWM" -> new double[] {220, 0.013, 0.0002};
            case "DIA" -> new double[] {440, 0.008, 0.0003};
            default -> {
                int k = UNIVERSE.indexOf(symbol);   // 0..11 spreads start price, volatility and drift
                yield new double[] {20 + 10 * k, 0.008 + 0.002 * k, 0.001 * (k % 5 - 2)};
            }
        };
    }
}
