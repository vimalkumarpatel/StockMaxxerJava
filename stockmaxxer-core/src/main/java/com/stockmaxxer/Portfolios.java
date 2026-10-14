package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.stockmaxxer.Model.Decision;
import com.stockmaxxer.Model.EquityPoint;
import com.stockmaxxer.Model.Holding;
import com.stockmaxxer.Model.Line;
import com.stockmaxxer.Model.Portfolio;
import com.stockmaxxer.Model.PortfolioDetail;
import com.stockmaxxer.Model.Position;
import com.stockmaxxer.Model.Status;

/**
 * The portfolio lifecycle: create, propose, accept or ignore, then the nightly expire / settle / mark.
 * Errors use plain Java exceptions (bad input, not found, conflict); the API maps them to 400/404/409.
 * Simplification: Spring's JdbcClient with plain SQL instead of repository interfaces, which is fewer
 * lines than mapping composite keys and BigDecimal-as-TEXT converters.
 */
@Service
public class Portfolios {

    public static final BigDecimal DEFAULT_DEPOSIT = new BigDecimal("1000.00");
    public static final String DEFAULT_BENCHMARK = "SPY";

    private final JdbcClient db;
    private final Market market;
    private final TransactionTemplate tx;

    public Portfolios(JdbcClient db, Market market, TransactionTemplate tx) {
        this.db = db;
        this.market = market;
        this.tx = tx;
    }

    // ------------------------------------------------------------------ portfolios

