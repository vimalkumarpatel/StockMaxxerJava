package com.stockmaxxer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

/**
 * Does the filing signal say anything about what a stock does next? One row per FILING (never per event: the
 * ~10 events of one filing share one forward return, and counting it 10 times invents a pattern). Each row is
 * the filing's severity and the stock's next-20-trading-day return minus the average of all universe stocks,
 * from the open of the first trading day after the filing. Only revealed bars are used.
 */
@Service
public class Evals {

    static final int HORIZON = 20;   // trading days after the filing

    /** One filing: its severity (0 if no verified firm-scope event) and its forward excess return. */
    public record Row(String accession, int severity, BigDecimal excess) {}

    /** All filings with one severity: how many, and their mean excess return. */
    public record Bucket(int severity, int count, BigDecimal meanExcess) {}

    private final JdbcClient db;
    private final Market market;

    public Evals(JdbcClient db, Market market) {
        this.db = db;
        this.market = market;
    }

    /** The eval report, as printable lines. */
    public List<String> report() {
        List<Row> rows = rows();
        List<String> lines = new ArrayList<>();
        lines.add("filings with a full " + HORIZON + "-day window after them: " + rows.size());
        lines.add("severity  filings  mean excess return (stock minus universe average)");
        for (Bucket b : buckets(rows)) {
            lines.add("%8d  %7d  %+.2f%%".formatted(b.severity(), b.count(), b.meanExcess().movePointRight(2)));
        }
        lines.add("quotes: " + db.sql("SELECT COALESCE(SUM(verified), 0) || ' of ' || COUNT(*) || ' verified' FROM events")
                .query(String.class).single());
        lines.add("industry-scope events (discarded): " + db.sql("""
                        SELECT ROUND(100.0 * SUM(scope = 'industry') / MAX(COUNT(*), 1), 1) || '%' FROM events""")
                .query(String.class).single());
        lines.add("A negative excess for high severities is what the signal hopes for. With this few filings the"
                + " numbers are underpowered: no alpha claim.");
        return lines;
    }

    /** One row per scored filing that has a full forward window in the revealed bars. */
    List<Row> rows() {
        record Filing(String accession, String symbol, LocalDate filedOn, int severity) {}
        List<Filing> filings = db.sql("""
                        SELECT f.accession, f.symbol, f.filed_on,
                               COALESCE((SELECT MAX(severity) FROM events e WHERE e.accession = f.accession
                                         AND e.scope = 'firm' AND e.verified = 1), 0) AS severity
                        FROM filings f WHERE f.scored = 1 ORDER BY f.filed_on""")
                .query((rs, row) -> new Filing(rs.getString("accession"), rs.getString("symbol"),
                        LocalDate.parse(rs.getString("filed_on")), rs.getInt("severity")))
                .list();
        LocalDate firstBar = db.sql("SELECT MIN(trade_date) FROM bars").query(String.class).optional()
                .map(LocalDate::parse).orElse(market.today());
        List<String> universe = Edgar.stocks();
        List<Row> rows = new ArrayList<>();
        for (Filing f : filings) {
            List<LocalDate> days = market.tradingDays(f.filedOn().plusDays(1), market.today());
            if (f.filedOn().isBefore(firstBar) || days.size() < HORIZON) {
                continue;   // filed before the revealed bars begin, or no full window yet
            }
            LocalDate entry = days.getFirst();
            LocalDate exit = days.get(HORIZON - 1);
            BigDecimal sum = BigDecimal.ZERO;
            for (String s : universe) {
                sum = sum.add(forward(s, entry, exit));
            }
            BigDecimal average = sum.divide(BigDecimal.valueOf(universe.size()), MathContext.DECIMAL64);
            rows.add(new Row(f.accession(), f.severity(), forward(f.symbol(), entry, exit).subtract(average)));
        }
        return rows;
    }

    /** From the entry day's open to the exit day's close. */
    private BigDecimal forward(String symbol, LocalDate entry, LocalDate exit) {
        return market.bar(symbol, exit).close().divide(market.bar(symbol, entry).open(), MathContext.DECIMAL64)
                .subtract(BigDecimal.ONE);
    }

    /** Count and mean excess return for each severity. */
    static List<Bucket> buckets(List<Row> rows) {
        Map<Integer, List<BigDecimal>> bySeverity = new TreeMap<>();
        for (Row r : rows) {
            bySeverity.computeIfAbsent(r.severity(), s -> new ArrayList<>()).add(r.excess());
        }
        List<Bucket> buckets = new ArrayList<>();
        for (var e : bySeverity.entrySet()) {
            BigDecimal mean = Engines.sum(e.getValue())
                    .divide(BigDecimal.valueOf(e.getValue().size()), 6, RoundingMode.HALF_EVEN);
            buckets.add(new Bucket(e.getKey(), e.getValue().size(), mean));
        }
        return buckets;
    }
}
