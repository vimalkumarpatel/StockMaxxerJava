package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Path;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Random;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

/**
 * A fresh SQLite file per test, wired the same way Spring wires the app (no Spring context needed).
 * Tests never touch the network: {@code history} is filled with made-up bars for the real tickers.
 */
final class TestDb {

    record World(JdbcClient db, Market market, Signals signals, Portfolios portfolios, Llm llm) {}

    static final LocalDate FIRST_DAY = LocalDate.of(2025, 11, 3);   // a Monday
    static final int DAYS = 80;                                      // weekdays of fixture history
    static final int START_INDEX = 30;                               // the clock starts on the 31st day

    private TestDb() {}

    static World seeded(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("test.sqlite"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcClient db = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Market market = new Market(db, tx, new Yahoo());   // Yahoo is never called in tests
        LocalDate start = writeHistory(db);
        market.startAt(start);
        // Replay only, with the cache in the test's temp folder: a test can never call Claude.
        Llm llm = new Llm(dir.toString(), true, "test-model", "");
        Signals signals = new Signals(db, llm, tx);
        return new World(db, market, signals, new Portfolios(db, market, signals, new Audit(db), tx), llm);
    }

    /** The market side of a night: a new simulated day and its bars. */
    static void nextDay(World w) {
        w.market().advance();
        w.market().reveal(w.market().today());
    }

    /** A whole night, in the same order as scripts/nightly.sh. */
    static void nightly(World w) {
        nextDay(w);
        w.portfolios().expireAll();
        w.portfolios().settleAll();
        w.portfolios().markAll();
        w.portfolios().proposeAll();
    }

    /** Made-up bars for every symbol on DAYS weekdays: a random walk seeded by (symbol, date). Returns the start day. */
    private static LocalDate writeHistory(JdbcClient db) {
        List<String> symbols = Market.allSymbols();
        LocalDate day = FIRST_DAY;
        LocalDate start = null;
        double[] prev = new double[symbols.size()];
        for (int d = 0; d < DAYS; d++) {
            for (int k = 0; k < symbols.size(); k++) {
                double vol = 0.008 + 0.001 * (k % 12);
                double drift = 0.001 * (k % 5 - 2);
                double last = prev[k] == 0 ? 20 + 10 * k : prev[k];
                Random rng = new Random(Objects.hash(symbols.get(k), day.toEpochDay()));
                double close = last * (1 + drift + vol * rng.nextGaussian());
                double open = last * (1 + 0.3 * vol * rng.nextGaussian());
                prev[k] = close;
                db.sql("INSERT INTO history (symbol, trade_date, open, close) VALUES (?, ?, ?, ?)")
                        .params(symbols.get(k), day.toString(), price(open), price(close)).update();
            }
            if (d == START_INDEX) {
                start = day;
            }
            day = day.plusDays(day.getDayOfWeek() == DayOfWeek.FRIDAY ? 3 : 1);
        }
        return start;
    }

    private static String price(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_EVEN).toPlainString();
    }
}
