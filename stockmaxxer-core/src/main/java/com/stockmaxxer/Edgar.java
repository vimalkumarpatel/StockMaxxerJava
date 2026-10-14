package com.stockmaxxer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The filings job: download every 10-K and 10-Q of the universe stocks from SEC EDGAR and store each one complete.
 * The raw document is kept gzipped in {@code data/filings/} (it survives a database reset, so it is never
 * downloaded twice), and {@code filings.text} holds the whole normalized text, so later signals can use any part.
 */
@Component
public class Edgar {

    static final LocalDate FILINGS_START = LocalDate.of(2024, 1, 1);   // so every replayed filing has a predecessor
    static final List<String> FORMS = List.of("10-K", "10-Q");

    private final JdbcClient db;
    private final String userAgent;
    private final Path rawDir;
    private final HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper json = new ObjectMapper();

    public Edgar(JdbcClient db, @Value("${SM_SEC_USER_AGENT:}") String userAgent,
                 @Value("${SM_DATA_DIR:data}") String dataDir) {
        this.db = db;
        this.userAgent = userAgent;
        this.rawDir = Path.of(dataDir, "filings");
    }

    /** Download and store every filing not stored yet. Returns how many were added. */
    public int download() {
        Map<String, Long> ciks = ciks();
        int added = 0;
        for (String symbol : stocks()) {
            Long cik = ciks.get(symbol);
            if (cik == null) {
                throw new IllegalStateException("SEC has no CIK for " + symbol);
            }
            JsonNode submissions = get("https://data.sec.gov/submissions/CIK%010d.json".formatted(cik));
            added += downloadList(symbol, cik, submissions.path("filings").path("recent"));
            // Busy filers have older filings in extra files; read those that reach back to FILINGS_START.
            for (JsonNode file : submissions.path("filings").path("files")) {
                if (file.path("filingTo").asText().compareTo(FILINGS_START.toString()) >= 0) {
                    added += downloadList(symbol, cik, get("https://data.sec.gov/submissions/" + file.path("name").asText()));
                }
            }
        }
        return added;
    }

    /** One EDGAR filing list: parallel arrays of form, filingDate, accessionNumber and primaryDocument. */
    private int downloadList(String symbol, long cik, JsonNode list) {
        int added = 0;
        for (int i = 0; i < list.path("form").size(); i++) {
            String form = list.path("form").get(i).asText();
            String filedOn = list.path("filingDate").get(i).asText();
            String accession = list.path("accessionNumber").get(i).asText();
            if (!FORMS.contains(form) || filedOn.compareTo(FILINGS_START.toString()) < 0 || stored(accession)) {
                continue;
            }
            String url = "https://www.sec.gov/Archives/edgar/data/%d/%s/%s"
                    .formatted(cik, accession.replace("-", ""), list.path("primaryDocument").get(i).asText());
            store(symbol, cik, form, LocalDate.parse(filedOn), accession, url, raw(accession, url));
            added++;
        }
        return added;
    }

    /** Save one filing: the whole normalized text, and the Item 1A section cut from it. */
    void store(String symbol, long cik, String form, LocalDate filedOn, String accession, String url, String html) {
        String text = Filings.normalize(html);
        db.sql("""
                INSERT OR IGNORE INTO filings (accession, symbol, cik, form, filed_on, doc_url, text, item1a)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
                .params(accession, symbol, cik, form, filedOn.toString(), url, text, Filings.item1a(text, form))
                .update();
    }

    private boolean stored(String accession) {
        return db.sql("SELECT COUNT(*) FROM filings WHERE accession = ?").param(accession).query(Integer.class).single() > 0;
    }

    /** The raw document: read from data/filings/ if there, otherwise downloaded and saved there first. */
    private String raw(String accession, String url) {
        Path file = rawDir.resolve(accession + ".htm.gz");
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(rawDir);
                try (OutputStream out = new GZIPOutputStream(Files.newOutputStream(file))) {
                    out.write(fetch(url).getBytes(StandardCharsets.UTF_8));
                }
            }
            try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
                return new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot store " + file, e);
        }
    }

    /** Ticker → CIK, from SEC's list of every company ticker. */
    private Map<String, Long> ciks() {
        Map<String, Long> result = new HashMap<>();
        for (JsonNode c : get("https://www.sec.gov/files/company_tickers.json")) {
            result.put(c.path("ticker").asText(), c.path("cik_str").asLong());
        }
        return result;
    }

    /** Every stock in any universe (the benchmarks are ETFs and file no 10-Ks). */
    static List<String> stocks() {
        TreeSet<String> all = new TreeSet<>();
        Market.UNIVERSES.values().forEach(all::addAll);
        return List.copyOf(all);
    }

    private JsonNode get(String url) {
        try {
            return json.readTree(fetch(url));
        } catch (IOException e) {
            throw new IllegalStateException("bad JSON from " + url, e);
        }
    }

    /** One request. SEC asks for a contact in the User-Agent and at most 10 requests a second. */
    private String fetch(String url) {
        if (userAgent.isBlank()) {
            throw new IllegalStateException("set SM_SEC_USER_AGENT (for example \"StockMaxxer you@example.com\"): SEC requires a contact");
        }
        try {
            Thread.sleep(150);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url)).header("User-Agent", userAgent).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("SEC returned " + response.statusCode() + " for " + url);
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("SEC request failed: " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
