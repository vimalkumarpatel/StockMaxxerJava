package com.stockmaxxer;

import java.time.LocalDate;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The audit trail: one append-only row for everything that happens to a recommendation (proposed, accepted,
 * ignored, expired, filled), each with a JSON detail. Rows are written in the same transaction as the change
 * they record. Two SQLite triggers reject any UPDATE or DELETE, so a row can't be changed once written.
 */
@Component
public class Audit {

    /** One audit row. detail is the JSON written at the time. */
    public record Entry(long id, long decisionId, LocalDate day, String kind, JsonNode detail) {}

    private final JdbcClient db;
    private final ObjectMapper json = new ObjectMapper();

    public Audit(JdbcClient db) {
        this.db = db;
        // Created here, not in schema.sql: Spring splits that file at every ';', which breaks a trigger body.
        for (String action : List.of("UPDATE", "DELETE")) {
            db.sql("CREATE TRIGGER IF NOT EXISTS audit_no_" + action.toLowerCase() + " BEFORE " + action
                    + " ON audit BEGIN SELECT RAISE(ABORT, 'the audit trail is append-only'); END").update();
        }
    }

    /** Append one row. {@code detail} is any object Jackson can write (maps, lists, records). */
    public void record(long decisionId, LocalDate day, String kind, Object detail) {
        try {
            db.sql("INSERT INTO audit (decision_id, day, kind, detail) VALUES (?, ?, ?, ?)")
                    .params(decisionId, day.toString(), kind, json.writeValueAsString(detail))
                    .update();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot write audit detail", e);
        }
    }

    /** One recommendation's audit rows, oldest first. */
    public List<Entry> entries(long decisionId) {
        return db.sql("SELECT * FROM audit WHERE decision_id = ? ORDER BY id").param(decisionId)
                .query((rs, row) -> new Entry(rs.getLong("id"), rs.getLong("decision_id"),
                        LocalDate.parse(rs.getString("day")), rs.getString("kind"), parse(rs.getString("detail"))))
                .list();
    }

    private JsonNode parse(String text) {
        try {
            return json.readTree(text);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("bad audit detail", e);
        }
    }
}