    /** A new portfolio holds only cash: its deposit. Every argument but the name may be null for its default. */
    public Portfolio create(String name, BigDecimal deposit, String benchmark, String engine) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        BigDecimal amount = deposit == null ? DEFAULT_DEPOSIT : deposit;
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("deposit must be greater than 0");
        }
        if (amount.stripTrailingZeros().scale() > 2) {
            throw new IllegalArgumentException("deposit must not have more than 2 decimal places");
        }
        String bench = benchmark == null ? DEFAULT_BENCHMARK : benchmark;
        if (!Market.BENCHMARKS.contains(bench)) {
            throw new IllegalArgumentException("benchmark must be one of " + Market.BENCHMARKS);
        }
        String eng = engine == null ? Engines.QUANT_LLM : engine;
        if (!Engines.exists(eng)) {
            throw new IllegalArgumentException("engine must be " + Engines.QUANT_LLM + " or " + Engines.QUANT_ONLY);
        }
        String money = amount.setScale(2).toPlainString();
        LocalDate today = market.today();
        long id = tx.execute(status -> {
            db.sql("""
                    INSERT INTO portfolios (name, initial_deposit, cash, benchmark, engine, created_on)
                    VALUES (?, ?, ?, ?, ?, ?)""")
                    .params(name.trim(), money, money, bench, eng, today.toString())
                    .update();
            long newId = db.sql("SELECT last_insert_rowid()").query(Long.class).single();
            // The equity curve starts at the deposit on the day the portfolio is created.
            db.sql("INSERT OR REPLACE INTO equity (portfolio_id, trade_date, nav) VALUES (?, ?, ?)")
                    .params(newId, today.toString(), money)
                    .update();
            return newId;
        });
        return get(id);
    }

    public List<Portfolio> list(boolean includeArchived) {
        return db.sql("SELECT * FROM portfolios" + (includeArchived ? "" : " WHERE archived = 0") + " ORDER BY id")
                .query(Portfolios::portfolio)
                .list();
    }

    public Portfolio get(long id) {
        return db.sql("SELECT * FROM portfolios WHERE id = ?").param(id)
                .query(Portfolios::portfolio)
                .optional()
                .orElseThrow(() -> new NoSuchElementException("no portfolio " + id));
    }

    public PortfolioDetail detail(long id) {
        Portfolio p = get(id);
        LocalDate today = market.today();
        List<Holding> holdings = holdings(id);
        BigDecimal nav = nav(p.cash(), holdings, today, false);
        Map<String, Lot> lots = replayFills(p);
        List<Position> positions = holdings.stream().map(h -> {
            BigDecimal value = h.shares().multiply(market.bar(h.symbol(), today).close());
            Lot lot = lots.get(h.symbol());
            return new Position(h.symbol(), h.shares(), value, lot == null ? null : lot.cost(),
                    value.divide(nav, Engines.WEIGHT_SCALE, RoundingMode.HALF_EVEN));
        }).toList();
        return new PortfolioDetail(p, positions, nav,
                p.cash().divide(nav, Engines.WEIGHT_SCALE, RoundingMode.HALF_EVEN), today);
    }

    /** Simplification: archiving sets a flag, with no timestamp since nothing reads one. History is kept; jobs skip it. */
    public Portfolio archive(long id) {
        return setArchived(id, true);
    }

    public Portfolio restore(long id) {
        return setArchived(id, false);
    }

    private Portfolio setArchived(long id, boolean archived) {
        get(id);   // NoSuchElementException if it doesn't exist
        db.sql("UPDATE portfolios SET archived = ? WHERE id = ?").params(archived ? 1 : 0, id).update();
        return get(id);
    }

    // ------------------------------------------------------------- recommendations

    /**
     * Today's recommendation, stored as PROPOSED. Nothing in the portfolio changes until it is accepted
     * and then filled. Only for the current simulated day, and at most one per portfolio per day.
     */
    public Decision propose(long id) {
        Portfolio p = get(id);
        if (p.archived()) {
            throw new IllegalStateException("portfolio " + id + " is archived");
        }
        LocalDate today = market.today();
        long decisionId = tx.execute(status -> {
            int existing = db.sql("SELECT COUNT(*) FROM decisions WHERE portfolio_id = ? AND as_of = ?")
                    .params(id, today.toString()).query(Integer.class).single();
            if (existing > 0) {
                throw new IllegalStateException("portfolio " + id + " already has a recommendation for " + today);
            }
            List<Holding> holdings = holdings(id);
            BigDecimal nav = nav(p.cash(), holdings, today, false);
            Map<String, BigDecimal> prior = currentWeights(p.cash(), holdings, nav, today);
            Engines.Target target = Engines.target(p.engine(), today, market);

            db.sql("INSERT INTO decisions (portfolio_id, as_of, engine, status, nav) VALUES (?, ?, ?, ?, ?)")
                    .params(id, today.toString(), p.engine(), Status.PROPOSED.name(), nav.toPlainString())
                    .update();
            long newId = db.sql("SELECT last_insert_rowid()").query(Long.class).single();
            Set<String> symbols = new TreeSet<>(target.weights().keySet());
            symbols.addAll(prior.keySet());   // a held name missing from the target is a SELL to zero
            for (String symbol : symbols) {
                BigDecimal from = prior.getOrDefault(symbol, BigDecimal.ZERO);
                BigDecimal to = target.weights().getOrDefault(symbol, BigDecimal.ZERO);
                db.sql("""
                        INSERT INTO decision_lines (decision_id, symbol, prior_weight, target_weight, severity, action)
                        VALUES (?, ?, ?, ?, ?, ?)""")
                        .params(newId, symbol, from.toPlainString(), to.toPlainString(),
                                target.severity().getOrDefault(symbol, 0), action(symbol, from, to))
                        .update();
            }
            return newId;
        });
        return decision(decisionId);
    }

    public Decision accept(long portfolioId, long decisionId) {
        return decide(portfolioId, decisionId, Status.ACCEPTED);
    }

    public Decision ignore(long portfolioId, long decisionId) {
        return decide(portfolioId, decisionId, Status.IGNORED);
    }

    /**
     * Accept or ignore the whole recommendation, only on the day it was made. Once the simulated day moves
     * on, the next open has happened and the proposal has expired, even if the expire job hasn't run yet.
     * Accepting doesn't trade by itself: the settle job fills it at the next open.
     */
    private Decision decide(long portfolioId, long decisionId, Status to) {
        Decision d = decision(decisionId);
        if (d.portfolioId() != portfolioId) {
            throw new NoSuchElementException("no decision " + decisionId + " for portfolio " + portfolioId);
        }
        if (d.status() != Status.PROPOSED) {
            throw new IllegalStateException("decision " + decisionId + " is " + d.status() + ", not PROPOSED");
        }
        if (!d.asOf().equals(market.today())) {
            throw new IllegalStateException("decision " + decisionId
                    + " has expired: a recommendation must be accepted on the day it is made");
        }
        db.sql("UPDATE decisions SET status = ? WHERE id = ? AND status = 'PROPOSED'")
                .params(to.name(), decisionId)
                .update();
        return decision(decisionId);
    }

    public List<Decision> decisions(long portfolioId) {
        get(portfolioId);
        return db.sql("SELECT id FROM decisions WHERE portfolio_id = ? ORDER BY as_of DESC").param(portfolioId)
                .query(Long.class).list()
                .stream().map(this::decision).toList();
    }

    public Decision decision(long id) {
        List<Line> lines = db.sql("SELECT * FROM decision_lines WHERE decision_id = ? ORDER BY symbol").param(id)
                .query((rs, row) -> new Line(rs.getString("symbol"), dec(rs, "prior_weight"),
                        dec(rs, "target_weight"), rs.getInt("severity"), rs.getString("action")))
                .list();
        return db.sql("SELECT * FROM decisions WHERE id = ?").param(id)
                .query((rs, row) -> new Decision(rs.getLong("id"), rs.getLong("portfolio_id"),
                        LocalDate.parse(rs.getString("as_of")), rs.getString("engine"),
                        Status.valueOf(rs.getString("status")), dec(rs, "nav"), date(rs, "fill_date"), lines))
                .optional()
                .orElseThrow(() -> new NoSuchElementException("no decision " + id));
    }

    // --------------------------------------------------------------- nightly jobs

    /** The expire job: earlier days' proposals that nobody accepted. */
    public int expireAll() {
        return db.sql("""
                UPDATE decisions SET status = 'EXPIRED'
                WHERE status = 'PROPOSED' AND as_of < ?
                  AND portfolio_id IN (SELECT id FROM portfolios WHERE archived = 0)""")
                .param(market.today().toString())
                .update();
    }

    /** The settle job: fill every accepted recommendation at today's open, the first open after it was accepted. */
    public int settleAll() {
        LocalDate today = market.today();
        List<Long> ids = db.sql("""
                SELECT d.id FROM decisions d JOIN portfolios p ON p.id = d.portfolio_id
                WHERE d.status = 'ACCEPTED' AND d.as_of < ? AND p.archived = 0
                ORDER BY d.id""")
                .param(today.toString()).query(Long.class).list();
        for (long id : ids) {
            tx.executeWithoutResult(status -> settle(decision(id), today));
        }
        return ids.size();
    }

    private void settle(Decision d, LocalDate day) {
        Portfolio p = get(d.portfolioId());
        Map<String, Lot> lots = new TreeMap<>();
        for (Holding h : holdings(p.id())) {
            lots.put(h.symbol(), new Lot(h.shares(), BigDecimal.ZERO));   // cost isn't stored; settle only needs shares
        }
        BigDecimal cash = rebalance(p.cash(), lots, d, day);
        db.sql("DELETE FROM holdings WHERE portfolio_id = ?").param(p.id()).update();
        for (var e : lots.entrySet()) {
            db.sql("INSERT INTO holdings (portfolio_id, symbol, shares) VALUES (?, ?, ?)")
                    .params(p.id(), e.getKey(), e.getValue().shares().toPlainString())
                    .update();
        }
        db.sql("UPDATE portfolios SET cash = ? WHERE id = ?").params(cash.toPlainString(), p.id()).update();
        db.sql("UPDATE decisions SET status = 'FILLED', fill_date = ? WHERE id = ?")
                .params(day.toString(), d.id())
                .update();
    }

    /** A position's shares and what was paid for them. */
    private record Lot(BigDecimal shares, BigDecimal cost) {}

    /**
     * One fill: rebalance {@code lots} to the decision's target weights at {@code day}'s open and return the
     * new cash. No transaction costs (removed system-wide). Shares round down, so cash never goes negative.
     * Cost basis uses the average-cost method: buying adds what was paid, selling removes the sold
     * fraction of the basis. Shared by the settle job and by {@link #replayFills}, so the two can't drift apart.
     */
    private BigDecimal rebalance(BigDecimal cash, Map<String, Lot> lots, Decision d, LocalDate day) {
        BigDecimal navAtOpen = cash;
        for (var e : lots.entrySet()) {
            navAtOpen = navAtOpen.add(e.getValue().shares().multiply(market.bar(e.getKey(), day).open()));
        }
        Map<String, Lot> next = new TreeMap<>();
        BigDecimal invested = BigDecimal.ZERO;
        for (Line line : d.lines()) {
            if (line.symbol().equals(Engines.CASH) || line.targetWeight().signum() == 0) {
                continue;
            }
            BigDecimal open = market.bar(line.symbol(), day).open();
            // Fractional shares, as in the original: $1,000 can't buy whole shares of every name.
            BigDecimal shares = line.targetWeight().multiply(navAtOpen).divide(open, 6, RoundingMode.DOWN);
            Lot old = lots.getOrDefault(line.symbol(), new Lot(BigDecimal.ZERO, BigDecimal.ZERO));
            BigDecimal cost = shares.compareTo(old.shares()) >= 0
                    ? old.cost().add(shares.subtract(old.shares()).multiply(open))           // bought more at this open
                    : old.cost().multiply(shares).divide(old.shares(), 10, RoundingMode.HALF_EVEN);   // sold some
            next.put(line.symbol(), new Lot(shares, cost));
            invested = invested.add(shares.multiply(open));
        }
        lots.clear();
        lots.putAll(next);
        return navAtOpen.subtract(invested);
    }

    /**
     * Simplification: cost basis is derived by replaying the portfolio's fills, oldest first, through the
     * same {@link #rebalance} the settle job uses, instead of being stored. It needs no schema change or
     * migration, and at this scale the replay is instant.
     */
    private Map<String, Lot> replayFills(Portfolio p) {
        Map<String, Lot> lots = new TreeMap<>();
        BigDecimal cash = p.initialDeposit();
        List<Long> filled = db.sql("""
                SELECT id FROM decisions WHERE portfolio_id = ? AND status = 'FILLED'
                ORDER BY fill_date, id""")
                .param(p.id()).query(Long.class).list();
        for (long id : filled) {
            Decision d = decision(id);
            cash = rebalance(cash, lots, d, d.fillDate());
        }
        return lots;
    }

    /** The mark job: value every active portfolio at today's close, one point on its equity curve. */
    public int markAll() {
        LocalDate today = market.today();
        List<Portfolio> active = list(false);
        for (Portfolio p : active) {
            BigDecimal nav = nav(p.cash(), holdings(p.id()), today, false);
            db.sql("INSERT OR REPLACE INTO equity (portfolio_id, trade_date, nav) VALUES (?, ?, ?)")
                    .params(p.id(), today.toString(), nav.toPlainString())
                    .update();
        }
        return active.size();
    }

    /**
     * The propose job: today's recommendation for every active portfolio that doesn't have one yet.
     * One portfolio failing doesn't stop the others, but the job still fails loudly at the end.
     */
    public int proposeAll() {
        List<Long> ids = db.sql("""
                SELECT id FROM portfolios
                WHERE archived = 0 AND id NOT IN (SELECT portfolio_id FROM decisions WHERE as_of = ?)
                ORDER BY id""")
                .param(market.today().toString()).query(Long.class).list();
        List<String> failures = new ArrayList<>();
        for (long id : ids) {
            try {
                propose(id);
            } catch (RuntimeException e) {
                failures.add("portfolio " + id + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("propose failed for " + failures.size() + " portfolio(s): " + failures);
        }
        return ids.size();
    }

    /** Simplification: the chosen benchmark rebased to the deposit, instead of beta/alpha/IR statistics. */
    public List<EquityPoint> equity(long id) {
        Portfolio p = get(id);
        record Point(LocalDate date, BigDecimal nav) {}
        List<Point> points = db.sql("SELECT trade_date, nav FROM equity WHERE portfolio_id = ? ORDER BY trade_date")
                .param(id)
                .query((rs, row) -> new Point(LocalDate.parse(rs.getString("trade_date")), dec(rs, "nav")))
                .list();
        if (points.isEmpty()) {
            return List.of();
        }
        BigDecimal base = market.bar(p.benchmark(), points.getFirst().date()).close();
        return points.stream()
                .map(pt -> new EquityPoint(pt.date(), pt.nav(), p.initialDeposit()
                        .multiply(market.bar(p.benchmark(), pt.date()).close())
                        .divide(base, 2, RoundingMode.HALF_EVEN)))
                .toList();
    }

    // -------------------------------------------------------------------- helpers

    private List<Holding> holdings(long portfolioId) {
        return db.sql("SELECT symbol, shares FROM holdings WHERE portfolio_id = ? ORDER BY symbol").param(portfolioId)
                .query((rs, row) -> new Holding(rs.getString("symbol"), dec(rs, "shares")))
                .list();
    }

    /** cash + shares x price, at the day's open or close. */
    private BigDecimal nav(BigDecimal cash, List<Holding> holdings, LocalDate day, boolean atOpen) {
        BigDecimal nav = cash;
        for (Holding h : holdings) {
            Market.Bar bar = market.bar(h.symbol(), day);
            nav = nav.add(h.shares().multiply(atOpen ? bar.open() : bar.close()));
        }
        return nav;
    }

    /** Weights at a day's close. Rounded for display, so unlike targets they may not add up to exactly 1. */
    private Map<String, BigDecimal> currentWeights(BigDecimal cash, List<Holding> holdings, BigDecimal nav, LocalDate day) {
        Map<String, BigDecimal> weights = new TreeMap<>();
        for (Holding h : holdings) {
            BigDecimal value = h.shares().multiply(market.bar(h.symbol(), day).close());
            weights.put(h.symbol(), value.divide(nav, Engines.WEIGHT_SCALE, RoundingMode.HALF_EVEN));
        }
        weights.put(Engines.CASH, cash.divide(nav, Engines.WEIGHT_SCALE, RoundingMode.HALF_EVEN));
        return weights;
    }

    private static String action(String symbol, BigDecimal from, BigDecimal to) {
        if (symbol.equals(Engines.CASH)) {
            return "-";   // cash is the residual, not a trade
        }
        int c = to.compareTo(from);
        return c > 0 ? "BUY" : c < 0 ? "SELL" : "HOLD";
    }

    private static Portfolio portfolio(ResultSet rs, int row) throws SQLException {
        return new Portfolio(rs.getLong("id"), rs.getString("name"), dec(rs, "initial_deposit"), dec(rs, "cash"),
                rs.getString("benchmark"), rs.getString("engine"), LocalDate.parse(rs.getString("created_on")),
                rs.getInt("archived") == 1);
    }

    private static BigDecimal dec(ResultSet rs, String column) throws SQLException {
        return new BigDecimal(rs.getString(column));
    }

    private static LocalDate date(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        return value == null ? null : LocalDate.parse(value);
    }
}
