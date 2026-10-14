package com.stockmaxxer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The filing signal. The signals job reads each new filing's Item 1A, finds the sentences that are new since
 * the company's previous filing of the same form, and asks Claude for typed risk events. {@link #flags} then
 * gives each stock's severity on a day: what its latest filing before that day says.
 */
@Service
public class Signals {

    /** Why a stock is trimmed: the severity, and the filing and sentence behind it. */
    public record Flag(int severity, String accession, String form, LocalDate filedOn, String quote) {}

    private record Filing(String accession, String symbol, String form, LocalDate filedOn, String item1a) {}

    private final JdbcClient db;
    private final Llm llm;
    private final TransactionTemplate tx;

    public Signals(JdbcClient db, Llm llm, TransactionTemplate tx) {
        this.db = db;
        this.llm = llm;
        this.tx = tx;
    }

    /**
     * The signals job: score every filing not scored yet, oldest first. One filing failing (a refused or cut-off
     * answer) doesn't stop the others, but the job still fails loudly at the end, and the next run tries it again.
     */
    public int scoreAll() {
        List<Filing> todo = db.sql("SELECT accession, symbol, form, filed_on, item1a FROM filings WHERE scored = 0 ORDER BY filed_on, accession")
                .query((rs, row) -> new Filing(rs.getString("accession"), rs.getString("symbol"), rs.getString("form"),
                        LocalDate.parse(rs.getString("filed_on")), rs.getString("item1a")))
                .list();
        List<String> failures = new ArrayList<>();
        for (Filing f : todo) {
            try {
                score(f);
            } catch (RuntimeException e) {
                failures.add(f.symbol() + " " + f.form() + " " + f.accession() + ": " + e.getMessage());
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("signals failed for " + failures.size() + " filing(s): " + failures);
        }
        return todo.size();
    }

    /** One filing: diff against the previous filing of the same form, ask Claude only if something is new. */
    private void score(Filing f) {
        String previous = db.sql("""
                        SELECT item1a FROM filings WHERE symbol = ? AND form = ? AND filed_on < ?
                        ORDER BY filed_on DESC LIMIT 1""")
                .params(f.symbol(), f.form(), f.filedOn().toString()).query(String.class).optional().orElse(null);
        // Simplification: the first filing of each form has nothing to compare with, so it is a baseline (no events).
        List<String> added = previous == null ? List.of() : Filings.newSentences(previous, f.item1a());
        Llm.Answer answer = added.isEmpty() ? null
                : llm.extract(Prompts.extraction(f.symbol(), f.form(), f.filedOn(), added));
        tx.executeWithoutResult(status -> {
            db.sql("DELETE FROM events WHERE accession = ?").param(f.accession()).update();
            List<Llm.Event> events = answer == null ? List.of() : answer.events().events();
            for (int n = 0; n < events.size(); n++) {
                Llm.Event e = events.get(n);
                db.sql("INSERT INTO events (accession, n, type, severity, scope, quote) VALUES (?, ?, ?, ?, ?, ?)")
                        .params(f.accession(), n, e.type(), e.severity(), e.scope(), e.quote()).update();
            }
            db.sql("UPDATE filings SET scored = 1, new_sentences = ?, llm_key = ?, llm_model = ? WHERE accession = ?")
                    .params(added.size(), answer == null ? null : answer.key(), answer == null ? null : answer.model(),
                            f.accession())
                    .update();
        });
    }

    /**
     * The flagged stocks on {@code asOf}: for each one, its latest scored filing filed before that day, and the most
     * severe firm-scope event in it. A filing counts from the day after it was filed. Simplifications: the latest
     * filing replaces the earlier ones (no "severity 5 stays until superseded", no time decay), events the model
     * calls industry-wide are dropped, and a newer filing that isn't scored yet is ignored until it is.
     */
    public Map<String, Flag> flags(List<String> symbols, LocalDate asOf) {
        Map<String, Flag> flags = new TreeMap<>();
        for (String symbol : symbols) {
            db.sql("""
                            SELECT f.accession, f.form, f.filed_on, e.severity, e.quote
                            FROM filings f JOIN events e ON e.accession = f.accession
                            WHERE f.accession = (SELECT accession FROM filings WHERE symbol = ? AND scored = 1 AND filed_on < ?
                                                 ORDER BY filed_on DESC, accession DESC LIMIT 1)
                              AND e.scope = 'firm' AND e.severity > 0
                            ORDER BY e.severity DESC, e.n LIMIT 1""")
                    .params(symbol, asOf.toString())
                    .query((rs, row) -> new Flag(rs.getInt("severity"), rs.getString("accession"), rs.getString("form"),
                            LocalDate.parse(rs.getString("filed_on")), rs.getString("quote")))
                    .optional()
                    .ifPresent(flag -> flags.put(symbol, flag));
        }
        return flags;
    }
}
