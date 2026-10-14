package com.stockmaxxer;

import java.nio.file.Path;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.support.TransactionTemplate;
import org.sqlite.SQLiteDataSource;

/** A fresh, seeded SQLite file per test, wired the same way Spring wires the app (no Spring context needed). */
final class TestDb {

    record World(JdbcClient db, Market market, Portfolios portfolios) {}

    private TestDb() {}

    static World seeded(Path dir) {
        SQLiteDataSource ds = new SQLiteDataSource();
        ds.setUrl("jdbc:sqlite:" + dir.resolve("test.sqlite"));
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(ds);
        JdbcClient db = JdbcClient.create(ds);
        TransactionTemplate tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Market market = new Market(db, tx);
        market.seed();
        return new World(db, market, new Portfolios(db, market, tx));
    }

    /** The market side of a night: a new simulated day and its bars. */
    static void nextDay(World w) {
        w.market().advance();
        w.market().writeBars(w.market().today());
    }

    /** A whole night, in the same order as scripts/nightly.sh. */
    static void nightly(World w) {
        nextDay(w);
        w.portfolios().expireAll();
        w.portfolios().settleAll();
        w.portfolios().markAll();
        w.portfolios().proposeAll();
    }
}
