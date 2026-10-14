package com.stockmaxxer;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Market data and the simulated clock. Real daily bars are downloaded into {@code history} once; the clock
 * then replays them one trading day at a time, copying each day into {@code bars} ("revealed so far").
 * Nothing reads {@code history} for decisions, so a bar after the simulated day can't be used by mistake.
 */
@Component
public class Market {

    /** The benchmarks a portfolio can be compared against. Each one picks the portfolio's universe. */
    public static final List<String> BENCHMARKS = List.of("SPY", "QQQ", "IWM", "DIA");

    /**
     * Each benchmark's universe: the ETF's 12 largest holdings on 2026-10-13, one share class per company.
     * Simplification: a fixed list instead of a screen, so it has survivorship bias (today's winners).
     * QQQ skips SPCX (listed in 2026, no history in the replay window); IWM skips its cash fund.
     */
    public static final Map<String, List<String>> UNIVERSES = Map.of(
            "SPY", List.of("NVDA", "AAPL", "MSFT", "AMZN", "GOOGL", "AVGO", "META", "MU", "TSLA", "AMD", "BRK-B", "LLY"),
            "QQQ", List.of("NVDA", "AAPL", "MSFT", "MU", "AMZN", "AMD", "META", "GOOGL", "TSLA", "AVGO", "WMT", "INTC"),
            "IWM", List.of("FROG", "MOG-A", "BTSG", "TWST", "UMBF", "GKOS", "PCVX", "SM", "FCFS", "PTGX", "CTRE", "HUT"),
            "DIA", List.of("GS", "CAT", "MSFT", "AMGN", "V", "UNH", "TRV", "GOOGL", "AAPL", "JPM", "SHW", "AXP"));

    static final LocalDate HISTORY_START = LocalDate.of(2025, 1, 2);   // a year of lookback before the replay
    static final LocalDate START = LocalDate.of(2026, 1, 2);           // the first simulated day

    public record Bar(BigDecimal open, BigDecimal close) {}

    private final JdbcClient db;
    private final TransactionTemplate tx;
    private final Yahoo yahoo;

    public Market(JdbcClient db, TransactionTemplate tx, Yahoo yahoo) {
        this.db = db;
        this.tx = tx;
        this.yahoo = yahoo;
    }

    /** The simulated trading day. */
    public LocalDate today() {
        return db.sql("SELECT today FROM sim_clock WHERE id = 1").query(String.class).optional()
                .map(LocalDate::parse)
                .orElseThrow(() -> new IllegalStateException("no simulated day yet: run the seed job first"));
    }

    /**
     * The advance job: move the clock to the next day that SPY traded, so real holidays are skipped, and reveal
     * that day's bars. One transaction, so a reader (the UI) never sees a day without its bars.
     */
    public LocalDate advance() {
        return tx.execute(status -> {
            LocalDate next = db.sql("SELECT MIN(trade_date) FROM history WHERE symbol = 'SPY' AND trade_date > ?")
                    .param(today().toString()).query(String.class).optional().map(LocalDate::parse)
                    .orElseThrow(() -> new IllegalStateException("no newer day in history: run the fetch job"));
            db.sql("UPDATE sim_clock SET today = ? WHERE id = 1").param(next.toString()).update();
            reveal(next);
            return next;
        });
    }

    /** The seed job: download the history, then start the clock at START. Does nothing if already seeded. */
    public void seed() {
        if (db.sql("SELECT COUNT(*) FROM sim_clock").query(Integer.class).single() > 0) {
            return;
        }
        fetch();
        startAt(START);
    }

    /** Reveal every day of history up to {@code start} and set the clock to the last trading day on or before it. */
    public void startAt(LocalDate start) {
        tx.executeWithoutResult(status -> {
            db.sql("INSERT OR IGNORE INTO bars SELECT symbol, trade_date, open, close FROM history WHERE trade_date <= ?")
                    .param(start.toString()).update();
            String first = db.sql("SELECT MAX(trade_date) FROM history WHERE symbol = 'SPY' AND trade_date <= ?")
                    .param(start.toString()).query(String.class).single();
            db.sql("INSERT INTO sim_clock (id, today) VALUES (1, ?)").param(first).update();
        });
    }

    /**
     * The fetch job: download every symbol's bars after its last stored day, up to yesterday (New York time).
     * Simplification: day D is fetched on D+1, so an unfinished session's close is never stored.
     */
    public int fetch() {
        LocalDate today = LocalDate.now(Yahoo.NEW_YORK);
        int written = 0;
        for (String symbol : allSymbols()) {
            LocalDate from = db.sql("SELECT MAX(trade_date) FROM history WHERE symbol = ?").param(symbol)
                    .query(String.class).optional().map(d -> LocalDate.parse(d).plusDays(1)).orElse(HISTORY_START);
            if (!from.isBefore(today)) {
                continue;   // already up to date
            }
            for (Yahoo.DailyBar b : yahoo.daily(symbol, from, today)) {
                written += db.sql("INSERT OR IGNORE INTO history (symbol, trade_date, open, close) VALUES (?, ?, ?, ?)")
                        .params(symbol, b.date().toString(), b.open().toPlainString(), b.close().toPlainString())
                        .update();
            }
        }
        return written;
    }

    /** The prices job: reveal one day of history. INSERT OR IGNORE makes a re-run harmless. */
    public int reveal(LocalDate day) {
        return db.sql("INSERT OR IGNORE INTO bars SELECT symbol, trade_date, open, close FROM history WHERE trade_date = ?")
                .param(day.toString()).update();
    }

    public Bar bar(String symbol, LocalDate day) {
        return db.sql("SELECT open, close FROM bars WHERE symbol = ? AND trade_date = ?")
                .params(symbol, day.toString())
                .query((rs, row) -> new Bar(new BigDecimal(rs.getString("open")), new BigDecimal(rs.getString("close"))))
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "no bar for " + symbol + " on " + day + ": run the prices job first"));
    }

    /** The last {@code count} closes up to and including {@code asOf}, oldest first. */
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

    /** The revealed trading days from {@code from} to {@code to}, both included, oldest first. */
    public List<LocalDate> tradingDays(LocalDate from, LocalDate to) {
        return db.sql("SELECT trade_date FROM bars WHERE symbol = 'SPY' AND trade_date BETWEEN ? AND ? ORDER BY trade_date")
                .params(from.toString(), to.toString())
                .query((rs, row) -> LocalDate.parse(rs.getString(1))).list();
    }

    /** Every stock in any universe, plus the benchmarks themselves, each once. */
    public static List<String> allSymbols() {
        TreeSet<String> all = new TreeSet<>(BENCHMARKS);
        UNIVERSES.values().forEach(all::addAll);
        return List.copyOf(all);
    }
}
